import assert from "node:assert/strict";
import { test } from "node:test";
import { gzipSync } from "node:zlib";

process.env.SUPABASE_URL = "https://example.invalid";
process.env.SUPABASE_SERVICE_ROLE_KEY = "synthetic-server-only-key";
const { default: dashboard } = await import("./netlify/functions/dashboard.mjs");
const password = "synthetic-admin-password";
const request = (query, options = {}) => new Request("https://dashboard.invalid/?" + query, {
  headers: { "x-dashboard-key": password }, ...options,
});
const page = (rows, total = rows.length, offset = 0) => Response.json(rows, {
  headers: { "content-range": rows.length ? `${offset}-${offset + rows.length - 1}/${total}` : `*/${total}` },
});
async function withBackend(handler, run) {
  const original = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    const u = new URL(url);
    if (u.pathname.endsWith("/fleet_config")) return Response.json([{ value: password }]);
    assert.equal(options.headers.apikey, process.env.SUPABASE_SERVICE_ROLE_KEY);
    return handler(u, options);
  };
  try { await run(); } finally { globalThis.fetch = original; }
}

test("analytics requires administrator authentication before fetching fleet data", async () => {
  await withBackend(() => assert.fail("unauthenticated data read"), async () => {
    assert.equal((await dashboard(request("api=analytics", { headers: {} }))).status, 401);
  });
});

test("UTC trends sum device-days, distinguish missing days, and do not turn degradations into failed calls", async () => {
  const rows = [
    { day: "2026-10-01", app_id: "one", manufacturer: "Google", model: "Pixel", sdk_int: 34, devices: 2, counters: { shows: 10, accepts: 4, "fail.notificationsBlocked": 2, "deg.fsiDenied": 3, "deg.fgsDenied": 2 } },
    { day: "2026-10-01", app_id: "one", manufacturer: "Samsung", model: "A", sdk_int: 35, devices: 1, counters: { shows: 5, declines: 2, droppedLogs: 1 } },
    { day: "2026-10-03", app_id: "one", manufacturer: "Google", model: "Pixel", sdk_int: 34, devices: 2, counters: { shows: 3, accepts: 1, timeouts: 2 } },
  ];
  const offsets = [];
  await withBackend((u) => {
    assert.equal(u.searchParams.get("app_id"), "eq.one");
    assert.deepEqual(u.searchParams.getAll("day"), ["gte.2026-10-01", "lte.2026-10-03"]);
    const offset = Number(u.searchParams.get("offset")); offsets.push(offset);
    return page(rows.slice(offset, offset + 2), 3, offset); // Backend caps below requested page size.
  }, async () => {
    const r = await dashboard(request("api=analytics&app=one&from=2026-10-01&to=2026-10-03"));
    assert.equal(r.status, 200);
    const d = await r.json();
    assert.deepEqual(offsets, [0, 2]);
    assert.equal(d.totals.shows, 18);
    assert.equal(d.totals.failures, 2);
    assert.equal(d.totals.degradations, 5);
    assert.equal(d.totals.reportingDeviceDays, 5);
    assert.equal(d.daily[0].reportingDevices, 3);
    assert.equal(d.daily[1].reported, false);
    assert.equal(d.daily[1].failureRate, null);
    assert.equal(d.heatmap.find(x => x.manufacturer === "Google").reportingDeviceDays, 4);
    assert.equal(d.truncated, false);
    assert.equal(d.totals.failureRate, 0.1);
  });
});

test("analytics rejects invalid, inverted, future, and oversized date ranges", async () => {
  await withBackend(() => assert.fail("invalid range queried backend"), async () => {
    for (const query of ["from=2026-02-30&to=2026-03-02", "from=2026-10-10&to=2026-10-01", "from=2026-01-01&to=2026-10-01", "days=999", "from=2999-01-01&to=2999-01-02"]) {
      assert.equal((await dashboard(request("api=analytics&" + query))).status, 400, query);
    }
  });
});

test("missing check-ins use last contact, app scope, explicit threshold and exact pagination", async () => {
  await withBackend((u) => {
    assert.equal(u.pathname, "/rest/v1/devices");
    assert.equal(u.searchParams.get("app_id"), "eq.one");
    assert.ok(u.searchParams.get("last_seen").startsWith("lt."));
    assert.ok(!u.searchParams.get("select").includes("device_token"));
    return page([{ install_id: "i", device_label: "offline", last_seen: "2026-01-01", killed: true }], 250);
  }, async () => {
    const r = await dashboard(request("api=silent&app=one&hours=48"));
    assert.equal(r.status, 200);
    const d = await r.json();
    assert.equal(d.total, 250);
    assert.equal(d.nextOffset, 1);
    assert.equal(d.devices[0].killed, true);
    assert.equal(d.hours, 48);
  });
});

test("per-app issues count only matching devices and withhold global occurrence totals", async () => {
  const issue = { fingerprint: "shared", incident_type: "FGS_DENIED", occurrence_count: 900, device_count: 100, degradations: [] };
  await withBackend((u) => {
    assert.equal(u.pathname, "/rest/v1/issue_devices");
    assert.equal(u.searchParams.get("devices.app_id"), "eq.one");
    assert.ok(u.searchParams.get("select").includes("devices!inner"));
    return page([{ install_id: "a", fingerprint: "shared", issues: issue }, { install_id: "b", fingerprint: "shared", issues: issue }]);
  }, async () => {
    const r = await dashboard(request("api=issues&app=one"));
    assert.equal(r.status, 200);
    const d = await r.json();
    assert.equal(d.issues[0].device_count, 2);
    assert.equal(d.issues[0].occurrence_count, null);
    assert.equal(d.scope, "app");
  });
});

