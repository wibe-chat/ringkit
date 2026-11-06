// Read-only reporting over the existing telemetry tables. No client SDK or keys.
const DAY = 86400000;
export const MAX_ROWS = 20000;
export class InputError extends Error {}
export function orderRecords(rows) {
  const time = (n) => Number.isFinite(n) && n >= 0 ? n : Infinity;
  return rows.map((row, index) => ({ row, index })).sort((a, b) =>
    (time(a.row.boot) - time(b.row.boot) || time(a.row.elapsed) - time(b.row.elapsed) || a.index - b.index)
  ).map(({ row }) => row);
}
const fail = (message) => { throw new InputError(message); };
const date = (ms) => new Date(ms).toISOString().slice(0, 10);
const number = (value) => Number.isFinite(Number(value)) && Number(value) >= 0 ? Number(value) : 0;

function dayMs(value) {
  const ms = Date.parse(value + "T00:00:00Z");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(ms) || date(ms) !== value) fail("Use valid YYYY-MM-DD dates");
  return ms;
}

export function dateWindow(params, completed = true, now = Date.now()) {
  const days = Number(params.get("days") || 30);
  if (![7, 30, 60].includes(days)) fail("Choose 7, 30 or 60 days");
  const today = dayMs(date(now));
  const to = params.get("to") || date(today - (completed ? DAY : 0));
  const end = dayMs(to);
  const from = params.get("from") || date(end - (days - 1) * DAY);
  const start = dayMs(from);
  if (start > end || end - start >= 60 * DAY || end > today - (completed ? DAY : 0)) fail("Choose up to 60 days within the available date range");
  return { from, to, start, end, days: Math.round((end - start) / DAY) + 1 };
}

function offsetOf(params) {
  const raw = params.get("offset") || "0";
  if (!/^\d+$/.test(raw) || !Number.isSafeInteger(Number(raw))) fail("Invalid page offset");
  return Number(raw);
}

function query(table, select, filters) {
  return table + "?" + new URLSearchParams([["select", select], ...filters]).toString();
}

export async function readAll(page, path, maximum = MAX_ROWS) {
  const rows = [];
  let total = 0;
  do {
    const result = await page(path, rows.length, Math.min(1000, maximum - rows.length));
    total = result.total;
    if (!result.rows.length && rows.length < total) throw new Error("empty page before total");
    rows.push(...result.rows);
  } while (rows.length < total && rows.length < maximum);
  return { rows, total, truncated: rows.length < total };
}

function counters(rows) {
  const result = Object.create(null);
  for (const row of rows) for (const [key, value] of Object.entries(row.counters || {})) {
    result[key] = (result[key] || 0) + number(value);
  }
  return result;
}

function metrics(values) {
  const sum = (prefix) => Object.entries(values).filter(([k]) => k.startsWith(prefix)).reduce((n, [, v]) => n + v, 0);
  const shows = values.shows || 0, failures = sum("fail."), attempts = shows + failures;
  return { shows, accepts: values.accepts || 0, declines: values.declines || 0,
    timeouts: values.timeouts || 0, cancels: values.cancels || 0, ends: values.ends || 0,
    expired: values.expired || 0, droppedLogs: values.droppedLogs || 0,
    failures, degradations: sum("deg."), attempts, failureRate: attempts ? failures / attempts : null };
}

export function summarizeFleet(rows, window) {
  const byDay = new Map(), byModel = new Map();
  for (const row of rows) {
    if (row.day < window.from || row.day > window.to) continue;
    if (!byDay.has(row.day)) byDay.set(row.day, []);
    byDay.get(row.day).push(row);
    const key = JSON.stringify([row.manufacturer, row.model, row.sdk_int]);
    if (!byModel.has(key)) byModel.set(key, []);
    byModel.get(key).push(row);
  }
  const deviceDays = (r) => r.reduce((n, v) => n + number(v.devices), 0);
  const daily = [];
  for (let t = window.start; t <= window.end; t += DAY) {
    const day = date(t), r = byDay.get(day) || [], values = counters(r);
    daily.push({ day, reported: r.length > 0, reportingDevices: deviceDays(r), counters: values, ...metrics(values) });
  }
  const heatmap = [...byModel.values()].map((r) => {
    const values = counters(r), first = r[0];
    return { manufacturer: first.manufacturer || "Unknown", model: first.model || "Unknown", sdk: first.sdk_int || "Unknown",
      reportingDeviceDays: deviceDays(r), counters: values, ...metrics(values) };
  }).sort((a, b) => b.failures - a.failures || b.degradations - a.degradations || b.shows - a.shows);
  const values = counters([...byDay.values()].flat());
  return { daily, heatmap, totals: { ...metrics(values), counters: values, reportingDeviceDays: deviceDays([...byDay.values()].flat()) } };
}

