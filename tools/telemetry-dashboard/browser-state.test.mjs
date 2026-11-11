import assert from "node:assert/strict";
import { test } from "node:test";
import { createContext, Script } from "node:vm";
import dashboard from "./netlify/functions/dashboard.mjs";

async function client() {
  const elements = new Map(), pending = new Map(), listeners = {};
  const el = (id) => {
    if (!elements.has(id)) elements.set(id, { value: "", innerHTML: "", textContent: "", style: {}, classList: { add() {}, remove() {} }, addEventListener() {} });
    return elements.get(id);
  };
  const context = createContext({ document: { getElementById: el, addEventListener: (kind, fn) => { listeners[kind] = fn; } },
    sessionStorage: { getItem() { return null; } }, setTimeout, clearTimeout,
    fetch: (url) => new Promise(resolve => pending.set(url, resolve)), location: { reload() {} } });
  context.window = context;
  const html = await (await dashboard(new Request("https://example.invalid/"))).text();
  new Script(html.match(/<script>([\s\S]*?)<\/script>/)[1]).runInContext(context);
  const resolve = async (name, data) => {
    const match = [...pending.keys()].find(key => key.includes(name));
    assert.ok(match, "expected pending request " + name);
    pending.get(match)(Response.json(data));
    await new Promise(setImmediate);
  };
  return { context, el, resolve, click: (action) => listeners.click({ target: { closest: () => ({ getAttribute: () => action }) } }) };
}

test("slower device reads cannot replace the most recently opened device", async () => {
  const c = await client();
  c.context.openDevice("first"); c.context.openDevice("second");
  const device = name => ({ device: { device_label: name, install_id: name }, incidents: [], heartbeats: [] });
  await c.resolve("install=second", device("second"));
  await c.resolve("install=first", device("first"));
  assert.match(c.el("drawerBody").innerHTML, /<h2>second<\/h2>/);
});

test("slower payload reads cannot replace a newer captured log", async () => {
  const c = await client();
  c.context.viewPayload("first"); c.context.viewPayload("second");
  await c.resolve("path=second", { records: [{ event: "second" }] });
  await c.resolve("path=first", { records: [{ event: "first" }] });
  assert.equal(c.context.LOG_RECORDS[0].event, "second");
});

test("closing the drawer discards pending reads", async () => {
  const c = await client();
  c.context.openDevice("first");
  c.click("close");
  await c.resolve("install=first", { device: { device_label: "late" }, incidents: [], heartbeats: [] });
  assert.ok(!c.el("drawerBody").innerHTML.includes("<h2>late</h2>"));
});

test("custom timeline dates are clamped to completed days on the overview", async () => {
  const c = await client();
  c.context.TAB = "analytics";
  c.el("rangePreset").value = "custom";
  c.el("fromDate").value = c.el("toDate").value = new Date().toISOString().slice(0, 10);
  c.context.reportControls();
  const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
  assert.equal(c.el("toDate").value, yesterday);
  assert.equal(c.el("fromDate").value, yesterday);
});

test("large log views render a page and exact call filtering resets the view", async () => {
  const c = await client();
  c.context.LOG_RECORDS = Array.from({ length: 1000 }, (_, i) => ({ event: String(i), callId: i < 750 ? "a" : "b" }));
  c.context.renderLogRecords();
  assert.equal((c.el("logRecords").innerHTML.match(/class="rec"/g) || []).length, 500);
  assert.equal(c.el("moreLogs").hidden, false);
  c.el("logCallFilter").value = "b";
  c.context.renderLogRecords();
  assert.equal((c.el("logRecords").innerHTML.match(/class="rec"/g) || []).length, 250);
  assert.equal(c.el("moreLogs").hidden, true);
});

test("device diagnostics distinguish unsupported, pending, partial and empty evidence", async () => {
  const c = await client();
  c.context.openDevice("device-one");
  await c.resolve("install=device-one", { device: { install_id: "device-one", device_label: "Test phone", telemetry_protocol: 1 }, incidents: [], heartbeats: [], requests: [], parts: [] });
  assert.match(c.el("drawerBody").innerHTML, /Unsupported/);
  c.context.openDevice("device-two");
  await c.resolve("install=device-two", { device: { install_id: "device-two", device_label: "New phone", telemetry_protocol: 2, capabilities: ["diagnostic_session"], diagnostics_enabled: true }, incidents: [], heartbeats: [], requests: [
    { id: "r1", kind: "diagnostic_session", state: "offered", record_count: 0, call_count: 0 },
    { id: "r2", kind: "diagnostic_session", state: "collecting", record_count: 3, call_count: 1 },
    { id: "r3", kind: "diagnostic_session", state: "received_empty", record_count: 0, call_count: 0 },
  ], parts: [] });
  const html = c.el("drawerBody").innerHTML;
  assert.match(html, /offered/); assert.match(html, /awaiting device acknowledgement/);
  assert.match(html, /partial/); assert.match(html, /received_empty/);
  assert.match(html, /Physical sound remains unknown/);
  assert.doesNotMatch(html, /remote diagnostics verified|ringtone healthy/i);
});


test("historical report identity is separate from the currently installed build and bounded history is disclosed", async () => {
  const c = await client(); c.context.openDevice("device-one");
  await c.resolve("install=device-one", { device: { install_id: "device-one", sdk_build_id: "build-B", last_control_at: "2026-11-11T12:00:00Z" }, incidents: [], heartbeats: [],
    requestsTruncated: true, partsTruncated: true, requests: [{id:"req",state:"received",kind:"full_buffer"}],
    parts: [{request_id:"req",report_id:"rep",part:1,parts_total:1,payload_path:"diagnostics/test.gz",identity:{sdk_build_id:"build-A",host_version_code:10},metadata:{}}] });
  const html = c.el("drawerBody").innerHTML;
  assert.match(html, /build-B/); assert.match(html, /Captured build[^]*build-A/);
  assert.match(html, /history is limited/); assert.match(html, /part list is incomplete/);
  assert.match(html, /Last control contact/);
});

test("all diagnostic terminal states and operator observations remain escaped and explicit", async () => {
  const c = await client(); c.context.openDevice("device-one");
  await c.resolve("install=device-one", {device:{install_id:"device-one"},incidents:[],heartbeats:[],parts:[],requests:
    ["queued","acknowledged","failed","cancelled","expired","received"].map((state,i)=>({id:String(i),state,kind:"full_buffer",tester_observation:{text:"<script>danger()</script>"}}))});
  const html=c.el("drawerBody").innerHTML;
  for (const state of ["queued","acknowledged","failed","cancelled","expired","received"]) assert.ok(html.includes(state));
  assert.ok(!html.includes("<script>danger")); assert.ok(html.includes("&lt;script&gt;"));
});

import { callNarratives } from "./netlify/lib/diagnostics-ui.mjs";
test("call narratives preserve call/boot boundaries, monotonic ordering and explicit wall correction", () => {
 const groups=callNarratives([{callId:"a",boot:2,elapsed:20,wall:200,event:"Playing",data:{audible:true}},{callId:"b",boot:2,elapsed:1,wall:201},{callId:"a",boot:1,elapsed:90,wall:100},{callId:"a",boot:2,elapsed:10,wall:199,event:"focus"}],1000);
 assert.equal(groups.length,3); assert.deepEqual(groups[0].records.map(r=>r.elapsed),[10,20]);
 assert.equal(groups[0].records[1].correctedWall,1200); assert.equal(groups[0].physicalSound,"unknown"); assert.ok(groups[0].records[1].engineStartOnly);
});
