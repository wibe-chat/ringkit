// ringkit fleet telemetry — read/debug dashboard (Netlify Function, Node).
//
// One self-contained, ZERO-DEPENDENCY function serves the HTML dashboard AND its
// JSON API (so it deploys by drag-and-drop with no build step). It reads every
// telemetry table over Supabase's REST API with the SERVICE ROLE key (the tables
// are deny-all RLS, so only this — or Studio — can see them). Hand the deployed
// URL + the dashboard password to developers: they browse devices across ALL
// apps, read the decompressed black-box log per incident, and trigger the two
// SAFE debug knobs (raise capture level, request a full-buffer pull).
//
// It never exposes the fleet api keys, device tokens, or the kill switch.
// Auth: every /?api=... call must carry `x-dashboard-key: <password>` (stored
// server-side in fleet_config key='dashboard_password').
//
// Env (Netlify → Site configuration → Environment variables):
//   SUPABASE_URL                — https://<ref>.supabase.co
//   SUPABASE_SERVICE_ROLE_KEY   — Supabase → Settings → API → service_role (secret)

const SB = (process.env.SUPABASE_URL || "").replace(/\/+$/, "");
const SR = process.env.SUPABASE_SERVICE_ROLE_KEY || "";
const AUTH = { apikey: SR, Authorization: "Bearer " + SR };
const LEVELS = ["none", "error", "info", "verbose"];

