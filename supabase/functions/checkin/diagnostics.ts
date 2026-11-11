/** Additive protocol 2: receipt means parsed and durable, never just HTTP 200. */
export const uuid = (value: unknown): value is string => typeof value === "string" && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
export function identity(value: unknown): Record<string, unknown> {
  if (!object(value)) return {};
  const result: Record<string, unknown> = {};
  for (const key of ["plugin_version", "app_version", "package_name"]) {
    if (typeof value[key] === "string") result[key] = (value[key] as string).slice(0, 255);
  }
  if (typeof value.sdk_build_id === "string" && /^(unknown|sha256:[a-f0-9]{64})$/.test(value.sdk_build_id)) result.sdk_build_id = value.sdk_build_id;
  if (value.telemetry_protocol === 2) result.telemetry_protocol = 2;
  if (typeof value.host_debuggable === "boolean") result.host_debuggable = value.host_debuggable;
  if (Number.isSafeInteger(value.host_version_code) && (value.host_version_code as number) >= 0) result.host_version_code = value.host_version_code;
  if (Array.isArray(value.capabilities)) result.capabilities = [...new Set(value.capabilities.filter(x => ["audio_failure", "build_identity", "diagnostic_session", "correlated_pull"].includes(x)))];
  return result;
}
function diagnosticStatus(value: unknown) {
  if (!Array.isArray(value)) return [];
  return value.slice(0, 6).filter(x => object(x) && uuid(x.id)).map(x => {
    const out: Record<string, unknown> = { id: x.id };
    if (["attempting", "acknowledged_part", "network_error", "http_5xx", "budget_deferred", "auth_retry", "rejected", "retry_limit"].includes(x.outcome)) out.outcome = x.outcome;
    if (uuid(x.report_id)) out.report_id = x.report_id;
    for (const key of ["wall_ms", "boot", "elapsed_ms", "part", "pending_parts"]) if (Number.isSafeInteger(x[key])) out[key] = x[key];
    return out;
  });
}
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const statusCode = (status: string) => status === "ok" ? 200 : status === "unauthorized" ? 401 : status === "over_budget" ? 429 : status === "invalid" ? 400 : 409;
async function parsePayload(encoded: unknown) {
  if (typeof encoded !== "string" || encoded.length > 3 * 1024 * 1024) throw new Error("payload");
  const bin = atob(encoded);
  const bytes = Uint8Array.from(bin, c => c.charCodeAt(0));
  const reader = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip")).getReader();
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read(); if (done) break;
      size += value.byteLength;
      if (size > 5 * 1024 * 1024) { await reader.cancel(); throw new Error("payload"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const raw = new Uint8Array(size); let at = 0;
  for (const c of chunks) { raw.set(c, at); at += c.byteLength; }
  const text = new TextDecoder("utf-8", { fatal: true }).decode(raw);
  const records = text.split("\n").filter(x => x.trim()).map(x => {
    const r = JSON.parse(x);
    if (!object(r) || !Number.isFinite(r.wall) || !Number.isFinite(r.elapsed) || !Number.isInteger(r.boot) || (r.callId != null && (typeof r.callId !== "string" || r.callId.length > 128))) throw new Error("record");
    return r;
  });
  const calls = [...new Set(records.map(r => r.callId).filter((x): x is string => typeof x === "string"))];
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))).map(b => b.toString(16).padStart(2, "0")).join("");
  return { bytes, hash, count: records.length, rawBytes: size, calls: calls.slice(0, 20), callsTruncated: calls.length > 20, callCount: calls.length };
}
// SupabaseClient is deliberately structural so the same handler exercises real streams in tests.
// deno-lint-ignore no-explicit-any
export async function diagnosticCheckin(db: any, body: Record<string, any>, token: string, size: number): Promise<Response> {
  if (body.v !== 2 || !uuid(body.install_id)) return json(400, { error: "invalid protocol" });
  if (body.kind === "control") {
    if (body.request_id != null && !uuid(body.request_id)) return json(400, { error: "invalid request" });
    if (body.action != null && !["acknowledged", "collecting", "failed"].includes(body.action)) return json(400, { error: "invalid action" });
    const { data, error } = await db.rpc("ringkit_control", {
      p_install: body.install_id, p_token: token, p_bytes: size, p_identity: identity(body.device),
      p_request: body.request_id ?? null, p_action: body.action ?? null,
      p_status: diagnosticStatus(body.diagnostic_status),
    });
    if (error) throw new Error("control failed");
    return json(statusCode(data.status), data);
  }
  const r = body.report;
  if (!object(r) || !uuid(r.request_id) || !uuid(r.report_id) || !Number.isInteger(r.part) || !Number.isInteger(r.of) ||
    (r.part as number) < 1 || (r.of as number) > 32 || (r.part as number) > (r.of as number) || !object(r.metadata) || r.metadata.schema !== 1) return json(400, { error: "invalid report" });
  const meta = r.metadata;
  if (typeof meta.final !== "boolean" || (meta.final && (!Array.isArray(meta.manifest) || meta.manifest.length < 1 || meta.manifest.length > 21 || !meta.manifest.every(uuid)))) return json(400, { error: "invalid manifest" });
  const { data: auth, error: authError } = await db.rpc("ringkit_diagnostic_authorize", {
    p_install: body.install_id, p_token: token, p_request: r.request_id,
  });
  if (authError) throw new Error("authorization failed");
  if (auth.status !== "ok") return json(statusCode(auth.status), auth);
  let parsed;
  try { parsed = await parsePayload(r.records_gzip_b64); } catch { return json(400, { error: "invalid records" }); }
  if (auth.kind === "diagnostic_session" && parsed.callCount > auth.max_calls) return json(400, { error: "call limit" });
  const path = `diagnostics/${body.install_id}/${r.request_id}/${r.report_id}/${r.part}-${crypto.randomUUID()}.jsonl.gz`;
  const { error: uploadError } = await db.storage.from("ringkit-logs").upload(path, parsed.bytes, { contentType: "application/gzip", upsert: false });
  if (uploadError) throw new Error("storage failed");
  const metadata: Record<string, unknown> = {
    schema: 1, final: meta.final, manifest: meta.final ? meta.manifest : undefined,
    record_count: parsed.count, raw_bytes: parsed.rawBytes, call_ids: parsed.calls, calls_truncated: parsed.callsTruncated,
  };
  for (const key of ["boot", "from_elapsed", "to_elapsed", "from_wall", "to_wall", "dropped_records", "observed_call_count", "clock_offset_ms", "omitted_records", "unparsed_records"]) {
    if (Number.isSafeInteger(meta[key])) metadata[key] = meta[key];
  }
  if (Array.isArray(meta.diagnose)) metadata.diagnose = meta.diagnose.slice(0, 64).filter(x => object(x) && typeof x.id === "string" && /^[A-Za-z0-9_]{1,80}$/.test(x.id) && ["INFO", "WARNING", "ERROR"].includes(x.severity as string)).map(x => ({ id: x.id, severity: x.severity }));
  if (typeof meta.truncated === "boolean") metadata.truncated = meta.truncated;
  if (["time_limit", "call_limit", "byte_limit", "reboot", "cancelled", "disabled", "completed", "process_restart", "build_changed", "empty"].includes(meta.end_reason as string)) metadata.end_reason = meta.end_reason;
  const { data, error } = await db.rpc("ringkit_diagnostic_receipt", {
    p_install: body.install_id, p_token: token, p_request: r.request_id, p_report: r.report_id,
    p_part: r.part, p_of: r.of, p_path: path, p_hash: parsed.hash, p_bytes: size,
    p_meta: metadata, p_identity: identity(r.identity),
  });
  if (error) throw new Error("receipt failed"); // orphan follows normal storage retention
  if (data.status !== "ok" || data.duplicate) await db.storage.from("ringkit-logs").remove([path]);
  return json(statusCode(data.status), data);
}
