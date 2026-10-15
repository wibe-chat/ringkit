import assert from "node:assert/strict";
import { test } from "node:test";

process.env.SUPABASE_URL = "https://example.invalid";
process.env.SUPABASE_SERVICE_ROLE_KEY = "synthetic-server-only-key";
const { default: dashboard } = await import("./netlify/functions/dashboard.mjs");

test("key-management failures never log credentials or upstream messages", async () => {
  const originalFetch = globalThis.fetch;
  const originalError = console.error;
  const password = "synthetic-admin-password";
  const fleetKey = "synthetic-client-key";
  try {
    for (const api of ["keyRename", "keyActive", "keyDelete"]) {
      for (const failure of ["http", "network"]) {
        const logs = [];
        console.error = (...args) => logs.push(args.join(" "));
        globalThis.fetch = async (url) => {
          if (String(url).includes("fleet_config?")) {
            return Response.json([{ value: password }]);
          }
          if (failure === "network") throw new Error(`upstream exposed ${fleetKey}`);
          return new Response("unavailable", { status: 503 });
        };
        const response = await dashboard(new Request(`https://example.invalid/?api=${api}`, {
          method: "POST",
          headers: { "x-dashboard-key": password },
          body: JSON.stringify({ api_key: fleetKey, app_id: "test-app", active: false }),
        }));
        assert.equal(response.status, 500);
        assert.deepEqual(await response.json(), { error: "internal" });
        assert.ok(logs.length > 0, "failure is observable without sensitive context");
        assert.ok(!logs.join("\n").includes(fleetKey), `${api}/${failure} leaked a key`);
        assert.ok(!logs.join("\n").includes(password));
      }
    }
  } finally {
    globalThis.fetch = originalFetch;
    console.error = originalError;
  }
});

test("authentication backend errors return a generic failure", async () => {
  const originalFetch = globalThis.fetch;
  const originalError = console.error;
  const logs = [];
  try {
    console.error = (...args) => logs.push(args.join(" "));
    globalThis.fetch = async () => { throw new Error("synthetic-sensitive-upstream-detail"); };
    const response = await dashboard(new Request("https://example.invalid/?api=keys"));
    assert.equal(response.status, 500);
    assert.deepEqual(await response.json(), { error: "internal" });
    assert.ok(!logs.join("\n").includes("synthetic-sensitive-upstream-detail"));
  } finally {
    globalThis.fetch = originalFetch;
    console.error = originalError;
  }
});

test("unauthenticated clients cannot list fleet keys", async () => {
  const originalFetch = globalThis.fetch;
  let requests = 0;
  try {
    globalThis.fetch = async () => {
      requests++;
      return Response.json([{ value: "synthetic-admin-password" }]);
    };
    const response = await dashboard(new Request("https://example.invalid/?api=keys"));
    assert.equal(response.status, 401);
    assert.equal(requests, 1);
    assert.deepEqual(await response.json(), { error: "unauthorized" });
  } finally {
    globalThis.fetch = originalFetch;
  }
});
