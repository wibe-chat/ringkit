import assert from "node:assert/strict";
import { calls, reset } from "./supabase_stub.ts";

type Handler = (req: Request) => Promise<Response>;
const handlers: Handler[] = [];
const serve = Deno.serve;
// Capture the actual deployed entrypoints without opening sockets.
Deno.serve = ((handler: Handler) => { handlers.push(handler); }) as typeof Deno.serve;
Deno.env.set("SUPABASE_URL", "https://example.invalid");
Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "synthetic-server-key");
try {
  await import("../functions/purge/index.ts");
  await import("../functions/checkin/index.ts");
} finally {
  Deno.serve = serve;
}
const [purge, checkin] = handlers;

function request(body: BodyInit | null, token = "synthetic-client-key", gzip = false) {
  return new Request("https://example.invalid", {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, ...(gzip ? { "content-encoding": "gzip" } : {}) },
    body,
  });
}

Deno.test("purge rejects client fleet keys and fails closed without its maintenance secret", async () => {
  for (const secret of [undefined, "synthetic-maintenance-secret"]) {
    if (secret) Deno.env.set("RINGKIT_PURGE_SECRET", secret);
    else Deno.env.delete("RINGKIT_PURGE_SECRET");
    reset();
    const response = await purge(request('{"max_age_days":0}'));
    assert.equal(response.status, 401);
    assert.deepEqual(calls, [], "unauthorized callers cannot reach the database or storage");
  }
});

Deno.test("purge accepts only the separately configured maintenance credential", async () => {
  Deno.env.set("RINGKIT_PURGE_SECRET", "synthetic-maintenance-secret");
  reset();
  const response = await purge(request("{}", "synthetic-maintenance-secret"));
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { rows: 0, objects: 0 });
  assert.deepEqual(calls, ["ringkit_purge_rows", "ringkit_expired_log_objects"]);
});

Deno.test("checkin rejects absent authorization before consuming the request body", async () => {
  let pulled = false;
  const stream = new ReadableStream({
    pull(controller) { pulled = true; controller.enqueue(new TextEncoder().encode("{}")); controller.close(); },
  }, { highWaterMark: 0 });
  reset();
  const req = request(stream, "");
  const response = await checkin(req);
  assert.equal(response.status, 401);
  assert.equal(pulled, false);
  assert.deepEqual(calls, []);
  await req.body?.cancel();
});

Deno.test("checkin cancels oversized input before reading the remaining chunks", async () => {
  let pulls = 0;
  let cancelled = false;
  const stream = new ReadableStream({
    pull(controller) {
      pulls++;
      if (pulls <= 10) controller.enqueue(new Uint8Array(1024 * 1024));
      else controller.close();
    },
    cancel() { cancelled = true; },
  }, { highWaterMark: 0 });
  reset();
  const response = await checkin(request(stream));
  assert.equal(response.status, 413);
  assert.equal(cancelled, true);
  assert.ok(pulls <= 4, `read ${pulls} chunks after crossing the limit`);
  assert.deepEqual(calls, []);
});

Deno.test("checkin bounds decompressed gzip and preserves normal JSON/gzip registrations", async () => {
  const encoder = new TextEncoder();
  for (const compressed of [false, true]) {
    for (const oversized of [false, true]) {
      const json = JSON.stringify({ kind: "register", install_id: "00000000-0000-4000-8000-000000000001", padding: oversized ? "x".repeat(3 * 1024 * 1024) : "" });
      let body: BodyInit = encoder.encode(json);
      if (compressed) {
        body = new Response(body).body!.pipeThrough(new CompressionStream("gzip"));
      }
      reset();
      const response = await checkin(request(body, "synthetic-client-key", compressed));
      assert.equal(response.status, oversized ? 413 : 200);
      if (oversized) assert.deepEqual(calls, []);
      else assert.equal((await response.json()).device_token, "synthetic-device-token");
    }
  }
});

Deno.test("cross-app registration denial returns unauthorized without a token", async () => {
  reset({ code: "42501", message: "installation belongs to another app" });
  const originalError = console.error;
  console.error = () => {};
  try {
    const response = await checkin(request(JSON.stringify({ kind: "register", install_id: "00000000-0000-4000-8000-000000000001" })));
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), { error: "unauthorized" });
  } finally {
    console.error = originalError;
  }
});
