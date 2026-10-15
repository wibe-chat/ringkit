// ringkit fleet telemetry — single check-in endpoint.
//
// POST /functions/v1/checkin   (Content-Encoding: gzip supported)
//   Authorization: Bearer <per-app fleet api key>   for kind=register
//   Authorization: Bearer <device_token>            for every other kind
//
// Multi-app: each app registers with its OWN fleet key (fleet_keys.api_key →
// app_id). The device is tagged with that app_id (authoritative — a client
// can't spoof its app), so one endpoint serves many apps with server-side
// separation; the client-reported package_name is stored alongside as a
// self-report cross-check.
//
// Request/response contracts are documented in docs/TELEMETRY.md. The function
// is a thin transport shell: all counter bookkeeping is atomic in Postgres
// (ringkit_* SECURITY DEFINER functions, service-role only). Failure semantics:
// 401 bad token · 413 over daily budget (pulls exempt) · client backs off on
// 429/5xx and retries later — payload files stay on the device, so nothing is
// ever lost by a failed upload. Incident retries are idempotent per
// (install_id, fingerprint, device_ts).

import { createClient } from "jsr:@supabase/supabase-js@2";

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

const MAX_BODY_BYTES = 3 * 1024 * 1024; // pulls are chunked at 2 MB client-side

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

async function readBody(req: Request): Promise<{ raw: Uint8Array; parsed: unknown }> {
  let stream = req.body;
  if (stream && /gzip/i.test(req.headers.get("content-encoding") ?? "")) {
    stream = stream.pipeThrough(new DecompressionStream("gzip"));
  }
  const chunks: Uint8Array[] = [];
  let size = 0;
  if (stream) {
    const reader = stream.getReader();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > MAX_BODY_BYTES) {
          await reader.cancel().catch(() => {});
          throw new RangeError("body too large");
        }
        chunks.push(value);
      }
    } finally {
      reader.releaseLock();
    }
  }
  const raw = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    raw.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return { raw, parsed: JSON.parse(new TextDecoder().decode(raw)) };
}

function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function uploadPayload(path: string, b64: string): Promise<void> {
  const bytes = b64ToBytes(b64);
  const { error } = await supabase.storage
    .from("ringkit-logs")
    .upload(path, bytes, { contentType: "application/gzip", upsert: true });
  if (error) throw new Error(`storage: ${error.message}`);
}

