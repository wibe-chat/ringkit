// ringkit fleet telemetry — administrator dashboard (Netlify Function, Node).
//
// One ZERO-DEPENDENCY function serves the HTML dashboard AND its
// JSON API (so it deploys by drag-and-drop with no build step). It reads every
// telemetry table over Supabase's REST API with the SERVICE ROLE key (the tables
// are deny-all RLS, so only this — or Studio — can see them). Access is for
// trusted fleet administrators only: it spans ALL apps, exposes fleet API keys,
// manages those keys and can raise capture level or request full-buffer pulls.
//
// Device tokens and the service-role key are not returned to the browser.
// Auth: every /?api=... call must carry `x-dashboard-key: <password>` (stored
// server-side in fleet_config key='dashboard_password').
//
// Env (Netlify → Site configuration → Environment variables):
//   SUPABASE_URL                — https://<ref>.supabase.co
//   SUPABASE_SERVICE_ROLE_KEY   — Supabase → Settings → API → service_role (secret)

import { InputError, report, readAll, orderRecords } from "../lib/analytics.mjs";
import { REPORT_STYLE, REPORT_CONTROLS, REPORT_CLIENT } from "../lib/analytics-ui.mjs";

const SB = (process.env.SUPABASE_URL || "").replace(/\/+$/, "");
const SR = process.env.SUPABASE_SERVICE_ROLE_KEY || "";
const AUTH = { apikey: SR, Authorization: "Bearer " + SR };
const LEVELS = ["none", "error", "info", "verbose"];

function json(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
}

// PostgREST query → parsed JSON array (or null). opts: {method, body, headers}.
async function rest(pathAndQuery, opts = {}) {
  const r = await fetch(SB + "/rest/v1/" + pathAndQuery, {
    method: opts.method || "GET",
    headers: Object.assign({}, AUTH, opts.headers || {}),
    body: opts.body,
  });
  if (!r.ok && r.status !== 206) throw new Error("rest " + r.status);
  const text = await r.text();
  return text ? JSON.parse(text) : null;
}

// Exact row count via the Content-Range header (Prefer: count=exact).
async function count(pathAndQuery) {
  const r = await fetch(SB + "/rest/v1/" + pathAndQuery, { headers: Object.assign({ Prefer: "count=exact" }, AUTH) });
  if (!r.ok) throw new Error("count failed");
  const total = r.headers.get("content-range")?.match(/\/(\d+)$/)?.[1];
  if (total === undefined) throw new Error("count unavailable");
  return Number(total);
}