function json(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

// PostgREST query → parsed JSON array (or null). opts: {method, body, headers}.
async function rest(pathAndQuery, opts = {}) {
  const r = await fetch(SB + "/rest/v1/" + pathAndQuery, {
    method: opts.method || "GET",
    headers: Object.assign({}, AUTH, opts.headers || {}),
    body: opts.body,
  });
  if (!r.ok && r.status !== 206) throw new Error("rest " + r.status + " " + pathAndQuery);
  const text = await r.text();
  return text ? JSON.parse(text) : null;
}

// Exact row count via the Content-Range header (Prefer: count=exact).
async function count(pathAndQuery) {
  const r = await fetch(SB + "/rest/v1/" + pathAndQuery, { headers: Object.assign({ Prefer: "count=exact" }, AUTH) });
  const cr = r.headers.get("content-range") || "*/0";
  return parseInt(cr.split("/")[1] || "0", 10) || 0;
}

function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function authed(req) {
  const key = req.headers.get("x-dashboard-key") ?? "";
  const rows = await rest("fleet_config?select=value&key=eq.dashboard_password&limit=1");
  const pw = (rows && rows[0] && rows[0].value) || "";
  return !!pw && !!key && safeEqual(key, pw);
}

async function gunzipText(bytes) {
  const stream = new Response(bytes).body.pipeThrough(new DecompressionStream("gzip"));
  return await new Response(stream).text();
}

// Strong random fleet key (48 hex chars) — Web Crypto, global on Node 18+/Netlify.
function genKey() {
  const b = new Uint8Array(24);
  crypto.getRandomValues(b);
  return Array.from(b).map((x) => x.toString(16).padStart(2, "0")).join("");
}

const enc = encodeURIComponent;

const JSON_MIN = { "Content-Type": "application/json", Prefer: "return=minimal" };

export default async (req) => {
  const url = new URL(req.url);
  const api = url.searchParams.get("api");

  if (!api) return new Response(HTML, { headers: { "Content-Type": "text/html; charset=utf-8" } });
  if (!SB || !SR) return json(500, { error: "server not configured (SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY)" });
  if (!(await authed(req))) return json(401, { error: "unauthorized" });

  try {
    if (api === "summary") {
      const [devices, openIssues, apps] = await Promise.all([
        count("devices?select=install_id&limit=1"),
        count("issues?select=fingerprint&status=eq.open&limit=1"),
        rest("devices?select=app_id"),
      ]);
      const appList = [...new Set((apps || []).map((r) => r.app_id ?? "default"))].sort();
      return json(200, { devices, openIssues, apps: appList });
    }

    if (api === "devices") {
      const app = url.searchParams.get("app") ?? "";
      const q = url.searchParams.get("q") ?? "";
      let qs = "devices?select=install_id,app_id,package_name,device_label,manufacturer,model,sdk_int,app_version,plugin_version,last_seen,log_level,pull_requested,sampled,killed,bytes_today&order=last_seen.desc&limit=500";
      if (app) qs += "&app_id=eq." + enc(app);
      if (q) qs += "&device_label=ilike.*" + enc(q) + "*";
      return json(200, { devices: (await rest(qs)) || [] });
    }

    if (api === "device") {
      const id = url.searchParams.get("install") ?? "";
      const [dev, inc, hb] = await Promise.all([
        rest("devices?select=install_id,app_id,package_name,device_label,manufacturer,model,sdk_int,app_version,plugin_version,first_seen,last_seen,log_level,pull_requested,sampled,killed,budget_kb,bytes_today&install_id=eq." + enc(id) + "&limit=1"),
        rest("incidents?select=id,kind,fingerprint,call_id,device_ts,server_ts,clock_offset_ms,payload_path&install_id=eq." + enc(id) + "&order=server_ts.desc&limit=100"),
        rest("heartbeats?select=day,counters,fingerprints,received_at&install_id=eq." + enc(id) + "&order=day.desc&limit=14"),
      ]);
      if (!dev || !dev[0]) return json(404, { error: "no such device" });
      const fps = [...new Set((inc || []).map((r) => r.fingerprint).filter(Boolean))];
      let byFp = {};
      if (fps.length) {
        const issues = await rest("issues?select=fingerprint,incident_type,codepoint,degradations&fingerprint=in.(" + fps.map(enc).join(",") + ")");
        byFp = Object.fromEntries((issues || []).map((i) => [i.fingerprint, i]));
      }
      const incidents = (inc || []).map((r) => ({ ...r, issue: byFp[r.fingerprint] ?? null }));
      return json(200, { device: dev[0], incidents, heartbeats: hb || [] });
    }

    if (api === "issues") {
      const app = url.searchParams.get("app") ?? "";
      let fpFilter = null;
      if (app) {
        const appDevs = await rest("devices?select=install_id&app_id=eq." + enc(app));
        const ids = (appDevs || []).map((d) => d.install_id);
        const id2 = ids.length ? await rest("issue_devices?select=fingerprint&install_id=in.(" + ids.map(enc).join(",") + ")") : [];
        fpFilter = [...new Set((id2 || []).map((r) => r.fingerprint))];
      }
      let qs = "issues?select=fingerprint,incident_type,codepoint,degradations,device_count,occurrence_count,status,first_seen,last_seen&order=device_count.desc,occurrence_count.desc&limit=200";
      if (fpFilter) qs += "&fingerprint=in.(" + (fpFilter.length ? fpFilter.map(enc).join(",") : "__none__") + ")";
      return json(200, { issues: (await rest(qs)) || [] });
    }

    if (api === "payload") {
      const path = url.searchParams.get("path") ?? "";
      if (!/^(incidents|pulls)\/[A-Za-z0-9\-/.]+\.gz$/.test(path)) return json(400, { error: "bad path" });
      const r = await fetch(SB + "/storage/v1/object/ringkit-logs/" + path, { headers: AUTH });
      if (!r.ok) return json(404, { error: "no payload" });
      const text = await gunzipText(new Uint8Array(await r.arrayBuffer()));
      const records = text.split("\n").filter((l) => l.trim()).map((l) => {
        try { return JSON.parse(l); } catch { return { _raw: l }; }
      });
      return json(200, { records });
    }

    if (api === "knob" && req.method === "POST") {
      const body = await req.json().catch(() => ({}));
      const id = body.install ?? "";
      const patch = {};
      if (typeof body.log_level === "string" && LEVELS.includes(body.log_level)) patch.log_level = body.log_level;
      if (typeof body.pull_requested === "boolean") patch.pull_requested = body.pull_requested;
      if (!id || !Object.keys(patch).length) return json(400, { error: "nothing to set" });
      await rest("devices?install_id=eq." + enc(id), {
        method: "PATCH",
        headers: { "Content-Type": "application/json", Prefer: "return=minimal" },
        body: JSON.stringify(patch),
      });
      return json(200, { ok: true, applied: patch });
    }

    // ── key management (internal — the password gate is the boundary) ──────
    if (api === "keys") {
      return json(200, { keys: (await rest("fleet_keys?select=api_key,app_id,label,active,created_at&order=created_at.desc")) || [] });
    }

    if (api === "keyCreate" && req.method === "POST") {
      const b = await req.json().catch(() => ({}));
      const app_id = (b.app_id || "").trim();
      const label = (b.label || "").trim() || app_id;
      if (!app_id) return json(400, { error: "app name required" });
      const api_key = genKey();
      const row = await rest("fleet_keys", {
        method: "POST",
        headers: { "Content-Type": "application/json", Prefer: "return=representation" },
        body: JSON.stringify({ api_key, app_id, label, active: true }),
      });
      return json(200, { key: (row && row[0]) || { api_key, app_id, label, active: true } });
    }

    if (api === "keyRename" && req.method === "POST") {
      const b = await req.json().catch(() => ({}));
      const api_key = b.api_key || "";
      const app_id = (b.app_id || "").trim();
      const label = (b.label || "").trim() || app_id;
      if (!api_key || !app_id) return json(400, { error: "app name required" });
      const cur = await rest("fleet_keys?select=app_id&api_key=eq." + enc(api_key) + "&limit=1");
      const oldApp = cur && cur[0] && cur[0].app_id;
      await rest("fleet_keys?api_key=eq." + enc(api_key), { method: "PATCH", headers: JSON_MIN, body: JSON.stringify({ app_id, label }) });
      // Cascade the rename onto this app's already-registered devices so the
      // whole fleet re-labels at once (fleet_daily history self-heals on the
      // next nightly roll-up).
      if (oldApp && oldApp !== app_id) {
        await rest("devices?app_id=eq." + enc(oldApp), { method: "PATCH", headers: JSON_MIN, body: JSON.stringify({ app_id }) });
      }
      return json(200, { ok: true });
    }

    if (api === "keyActive" && req.method === "POST") {
      const b = await req.json().catch(() => ({}));
      if (!b.api_key || typeof b.active !== "boolean") return json(400, { error: "bad request" });
      await rest("fleet_keys?api_key=eq." + enc(b.api_key), { method: "PATCH", headers: JSON_MIN, body: JSON.stringify({ active: b.active }) });
      return json(200, { ok: true });
    }

    if (api === "keyDelete" && req.method === "POST") {
      const b = await req.json().catch(() => ({}));
      if (!b.api_key) return json(400, { error: "bad request" });
      await rest("fleet_keys?api_key=eq." + enc(b.api_key), { method: "DELETE", headers: { Prefer: "return=minimal" } });
      return json(200, { ok: true });
    }

    return json(400, { error: "unknown api" });
  } catch (e) {
    console.error("dashboard error:", e && e.message ? e.message : e);
    return json(500, { error: "internal" });
  }
};

// ── single-page dashboard (vanilla JS, event-delegated; the embedded client
//    script uses no backticks / ${} so this template literal needs no escaping) ─
const HTML = `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>ringkit fleet</title>
<style>
  :root{--bg:#0d1117;--panel:#161b22;--line:#30363d;--fg:#e6edf3;--dim:#8b949e;--accent:#2f81f7;--red:#f85149;--amber:#d29922;--green:#3fb950}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
  header{display:flex;gap:12px;align-items:center;padding:10px 16px;border-bottom:1px solid var(--line);background:var(--panel);position:sticky;top:0;z-index:5;flex-wrap:wrap}
  header h1{font-size:15px;margin:0;font-weight:600}header h1 span{color:var(--dim);font-weight:400}
  .grow{flex:1}
  select,input,button{background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:6px;padding:6px 10px;font:inherit}
  button{cursor:pointer}button:hover{border-color:var(--accent)}
  .tabs button.on{background:var(--accent);border-color:var(--accent);color:#fff}
  main{padding:16px;max-width:1400px;margin:0 auto}
  table{width:100%;border-collapse:collapse;font-size:13px}
  th,td{text-align:left;padding:7px 10px;border-bottom:1px solid var(--line);white-space:nowrap}
  th{color:var(--dim);font-weight:600}
  tbody tr{cursor:pointer}tbody tr:hover{background:var(--panel)}
  .mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
  .pill{display:inline-block;padding:1px 8px;border-radius:10px;font-size:11px;border:1px solid var(--line)}
  .pill.verbose{color:var(--amber);border-color:var(--amber)}
  .pill.killed{color:var(--red);border-color:var(--red)}
  .pill.pull{color:var(--accent);border-color:var(--accent)}
  .dim{color:var(--dim)}
  .drawer{position:fixed;top:0;right:0;height:100%;width:min(760px,94vw);background:var(--panel);border-left:1px solid var(--line);transform:translateX(100%);transition:.18s;overflow:auto;z-index:20;padding:18px}
  .drawer.open{transform:none}
  .drawer h2{margin:.4em 0 .2em;font-size:16px}
  .kv{display:grid;grid-template-columns:130px 1fr;gap:2px 10px;margin:10px 0;font-size:13px}
  .kv div:nth-child(odd){color:var(--dim)}
  .row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:10px 0}
  .close{position:absolute;top:12px;right:14px;font-size:22px;background:none;border:none;color:var(--dim)}
  pre{background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:10px;overflow:auto;font-size:11.5px;max-height:52vh}
  .rec{border-bottom:1px solid var(--line);padding:4px 0}.rec .lvl{display:inline-block;width:58px}
  .ERROR{color:var(--red)}.INFO{color:var(--green)}.VERBOSE{color:var(--dim)}
  .gate{position:fixed;inset:0;background:var(--bg);display:flex;align-items:center;justify-content:center;z-index:50}
  .gate form{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:24px;width:320px;text-align:center}
  .gate h1{font-size:16px}.gate input{width:100%;margin:10px 0}
  .empty{color:var(--dim);padding:30px;text-align:center}.err{color:var(--red)}
</style></head><body>

<div class="gate" id="gate"><form id="loginForm">
  <h1>ringkit fleet</h1><p class="dim">Enter the dashboard password</p>
  <input id="pw" type="password" autofocus autocomplete="current-password" placeholder="password">
  <button type="submit">Sign in</button><p class="err" id="loginErr"></p>
</form></div>

<header style="display:none" id="hdr">
  <h1>ringkit fleet <span id="counts"></span></h1>
  <div class="tabs"><button class="on" data-act="tab" data-tab="devices">Devices</button>
    <button data-act="tab" data-tab="issues">Issues</button>
    <button data-act="tab" data-tab="keys">Keys</button></div>
  <select id="appFilter"></select>
  <input id="search" placeholder="search label...">
  <div class="grow"></div>
  <button data-act="refresh">Refresh</button>
  <button class="dim" data-act="logout">Sign out</button>
</header>
<main id="main" style="display:none"><div class="empty">Loading...</div></main>
<div class="drawer" id="drawer"><button class="close" data-act="close">&times;</button><div id="drawerBody"></div></div>

<script>
var KEY=null,TAB='devices';
function el(id){return document.getElementById(id)}
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function api(name,opts){opts=opts||{};opts.headers=Object.assign({'x-dashboard-key':KEY},opts.headers||{});
  return fetch('?api='+name+(opts.qs||''),opts).then(function(r){if(r.status===401){logout();throw new Error('unauthorized')}return r.json()})}
function ago(ts){if(!ts)return '-';var s=(Date.now()-new Date(ts).getTime())/1000;if(s<60)return Math.round(s)+'s';if(s<3600)return Math.round(s/60)+'m';if(s<86400)return Math.round(s/3600)+'h';return Math.round(s/86400)+'d'}
function kb(b){return Math.round((b||0)/1024*10)/10+' KB'}

el('loginForm').addEventListener('submit',function(e){e.preventDefault();KEY=el('pw').value;
  api('summary').then(function(d){sessionStorage.setItem('dk',KEY);boot(d)})
   .catch(function(){el('loginErr').textContent='Wrong password';KEY=null})});
function logout(){sessionStorage.removeItem('dk');location.reload()}
function boot(d){el('gate').style.display='none';el('hdr').style.display='flex';el('main').style.display='block';
  el('counts').textContent='- '+d.devices+' devices - '+d.openIssues+' open issues';
  el('appFilter').innerHTML='<option value="">all apps</option>'+d.apps.map(function(a){return '<option>'+esc(a)+'</option>'}).join('');
  reload()}
function qstr(){var a=el('appFilter').value,q=el('search').value,s=[];
  if(a)s.push('app='+encodeURIComponent(a));if(q)s.push('q='+encodeURIComponent(q));return s.length?'&'+s.join('&'):''}
function reload(){var m=el('main');m.innerHTML='<div class="empty">Loading...</div>';
  if(TAB==='devices')api('devices',{qs:qstr()}).then(renderDevices).catch(function(e){m.innerHTML='<div class="empty err">'+esc(e.message)+'</div>'});
  else if(TAB==='keys')api('keys').then(renderKeys).catch(function(e){m.innerHTML='<div class="empty err">'+esc(e.message)+'</div>'});
  else api('issues',{qs:qstr()}).then(renderIssues)}

function renderDevices(d){var r=d.devices;if(!r.length){el('main').innerHTML='<div class="empty">No devices</div>';return}
  var t='<table><thead><tr><th>App</th><th>Label</th><th>Device</th><th>SDK</th><th>App ver</th><th>Last seen</th><th>Level</th><th>Flags</th><th>Today</th></tr></thead><tbody>';
  r.forEach(function(x){t+='<tr data-act="device" data-install="'+esc(x.install_id)+'">'+
    '<td>'+esc(x.app_id)+'</td><td>'+esc(x.device_label)+'</td><td class="dim">'+esc(x.manufacturer)+' '+esc(x.model)+'</td>'+
    '<td>'+esc(x.sdk_int)+'</td><td>'+esc(x.app_version)+'</td><td>'+ago(x.last_seen)+' ago</td>'+
    '<td><span class="pill '+(x.log_level==='verbose'?'verbose':'')+'">'+esc(x.log_level)+'</span></td>'+
    '<td>'+(x.pull_requested?'<span class="pill pull">pull</span> ':'')+(x.killed?'<span class="pill killed">killed</span>':'')+'</td>'+
    '<td class="dim">'+kb(x.bytes_today)+'</td></tr>'});
  el('main').innerHTML=t+'</tbody></table>'}

function renderIssues(d){var r=d.issues;if(!r.length){el('main').innerHTML='<div class="empty">No issues</div>';return}
  var t='<table><thead><tr><th>Type</th><th>Codepoint</th><th>Degradations</th><th>Devices</th><th>Occurrences</th><th>Status</th><th>Last seen</th></tr></thead><tbody>';
  r.forEach(function(x){t+='<tr><td>'+esc(x.incident_type)+'</td><td class="mono">'+esc(x.codepoint)+'</td>'+
    '<td class="dim">'+esc((x.degradations||[]).join(', '))+'</td><td>'+esc(x.device_count)+'</td>'+
    '<td>'+esc(x.occurrence_count)+'</td><td>'+esc(x.status)+'</td><td>'+ago(x.last_seen)+' ago</td></tr>'});
  el('main').innerHTML=t+'</tbody></table>'}

function renderKeys(d){var r=d.keys||[];
  var t='<div class="row"><b>New key</b> <input id="nkApp" placeholder="app name (e.g. mallu-talks)"><input id="nkLabel" placeholder="note (optional)"><button data-act="keyCreate">+ Create key</button></div>'+
    '<p class="dim" style="margin:0 0 8px">Give each app its own key. Paste the app name + key into that app in your telemetry config. Renaming an app cascades to its devices.</p>';
  t+='<table><thead><tr><th>App name</th><th>Note</th><th>Key (click to select)</th><th>Active</th><th></th></tr></thead><tbody>';
  if(!r.length)t+='<tr><td colspan="5" class="dim">no keys yet</td></tr>';
  r.forEach(function(x){t+='<tr>'+
    '<td>'+esc(x.app_id)+'</td><td class="dim">'+esc(x.label||'')+'</td>'+
    '<td><span class="mono" style="user-select:all">'+esc(x.api_key)+'</span> <button data-act="keyCopy" data-key="'+esc(x.api_key)+'">copy</button></td>'+
    '<td><button data-act="keyActive" data-key="'+esc(x.api_key)+'" data-next="'+(x.active?'0':'1')+'">'+(x.active?'on':'off')+'</button></td>'+
    '<td><button data-act="keyRename" data-key="'+esc(x.api_key)+'" data-app="'+esc(x.app_id)+'" data-label="'+esc(x.label||'')+'">rename</button> '+
    '<button data-act="keyDelete" data-key="'+esc(x.api_key)+'" data-app="'+esc(x.app_id)+'">delete</button></td></tr>'});
  el('main').innerHTML=t+'</tbody></table>'}

function openDevice(id){var b=el('drawerBody');b.innerHTML='<div class="empty">Loading...</div>';el('drawer').classList.add('open');
  api('device',{qs:'&install='+encodeURIComponent(id)}).then(function(d){var v=d.device;
    var s='<h2>'+esc(v.device_label)+'</h2><div class="kv">'+
      '<div>app_id</div><div>'+esc(v.app_id)+'</div><div>package</div><div class="mono">'+esc(v.package_name||'-')+'</div>'+
      '<div>install_id</div><div class="mono">'+esc(v.install_id)+'</div>'+
      '<div>device</div><div>'+esc(v.manufacturer)+' '+esc(v.model)+' - SDK '+esc(v.sdk_int)+'</div>'+
      '<div>app / plugin</div><div>'+esc(v.app_version)+' / '+esc(v.plugin_version)+'</div>'+
      '<div>first / last</div><div>'+ago(v.first_seen)+' ago / '+ago(v.last_seen)+' ago</div>'+
      '<div>budget today</div><div>'+kb(v.bytes_today)+' / '+esc(v.budget_kb)+' KB</div></div>';
    s+='<div class="row"><span class="dim">capture level</span> <select data-act="level" data-install="'+esc(v.install_id)+'">'+
      ['none','error','info','verbose'].map(function(l){return '<option'+(l===v.log_level?' selected':'')+'>'+l+'</option>'}).join('')+'</select>'+
      '<button data-act="pull" data-install="'+esc(v.install_id)+'">'+(v.pull_requested?'pull requested':'Request full buffer')+'</button>'+
      (v.killed?'<span class="pill killed">telemetry killed</span>':'')+'</div>';
    s+='<h2>Incidents <span class="dim">('+d.incidents.length+')</span></h2>';
    if(!d.incidents.length)s+='<div class="dim">none - healthy</div>';
    else{s+='<table><thead><tr><th>When</th><th>Type</th><th>Codepoint</th><th>Call</th><th></th></tr></thead><tbody>';
      d.incidents.forEach(function(i){var it=i.issue||{};
        s+='<tr><td>'+ago(i.server_ts)+' ago</td><td>'+esc(i.kind==='pull'?'pull':(it.incident_type||i.kind))+'</td>'+
        '<td class="mono">'+esc(it.codepoint||'')+'</td><td class="mono dim">'+esc(i.call_id||'')+'</td>'+
        '<td><button data-act="payload" data-path="'+esc(encodeURIComponent(i.payload_path))+'">log</button></td></tr>'});
      s+='</tbody></table>'}
    if(d.heartbeats.length)s+='<h2>Latest heartbeat <span class="dim">'+esc(d.heartbeats[0].day)+'</span></h2><pre>'+esc(JSON.stringify(d.heartbeats[0].counters,null,1))+'</pre>';
    b.innerHTML=s})}

function viewPayload(p){var b=el('drawerBody'),back=b.innerHTML;b.innerHTML='<button data-act="back">&larr; back</button><div class="empty">Decompressing...</div>';
  window.__back=back;
  api('payload',{qs:'&path='+p}).then(function(d){
    var o='<button data-act="back">&larr; back</button><h2>Black-box log <span class="dim">('+d.records.length+' records)</span></h2>';
    d.records.forEach(function(r){o+='<div class="rec"><span class="lvl '+esc(r.level)+'">'+esc(r.level||'')+'</span> '+
      '<span class="dim mono">'+esc(r.tag||'')+'</span> '+esc(r.event||r._raw||'')+
      (r.data?' <span class="dim mono">'+esc(JSON.stringify(r.data))+'</span>':'')+(r.stack?'<pre>'+esc(r.stack)+'</pre>':'')+'</div>'});
    b.innerHTML=o})}

function toast(m){var t=document.createElement('div');t.textContent=m;
  t.style.cssText='position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#2f81f7;color:#fff;padding:8px 16px;border-radius:6px;z-index:60';
  document.body.appendChild(t);setTimeout(function(){t.remove()},1800)}

document.addEventListener('click',function(e){var t=e.target.closest('[data-act]');if(!t)return;var act=t.getAttribute('data-act');
  if(act==='tab'){TAB=t.getAttribute('data-tab');document.querySelectorAll('.tabs button').forEach(function(b){b.className=b===t?'on':''});reload()}
  else if(act==='refresh')reload();
  else if(act==='logout')logout();
  else if(act==='device')openDevice(t.getAttribute('data-install'));
  else if(act==='close')el('drawer').classList.remove('open');
  else if(act==='payload'){e.stopPropagation();viewPayload(t.getAttribute('data-path'))}
  else if(act==='back')el('drawerBody').innerHTML=window.__back;
  else if(act==='pull'){var id=t.getAttribute('data-install');
    api('knob',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({install:id,pull_requested:true})}).then(function(){toast('full-buffer pull requested');openDevice(id)})}
  else if(act==='keyCopy'){navigator.clipboard.writeText(t.getAttribute('data-key')).then(function(){toast('key copied')})}
  else if(act==='keyCreate'){var na=el('nkApp').value.trim();if(!na){toast('enter an app name');return}
    api('keyCreate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({app_id:na,label:el('nkLabel').value.trim()})}).then(function(){toast('key created');reload()})}
  else if(act==='keyActive'){api('keyActive',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({api_key:t.getAttribute('data-key'),active:t.getAttribute('data-next')==='1'})}).then(reload)}
  else if(act==='keyRename'){var nn=prompt('New app name:',t.getAttribute('data-app'));if(nn==null)return;nn=nn.trim();if(!nn)return;
    api('keyRename',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({api_key:t.getAttribute('data-key'),app_id:nn,label:t.getAttribute('data-label')})}).then(function(){toast('renamed');reload()})}
  else if(act==='keyDelete'){if(!confirm('Delete the key for "'+t.getAttribute('data-app')+'"? Devices already registered keep working; new installs of that app can no longer register.'))return;
    api('keyDelete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({api_key:t.getAttribute('data-key')})}).then(function(){toast('deleted');reload()})}});
document.addEventListener('change',function(e){var t=e.target.closest('[data-act="level"]');if(!t)return;
  api('knob',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({install:t.getAttribute('data-install'),log_level:t.value})}).then(function(){toast('level -> '+t.value)})});
var si;el('search').addEventListener('input',function(){clearTimeout(si);si=setTimeout(reload,250)});
el('appFilter').addEventListener('change',reload);

var saved=sessionStorage.getItem('dk');
if(saved){KEY=saved;api('summary').then(boot).catch(function(){sessionStorage.removeItem('dk')})}
</script></body></html>`;
