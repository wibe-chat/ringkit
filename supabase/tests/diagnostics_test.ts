import assert from "node:assert/strict";
import { calls, argumentsSeen, reset, responses, failures } from "./supabase_stub.ts";
type Handler = (req: Request) => Promise<Response>;
let checkin: Handler;
const serve = Deno.serve;
Deno.serve = ((handler: Handler) => { checkin = handler; }) as typeof Deno.serve;
Deno.env.set("SUPABASE_URL", "https://example.invalid");
Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "synthetic-server-key");
try { await import("../functions/checkin/index.ts"); } finally { Deno.serve = serve; }
const install = "00000000-0000-4000-8000-000000000001";
const requestId = "10000000-0000-4000-8000-000000000001";
const reportId = "20000000-0000-4000-8000-000000000001";
function post(body: unknown) { return checkin(new Request("https://example.invalid", { method: "POST", headers: { authorization: "Bearer synthetic-token" }, body: JSON.stringify(body) })); }
async function report(raw = '{"wall":1,"boot":1,"elapsed":1,"callId":"one","event":"policy"}\n') {
 const bytes = await new Response(new Blob([raw]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
 return { v: 2, kind: "diagnostic_report", install_id: install, report: {
  request_id: requestId, report_id: reportId, part: 1, of: 1,
  identity: { sdk_build_id: "unknown" }, records_gzip_b64: btoa(String.fromCharCode(...new Uint8Array(bytes))),
  metadata: { schema: 1, final: true, manifest: [reportId], end_reason: "time_limit", record_count: 999, call_ids: ["spoof"] },
 } };
}
Deno.test("v2 control uses separate control budget and token-bound request RPC", async () => {
 reset(); const body = { v: 2, kind: "control", install_id: install, device: {}, request_id: requestId, action: "acknowledged" };
 const response = await post(body);
 assert.equal(response.status, 200); assert.deepEqual(calls, ["ringkit_control"]);
 const args = argumentsSeen[0].args as Record<string, unknown>;
 assert.equal(args.p_token, "synthetic-token"); assert.equal(args.p_bytes, new TextEncoder().encode(JSON.stringify(body)).length);
});
Deno.test("diagnostic receipt follows parsing and durable storage; counts come from records", async () => {
 reset(); const response = await post(await report());
 assert.equal(response.status, 200); assert.deepEqual(calls, ["ringkit_diagnostic_authorize", "storage.upload", "ringkit_diagnostic_receipt"]);
 const args = argumentsSeen[1].args as Record<string, unknown>;
 assert.equal((args.p_meta as Record<string, unknown>).record_count, 1);
 assert.deepEqual((args.p_meta as Record<string, unknown>).call_ids, ["one"]);
});
Deno.test("malformed nested payloads and decompression bombs never receive receipts", async () => {
 for (const raw of ["not JSON", "x".repeat(5 * 1024 * 1024 + 1)]) {
  reset(); const response = await post(await report(raw));
  assert.equal(response.status, 400); assert.deepEqual(calls, ["ringkit_diagnostic_authorize"]);
 }
});
Deno.test("unnegotiated protocol cannot invoke v2 kinds", async () => {
 reset(); const body = await report(); body.v = 1;
 assert.equal((await post(body)).status, 400); assert.deepEqual(calls, []);
});
Deno.test("cancelled and duplicate receipts remove only the redundant uploaded object", async () => {
 for (const result of [{ status: "cancelled" }, { status: "ok", duplicate: true, payload_path: "old/path" }]) {
  reset(); responses.set("ringkit_diagnostic_receipt", result);
  const response = await post(await report());
  assert.equal(response.status, result.status === "ok" ? 200 : 409);
  assert.equal(calls.at(-1), "storage.remove");
 }
});
Deno.test("ordinary budget exhaustion preserves enrolled control capability", async () => {
 reset();
 responses.set("ringkit_checkin_gate", { status: "over_budget", budget_kb: 1 });
 responses.set("ringkit_identity", { telemetry_protocol: 2, diagnostics_enabled: true });
 const response = await post({ kind: "heartbeat", install_id: install });
 assert.equal(response.status, 413);
 assert.equal((await response.json()).diagnostics_enabled, true);
});
Deno.test("sessions reject a 21st distinct call before storing data", async () => {
 reset();
 responses.set("ringkit_diagnostic_authorize", { status: "ok", kind: "diagnostic_session", max_calls: 20 });
 const raw = Array.from({ length: 21 }, (_, i) => JSON.stringify({ wall: 1, elapsed: 1, boot: 1, callId: `call-${i}` })).join("\n");
 assert.equal((await post(await report(raw))).status, 400);
 assert.deepEqual(calls, ["ringkit_diagnostic_authorize"]);
});

Deno.test("storage or receipt failure never creates a success response", async () => {
 for (const fault of ["storage.upload", "ringkit_diagnostic_receipt"]) {
  reset(); failures.add(fault);
  assert.equal((await post(await report())).status,500);
  if(fault==="storage.upload") assert.ok(!calls.includes("ringkit_diagnostic_receipt"));
  assert.ok(!calls.includes("storage.remove")); // a DB outcome may be uncertain: retention handles orphan cleanup
 }
});
Deno.test("build-changed capture publishes its bounded reason and incomplete coverage", async () => {
 reset(); const body=await report(""); body.report.metadata.end_reason="build_changed";
 await post(body);
 const args=argumentsSeen.at(-1)!.args as Record<string,unknown>;
 assert.equal((args.p_meta as Record<string,unknown>).end_reason,"build_changed");
});
