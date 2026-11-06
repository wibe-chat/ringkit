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