async function restPage(path, offset, limit) {
  const r = await fetch(SB + "/rest/v1/" + path + "&offset=" + offset + "&limit=" + limit, {
    headers: { ...AUTH, Prefer: "count=exact" }, signal: AbortSignal.timeout(15000),
  });
  const total = r.headers.get("content-range")?.match(/\/(\d+)$/)?.[1];
  if ((!r.ok && r.status !== 416) || total === undefined) throw new Error("page unavailable");
  const rows = r.status === 416 ? [] : await r.json();
  if (!Array.isArray(rows) || rows.length > limit) throw new Error("invalid page");
  return { rows, total: Number(total) };
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

class PayloadTooLarge extends Error {}
async function boundedBytes(stream, maximum) {
  const reader = stream.getReader(), parts = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maximum) { await reader.cancel(); throw new PayloadTooLarge(); }
      parts.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) { bytes.set(part, offset); offset += part.length; }
  return bytes;
}
async function gunzipText(body) {
  const bytes = await boundedBytes(body, 4 * 1024 * 1024);
  const stream = new Response(bytes).body.pipeThrough(new DecompressionStream("gzip"));
  return new TextDecoder().decode(await boundedBytes(stream, 6 * 1024 * 1024));
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
  try {
    if (!(await authed(req))) return json(401, { error: "unauthorized" });
    if (["analytics", "silent", "timeline", "issues", "devices"].includes(api)) {
      if (req.method !== "GET") return json(405, { error: "GET required" });
      return json(200, await report(api, url, restPage));
    }
    if (api === "summary") {
      const [devices, openIssues, apps, keys] = await Promise.all([
        count("devices?select=install_id&limit=1"),
        count("issues?select=fingerprint&status=eq.open&limit=1"),
        readAll(restPage, "devices?select=app_id,install_id&order=app_id.asc,install_id.asc"),
        readAll(restPage, "fleet_keys?select=app_id&order=app_id.asc,api_key.asc"),
      ]);
      const appList = [...new Set([...apps.rows, ...keys.rows].map((r) => r.app_id ?? "default"))].sort();
      return json(200, { devices, openIssues, apps: appList, appsTruncated: apps.truncated || keys.truncated });
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

    if (api === "payload") {
      const path = url.searchParams.get("path") ?? "";
      if (!/^(incidents|pulls)\/[A-Za-z0-9\-/.]+\.gz$/.test(path) || path.split("/").some((segment) => !segment || segment === "." || segment === "..")) return json(400, { error: "bad path" });
      const r = await fetch(SB + "/storage/v1/object/ringkit-logs/" + path, { headers: AUTH, signal: AbortSignal.timeout(15000) });
      if (!r.ok) return json(404, { error: "no payload" });
      const text = await gunzipText(r.body);
      // Keep a bounded tail without allocating an array for millions of tiny lines.
      const lines = [], recordLimit = 10000;
      let end = text.length;
      while (end > 0 && lines.length <= recordLimit) {
        const start = text.lastIndexOf("\n", end - 1), line = text.slice(start + 1, end).trim();
        if (line) lines.push(line);
        end = start;
      }
      const records = lines.slice(0, recordLimit).reverse().map((l) => {
        try { const record = JSON.parse(l); return record && typeof record === "object" && !Array.isArray(record) ? record : { _raw: l }; } catch { return { _raw: l }; }
      });
      return json(200, { records: orderRecords(records), truncated: lines.length > recordLimit, recordLimit });
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
  } catch (error) {
    if (error instanceof InputError) return json(400, { error: error.message });
    if (error instanceof PayloadTooLarge) return json(413, { error: "payload too large" });
    // Upstream errors may contain URLs with fleet keys or other credentials.
    console.error("dashboard request failed");
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
  ${REPORT_STYLE}
</style></head><body>

<div class="gate" id="gate"><form id="loginForm">
  <h1>ringkit fleet</h1><p class="dim">Enter the dashboard password</p>
  <input id="pw" type="password" autofocus autocomplete="current-password" placeholder="password">
  <button type="submit">Sign in</button><p class="err" id="loginErr"></p>
</form></div>

<header style="display:none" id="hdr">
  <h1>ringkit fleet <span id="counts"></span></h1>
  <div class="tabs"><button class="on" data-act="tab" data-tab="analytics">Overview</button>
    <button data-act="tab" data-tab="devices">Devices</button>
    <button data-act="tab" data-tab="issues">Issues</button>
    <button data-act="tab" data-tab="timeline">Timeline</button>
    <button data-act="tab" data-tab="silent">Missing check-ins</button>
    <button data-act="tab" data-tab="keys">Keys</button></div>
  <select id="appFilter" aria-label="App filter"></select>
  <input id="search" aria-label="Search device label" placeholder="search label...">
  <div class="grow"></div>
  <button data-act="refresh">Refresh</button>
  <button class="dim" data-act="logout">Sign out</button>
  ${REPORT_CONTROLS}
</header>
<main id="main" style="display:none"><div class="empty">Loading...</div></main>
<div class="drawer" id="drawer"><button class="close" data-act="close">&times;</button><div id="drawerBody"></div></div>

<script>
var KEY=null,TAB='analytics';
function el(id){return document.getElementById(id)}
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function api(name,opts){opts=opts||{};opts.headers=Object.assign({'x-dashboard-key':KEY},opts.headers||{});
  return fetch('?api='+name+(opts.qs||''),opts).then(function(r){if(r.status===401){logout();throw new Error('unauthorized')}return r.json().then(function(d){if(!r.ok)throw new Error(d.error||'Request failed');return d})})}
function ago(ts){if(!ts)return '-';var s=(Date.now()-new Date(ts).getTime())/1000;if(s<60)return Math.round(s)+'s';if(s<3600)return Math.round(s/60)+'m';if(s<86400)return Math.round(s/3600)+'h';return Math.round(s/86400)+'d'}
function kb(b){return Math.round((b||0)/1024*10)/10+' KB'}

el('loginForm').addEventListener('submit',function(e){e.preventDefault();KEY=el('pw').value;
  api('summary').then(function(d){sessionStorage.setItem('dk',KEY);boot(d)})
   .catch(function(){el('loginErr').textContent='Wrong password';KEY=null})});
function logout(){sessionStorage.removeItem('dk');location.reload()}
function boot(d){el('gate').style.display='none';el('hdr').style.display='flex';el('main').style.display='block';
  el('counts').textContent='- fleet: '+d.devices+' devices - '+d.openIssues+' open issues'+(d.appsTruncated?' · app selector is partial':'');
  el('appFilter').innerHTML='<option value="">all apps</option>'+d.apps.map(function(a){return '<option>'+esc(a)+'</option>'}).join('');
  reportControls();reload()}
function qstr(){var a=el('appFilter').value,q=el('search').value,s=[];
  if(a)s.push('app='+encodeURIComponent(a));if(q)s.push('q='+encodeURIComponent(q));return s.length?'&'+s.join('&'):''}
function reload(){var m=el('main'),load=++LOAD_ID;m.innerHTML='<div class="empty">Loading...</div>';
  var renderers={analytics:renderAnalytics,devices:renderDevices,keys:renderKeys,issues:renderIssues,silent:renderSilent,timeline:renderTimeline},renderer=renderers[TAB];
  return api(TAB,{qs:reportQuery()}).then(function(d){if(load===LOAD_ID)renderer(d)}).catch(function(e){if(load===LOAD_ID)m.innerHTML='<div class="empty err" role="alert">'+esc(e.message)+'</div>'})}

function renderDevices(d){var r=d.devices;if(!r.length){el('main').innerHTML='<div class="empty">No devices</div>'+pager(d,0);return}
  var t='<div class="table-scroll"><table><thead><tr><th>App</th><th>Label</th><th>Device</th><th>SDK</th><th>App ver</th><th>Last seen</th><th>Level</th><th>Flags</th><th>Today</th></tr></thead><tbody>';
  r.forEach(function(x){t+='<tr data-act="device" data-install="'+esc(x.install_id)+'">'+
    '<td>'+esc(x.app_id)+'</td><td>'+esc(x.device_label)+'</td><td class="dim">'+esc(x.manufacturer)+' '+esc(x.model)+'</td>'+
    '<td>'+esc(x.sdk_int)+'</td><td>'+esc(x.app_version)+'</td><td>'+ago(x.last_seen)+' ago</td>'+
    '<td><span class="pill '+(x.log_level==='verbose'?'verbose':'')+'">'+esc(x.log_level)+'</span></td>'+
    '<td>'+(x.pull_requested?'<span class="pill pull">pull</span> ':'')+(x.killed?'<span class="pill killed">killed</span>':'')+'</td>'+
    '<td class="dim">'+kb(x.bytes_today)+'</td></tr>'});
  el('main').innerHTML=t+'</tbody></table></div>'+pager(d,r.length)}

function renderIssues(d){var r=d.issues;if(!r.length){el('main').innerHTML='<div class="empty">No issues</div>';return}
  var t=partial(d)+'<p class="notice">'+(d.scope==='app'?'Affected devices belong to the selected app. Occurrences are unavailable per app; first/last issue dates describe the whole fleet.':'Occurrence totals are heartbeat-delta driven and retries may repeat a delta. They are not exactly-once totals.')+'</p><div class="table-scroll"><table><thead><tr><th>Type</th><th>Codepoint</th><th>Degradations</th><th>Devices</th><th>Occurrences</th><th>Status</th><th>Last seen (fleet)</th></tr></thead><tbody>';
  r.forEach(function(x){t+='<tr><td>'+esc(x.incident_type)+'</td><td class="mono">'+esc(x.codepoint)+'</td>'+
    '<td class="dim">'+esc((x.degradations||[]).join(', '))+'</td><td>'+esc(x.device_count)+'</td>'+
    '<td>'+num(x.occurrence_count)+'</td><td>'+esc(x.status)+'</td><td>'+ago(x.last_seen)+' ago</td></tr>'});
  el('main').innerHTML=t+'</tbody></table></div>'}

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

function openDevice(id){var b=el('drawerBody'),load=++DRAWER_ID;DRAWER_OPEN=true;b.innerHTML='<div class="empty">Loading...</div>';el('drawer').classList.add('open');
  api('device',{qs:'&install='+encodeURIComponent(id)}).then(function(d){if(load!==DRAWER_ID)return;var v=d.device;
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
    s+='<button data-act="deviceTimeline" data-install="'+esc(v.install_id)+'">View captured timeline</button>';
    if(!d.incidents.length)s+='<div class="dim">No retained incident uploads</div>';
    else{s+='<table><thead><tr><th>When</th><th>Type</th><th>Codepoint</th><th>Call</th><th></th></tr></thead><tbody>';
      d.incidents.forEach(function(i){var it=i.issue||{};
        s+='<tr><td>'+ago(i.server_ts)+' ago</td><td>'+esc(i.kind==='pull'?'pull':(it.incident_type||i.kind))+'</td>'+
        '<td class="mono">'+esc(it.codepoint||'')+'</td><td class="mono dim">'+esc(i.call_id||'')+'</td>'+
        '<td><button data-act="payload" data-path="'+esc(encodeURIComponent(i.payload_path))+'">log</button></td></tr>'});
      s+='</tbody></table>'}
    if(d.heartbeats.length)s+='<h2>Latest heartbeat <span class="dim">'+esc(d.heartbeats[0].day)+'</span></h2><pre>'+esc(JSON.stringify(d.heartbeats[0].counters,null,1))+'</pre>';
    b.innerHTML=s}).catch(function(e){if(load===DRAWER_ID)b.innerHTML='<div class="empty err">'+esc(e.message)+'</div>'})}

function viewPayload(p){var b=el('drawerBody'),back=DRAWER_OPEN?{html:b.innerHTML,records:LOG_RECORDS}:null,load=++DRAWER_ID;DRAWER_OPEN=true;b.innerHTML='<button data-act="back">&larr; back</button><div class="empty">Decompressing...</div>';
  el('drawer').classList.add('open');
  window.__back=back;
  api('payload',{qs:'&path='+p}).then(function(d){
    if(load!==DRAWER_ID)return;LOG_RECORDS=d.records;LOG_VISIBLE=500;b.innerHTML='<button data-act="back">&larr; back</button><h2>Captured log timeline</h2><p class="notice">Ordered by boot and elapsed time when available. Displayed wall time is the device clock. Each upload contains a bounded slice of available logs.</p>'+(d.truncated?'<p class="warning">This upload exceeds the record limit. Only its latest '+d.recordLimit+' records are available here.</p>':'')+'<label>Call ID <input id="logCallFilter" placeholder="Filter exact call ID"></label><p class="dim" id="logCount"></p><div id="logRecords"></div><button id="moreLogs" data-act="moreLogs" hidden>Show 500 more records</button>';renderLogRecords()
  }).catch(function(e){if(load===DRAWER_ID)b.innerHTML='<button data-act="back">&larr; back</button><div class="empty err">'+esc(e.message)+'</div>'})}

${REPORT_CLIENT}

function toast(m){var t=document.createElement('div');t.textContent=m;
  t.style.cssText='position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#2f81f7;color:#fff;padding:8px 16px;border-radius:6px;z-index:60';
  document.body.appendChild(t);setTimeout(function(){t.remove()},1800)}

document.addEventListener('click',function(e){var t=e.target.closest('[data-act]');if(!t)return;var act=t.getAttribute('data-act');
  if(act==='tab'){closeDrawer();TAB=t.getAttribute('data-tab');resetPage();reportControls();document.querySelectorAll('.tabs button').forEach(function(b){b.className=b===t?'on':''});reload()}
  else if(act==='nextPage'){PREV_OFFSETS.push(PAGE_OFFSET);PAGE_OFFSET=Number(t.getAttribute('data-offset'));reload()}
  else if(act==='previousPage'){PAGE_OFFSET=PREV_OFFSETS.pop()||0;reload()}
  else if(act==='deviceTimeline'){el('installFilter').value=t.getAttribute('data-install');el('callFilter').value='';closeDrawer();document.querySelector('[data-tab="timeline"]').click()}
  else if(act==='refresh')reload();
  else if(act==='logout')logout();
  else if(act==='device')openDevice(t.getAttribute('data-install'));
  else if(act==='close')closeDrawer();
  else if(act==='payload'){e.stopPropagation();viewPayload(t.getAttribute('data-path'))}
  else if(act==='back'){DRAWER_ID++;if(window.__back){el('drawerBody').innerHTML=window.__back.html;LOG_RECORDS=window.__back.records;window.__back=null}else closeDrawer()}
  else if(act==='moreLogs'){LOG_VISIBLE+=500;renderLogRecords()}
  else if(act==='pull'){var id=t.getAttribute('data-install'),drawer= DRAWER_ID;
    api('knob',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({install:id,pull_requested:true})}).then(function(){toast('full-buffer pull requested');if(drawer===DRAWER_ID)openDevice(id)})}
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
var si;el('search').addEventListener('input',function(){clearTimeout(si);si=setTimeout(function(){resetPage();reload()},250)});
el('appFilter').addEventListener('change',function(){resetPage();reload()});

var saved=sessionStorage.getItem('dk');
if(saved){KEY=saved;api('summary').then(boot).catch(function(){sessionStorage.removeItem('dk')})}
</script></body></html>`;