test("timeline filters upload metadata, embeds app identity and preserves clock offset", async () => {
  await withBackend((u) => {
    assert.equal(u.pathname, "/rest/v1/incidents");
    assert.equal(u.searchParams.get("call_id"), "eq.call,one");
    assert.equal(u.searchParams.get("devices.app_id"), "eq.app&two");
    assert.ok(!u.searchParams.get("select").includes("device_token"));
    return page([{ id: "1", call_id: "call,one", device_ts: "2026-10-01T00:00:00Z", clock_offset_ms: 2000, devices: { app_id: "app&two" } }]);
  }, async () => {
    const r = await dashboard(request("api=timeline&app=app%26two&call=call%2Cone&from=2026-10-01&to=2026-10-02"));
    assert.equal(r.status, 200);
    const d = await r.json();
    assert.equal(d.incidents[0].corrected_device_ts, "2026-10-01T00:00:02.000Z");
    assert.equal(d.nextOffset, null);
  });
});

test("analytics never silently treats an upstream failure or unknown count as a zero fleet", async () => {
  const originalError = console.error;
  console.error = () => {};
  try {
    for (const response of [new Response("secret upstream", { status: 503 }), Response.json([])]) {
      await withBackend(() => response, async () => {
        const r = await dashboard(request("api=analytics&from=2026-10-01&to=2026-10-02"));
        assert.equal(r.status, 500);
        assert.deepEqual(await r.json(), { error: "internal" });
      });
    }
  } finally { console.error = originalError; }
});

test("new analytics routes reject writes and private responses cannot be cached", async () => {
  await withBackend(() => page([]), async () => {
    assert.equal((await dashboard(request("api=analytics", { method: "POST" }))).status, 405);
    const r = await dashboard(request("api=analytics&from=2026-10-01&to=2026-10-02"));
    assert.equal(r.headers.get("cache-control"), "no-store");
  });
});


test("paginated reports expose caps rather than invent complete totals", async () => {
  const { readAll } = await import("./netlify/lib/analytics.mjs");
  const result = await readAll(async (_path, offset, limit) => ({ rows: Array.from({ length: limit }, (_, i) => offset + i), total: 10 }), "table?select=id", 3);
  assert.deepEqual(result, { rows: [0, 1, 2], total: 10, truncated: true });
});

test("captured logs follow boot and monotonic time across wall-clock jumps", async () => {
  const { orderRecords } = await import("./netlify/lib/analytics.mjs");
  const rows = [{ boot: 2, elapsed: 10, wall: 0, event: "later boot" }, { boot: 1, elapsed: 2, wall: 999, event: "later elapsed" }, { boot: 1, elapsed: 1, wall: 99999, event: "first" }];
  assert.deepEqual(orderRecords(rows).map(r => r.event), ["first", "later elapsed", "later boot"]);
  assert.equal(rows[0].event, "later boot", "source is not mutated");
});

test("payload timelines normalize records and bound decompression", async () => {
  for (const [text, status] of [[JSON.stringify({ boot: 1, elapsed: 2, event: "second" }) + '\nnull\n' + JSON.stringify({ boot: 1, elapsed: 1, event: "first" }), 200], ["a".repeat(6 * 1024 * 1024 + 1), 413]]) {
    await withBackend(() => new Response(gzipSync(text)), async () => {
      const r = await dashboard(request("api=payload&path=incidents/a/sample.jsonl.gz"));
      assert.equal(r.status, status);
      const d = await r.json();
      if (status === 200) assert.deepEqual(d.records.map(r => r.event || r._raw), ["first", "second", "null"]);
      else assert.equal(d.error, "payload too large");
    });
  }
});

test("payload lookup cannot escape the private log bucket with dot segments", async () => {
  await withBackend(() => assert.fail("unsafe path reached Storage"), async () => {
    for (const path of ["incidents/../../other-bucket/secrets.gz", "pulls/a/../secret.gz", "incidents/./file.gz", "incidents//file.gz"]) {
      assert.equal((await dashboard(request("api=payload&path=" + encodeURIComponent(path)))).status, 400);
    }
  });
});

test("tiny-record payloads return an explicitly truncated bounded tail", async () => {
  const text = Array.from({ length: 10005 }, (_, i) => JSON.stringify({ boot: 1, elapsed: i, event: String(i) })).join("\n");
  await withBackend(() => new Response(gzipSync(text)), async () => {
    const d = await (await dashboard(request("api=payload&path=incidents/a/sample.jsonl.gz"))).json();
    assert.equal(d.truncated, true);
    assert.equal(d.records.length, 10000);
    assert.equal(d.records[0].event, "5");
    assert.equal(d.records.at(-1).event, "10004");
  });
});


test("device list and app discovery expose pagination instead of dropping later rows", async () => {
  await withBackend((u) => {
    if(u.pathname.endsWith("/fleet_keys"))return page([{app_id:"later-app"}]);
    if(u.pathname.endsWith("/issues"))return page([],0);
    if(u.searchParams.get("select")==="install_id")return page([{install_id:"first"}],501);
    if(u.searchParams.get("select")==="app_id,install_id")return page([{app_id:"first-app",install_id:"first"}]);
    assert.equal(u.searchParams.get("offset"),"200");
    return page([{install_id:"later",app_id:"later-app"}],501,200);
  }, async () => {
    const s=await (await dashboard(request("api=summary"))).json();
    assert.ok(s.apps.includes("later-app"));
    const r=await dashboard(request("api=devices&offset=200"));
    assert.equal(r.status,200);
    const d=await r.json();assert.equal(d.total,501);assert.equal(d.offset,200);assert.equal(d.nextOffset,201);
  });
});