function knobs(row: Record<string, unknown>, extra: Record<string, unknown> = {}) {
  return {
    log_level: row.log_level ?? "info",
    pull_buffer: row.pull_buffer ?? false,
    sampled: row.sampled ?? false,
    budget_kb: row.budget_kb ?? 200,
    kill: row.kill ?? false,
    ...extra,
  };
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json(405, { error: "POST only" });
  const token = req.headers.get("authorization")?.match(/^Bearer\s+(\S+)$/i)?.[1];
  if (!token) return json(401, { error: "unauthorized" });

  let raw: Uint8Array;
  // deno-lint-ignore no-explicit-any
  let body: any;
  try {
    ({ raw, parsed: body } = await readBody(req));
  } catch (e) {
    return json(e instanceof RangeError ? 413 : 400, { error: "bad body" });
  }
  const kind: string = body?.kind ?? "";
  const installId: string = body?.install_id ?? "";
  if (!installId || !kind) return json(400, { error: "missing fields" });

  try {
    // ── register: per-app fleet key auth (fleet_keys.api_key → app_id) ────
    if (kind === "register") {
      const { data: key, error } = await supabase
        .from("fleet_keys").select("app_id, active").eq("api_key", token).maybeSingle();
      if (error || !key || key.active !== true) return json(401, { error: "unauthorized" });
      const { data, error: rpcErr } = await supabase.rpc("ringkit_register", {
        p_install: installId,
        p_label: body.device?.label ?? null,
        p_manufacturer: body.device?.manufacturer ?? null,
        p_model: body.device?.model ?? null,
        p_sdk: body.device?.sdk_int ?? null,
        p_app_version: body.device?.app_version ?? null,
        p_plugin_version: body.device?.plugin_version ?? null,
        p_app_id: key.app_id, // authoritative: the key determines the app
        p_package_name: body.device?.package_name ?? null, // client self-report
      });
      if (rpcErr?.code === "42501") return json(401, { error: "unauthorized" });
      if (rpcErr) throw new Error(rpcErr.message);
      return json(200, data);
    }

    // ── all other kinds: device-token auth + budget gate ─────────────────
    const { data: gate, error: gateErr } = await supabase.rpc("ringkit_checkin_gate", {
      p_install: installId,
      p_token: token,
      p_bytes: raw.byteLength,
      p_exempt: kind === "pull", // pulls are operator-requested; never budget-blocked
    });
    if (gateErr) throw new Error(gateErr.message);
    if (gate.status === "unauthorized") return json(401, { error: "unauthorized" });
    if (gate.status === "over_budget") return json(413, knobs(gate, { error: "over budget" }));

    const clockOffsetMs = typeof body.device_wall_ms === "number"
      ? Date.now() - body.device_wall_ms
      : null;

    if (kind === "heartbeat") {
      // Fold the explicit dropped_logs field into the counters map so the
      // sink-drop signal survives into fleet_daily (contract carries both).
      const counters = { ...(body.heartbeat?.counters ?? {}) };
      if (typeof body.heartbeat?.dropped_logs === "number" && body.heartbeat.dropped_logs > 0) {
        counters.droppedLogs = (counters.droppedLogs ?? 0) + body.heartbeat.dropped_logs;
      }
      const { error } = await supabase.rpc("ringkit_record_heartbeat", {
        p_install: installId,
        p_day: body.heartbeat?.day ?? new Date().toISOString().slice(0, 10),
        p_counters: counters,
        p_fingerprints: body.heartbeat?.fingerprints ?? {},
      });
      if (error) throw new Error(error.message);
      return json(200, knobs(gate));
    }

    if (kind === "incident" || kind === "user_report") {
      const inc = body.incident;
      if (!inc?.fingerprint || !inc?.records_gzip_b64) return json(400, { error: "missing incident" });
      const path = `incidents/${installId}/${Date.now()}-${inc.fingerprint}.jsonl.gz`;
      const deviceTs = typeof inc.device_ts_ms === "number"
        ? new Date(inc.device_ts_ms).toISOString()
        : null;
      // Upload FIRST: if bookkeeping-then-upload ordered the other way, a failed
      // upload after successful bookkeeping would make every retry a "duplicate"
      // and the payload would never land. Upload-first converges: a failed
      // bookkeeping retry re-uploads under a new path and the orphan object is
      // TTL-purged; a true duplicate retry removes its redundant object below.
      await uploadPayload(path, inc.records_gzip_b64);
      const { data: fresh, error } = await supabase.rpc("ringkit_record_incident", {
        p_install: installId,
        p_fingerprint: inc.fingerprint,
        p_type: inc.incident_type ?? "unknown",
        p_codepoint: inc.codepoint ?? "unknown",
        p_degradations: inc.degradations ?? [],
        p_call_id: inc.call_id ?? null,
        p_device_ts: deviceTs,
        p_kind: kind,
        p_payload_path: path,
        p_clock_offset_ms: clockOffsetMs,
      });
      if (error) throw new Error(error.message);
      if (!fresh) {
        // Idempotent retry: the payload already exists under the original path.
        await supabase.storage.from("ringkit-logs").remove([path]);
      }
      return json(200, knobs(gate, { duplicate: !fresh }));
    }

    if (kind === "pull") {
      const pull = body.pull;
      if (!pull?.records_gzip_b64) return json(400, { error: "missing pull" });
      const part = pull.part ?? 1;
      const of = pull.of ?? 1;
      const path = `pulls/${installId}/${Date.now()}.p${part}of${of}.jsonl.gz`;
      await uploadPayload(path, pull.records_gzip_b64);
      const { error } = await supabase.rpc("ringkit_record_incident", {
        p_install: installId,
        p_fingerprint: null,
        p_type: "pull",
        p_codepoint: "pull",
        p_degradations: [],
        p_call_id: null,
        p_device_ts: null,
        p_kind: "pull",
        p_payload_path: path,
        p_clock_offset_ms: clockOffsetMs,
      });
      if (error) throw new Error(error.message);
      const { error: landErr } = await supabase.rpc("ringkit_pull_landed", {
        p_install: installId, p_part: part, p_of: of,
      });
      if (landErr) throw new Error(landErr.message);
      return json(200, knobs(gate, { pull_buffer: part < of }));
    }

    return json(400, { error: `unknown kind: ${kind}` }); // unknown KINDS are errors; unknown FIELDS are ignored (fwd compat)
  } catch (e) {
    console.error("checkin error:", e instanceof Error ? e.message : e);
    return json(500, { error: "internal" });
  }
});