export async function report(api, url, page, now = Date.now()) {
  const p = url.searchParams, app = p.get("app") || "";
  if (app.length > 200) fail("App filter is too long");
  if (api === "analytics") {
    const window = dateWindow(p, true, now);
    const filters = [["day", "gte." + window.from], ["day", "lte." + window.to],
      ["order", "day.asc,app_id.asc,manufacturer.asc,model.asc,sdk_int.asc"]];
    if (app) filters.push(["app_id", "eq." + app]);
    const result = await readAll(page, query("fleet_daily", "day,app_id,manufacturer,model,sdk_int,devices,counters", filters));
    return { from: window.from, to: window.to, app, generatedAt: new Date(now).toISOString(),
      ...summarizeFleet(result.rows, window), totalRows: result.total, loadedRows: result.rows.length, truncated: result.truncated };
  }
  if (api === "silent" || api === "devices") {
    const silent = api === "silent";
    const hours = Number(p.get("hours") || 48);
    if (![24, 48, 72, 168].includes(hours)) fail("Choose a 24, 48, 72 or 168 hour threshold");
    const cutoff = new Date(now - hours * 3600000).toISOString();
    const filters = [["order", (silent ? "last_seen.asc" : "last_seen.desc") + ",install_id.asc"]];
    if (silent) filters.push(["last_seen", "lt." + cutoff]);
    if (app) filters.push(["app_id", "eq." + app]);
    if (p.get("q")) filters.push(["device_label", "ilike.*" + p.get("q") + "*"]);
    const offset = offsetOf(p);
    const result = await page(query("devices", "install_id,app_id,package_name,device_label,manufacturer,model,sdk_int,app_version,plugin_version,last_seen,log_level,sampled,bytes_today,killed,pull_requested", filters), offset, 200);
    return { devices: result.rows, total: result.total, hours, cutoff, offset,
      nextOffset: offset + result.rows.length < result.total ? offset + result.rows.length : null };
  }
  if (api === "timeline") {
    const window = dateWindow(p, false, now);
    const filters = [["server_ts", "gte." + window.from + "T00:00:00Z"],
      ["server_ts", "lt." + date(window.end + DAY) + "T00:00:00Z"], ["order", "server_ts.desc,id.desc"]];
    if (app) filters.push(["devices.app_id", "eq." + app]);
    if (p.get("call")) {
      if (p.get("call").length > 128) fail("Call ID is too long");
      filters.push(["call_id", "eq." + p.get("call")]);
    }
    if (p.get("install")) {
      if (!/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(p.get("install"))) fail("Use a valid install UUID");
      filters.push(["install_id", "eq." + p.get("install")]);
    }
    const offset = offsetOf(p);
    const result = await page(query("incidents", "id,install_id,kind,fingerprint,call_id,device_ts,server_ts,clock_offset_ms,payload_path,devices!inner(app_id,device_label,manufacturer,model,sdk_int),issues(incident_type,codepoint,degradations)", filters), offset, 200);
    const incidents = result.rows.map((row) => {
      const wall = Date.parse(row.device_ts), offset = row.clock_offset_ms;
      const corrected = wall + Number(offset);
      return { ...row, corrected_device_ts: offset != null && Number.isFinite(corrected) && Math.abs(corrected) <= 8640000000000000 ? new Date(corrected).toISOString() : null };
    });
    return { incidents, from: window.from, to: window.to, total: result.total, offset,
      nextOffset: offset + incidents.length < result.total ? offset + incidents.length : null };
  }
  if (api === "issues") {
    if (!app) {
      const result = await page(query("issues", "fingerprint,incident_type,codepoint,degradations,device_count,occurrence_count,status,first_seen,last_seen", [["order", "device_count.desc,occurrence_count.desc,fingerprint.asc"]]), 0, 200);
      return { issues: result.rows, total: result.total, truncated: result.rows.length < result.total, scope: "fleet" };
    }
    const result = await readAll(page, query("issue_devices", "fingerprint,install_id,issues!inner(fingerprint,incident_type,codepoint,degradations,status,first_seen,last_seen),devices!inner(app_id)", [["devices.app_id", "eq." + app], ["order", "fingerprint.asc,install_id.asc"]]));
    const groups = new Map();
    for (const row of result.rows) {
      if (!row.issues) continue;
      if (!groups.has(row.fingerprint)) groups.set(row.fingerprint, { ...row.issues, device_count: 0, occurrence_count: null });
      groups.get(row.fingerprint).device_count++;
    }
    const issues = [...groups.values()].sort((a, b) => b.device_count - a.device_count || a.fingerprint.localeCompare(b.fingerprint));
    return { issues: issues.slice(0, 200), total: groups.size, loadedRows: result.rows.length, totalRows: result.total,
      truncated: result.truncated || issues.length > 200, scope: "app" };
  }
  throw new Error("unknown report");
}
