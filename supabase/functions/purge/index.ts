// ringkit fleet telemetry — nightly TTL purge (invoked by pg_cron via pg_net).
//
// POST /functions/v1/purge   Authorization: Bearer <fleet api key>
//   body: { "max_age_days": 60 }   (optional; default 60; 0 = purge everything,
//                                   operator-only, used by maintenance/smoke cleanup)
//
// Deletes expired rows (incidents/heartbeats) via SQL RPC and expired storage
// objects via the Storage API — direct SQL deletes on storage.objects are
// blocked by Supabase's protect_delete guard, hence this function owns the job.

import { createClient } from "jsr:@supabase/supabase-js@2";

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("POST only", { status: 405 });
  const token = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  const { data: cfg } = await supabase.from("fleet_config").select("value").eq("key", "api_key").single();
  if (!cfg || cfg.value !== token) return new Response("unauthorized", { status: 401 });

  let maxAgeDays = 60;
  try {
    const body = await req.json();
    if (typeof body?.max_age_days === "number" && body.max_age_days >= 0) maxAgeDays = body.max_age_days;
  } catch (_) { /* empty body = defaults */ }

  try {
    const { data: rows, error: rowErr } = await supabase.rpc("ringkit_purge_rows", {
      p_max_age_days: maxAgeDays,
    });
    if (rowErr) throw new Error(rowErr.message);

    let objects = 0;
    for (;;) {
      const { data: batch, error } = await supabase.rpc("ringkit_expired_log_objects", {
        p_max_age_days: maxAgeDays, p_limit: 100,
      });
      if (error) throw new Error(error.message);
      if (!batch || batch.length === 0) break;
      const names = batch.map((r: { name: string }) => r.name);
      const { error: rmErr } = await supabase.storage.from("ringkit-logs").remove(names);
      if (rmErr) throw new Error(rmErr.message);
      objects += names.length;
      if (names.length < 100) break;
    }
    return new Response(JSON.stringify({ rows, objects }), {
      headers: { "Content-Type": "application/json" },
    });
  } catch (e) {
    console.error("purge error:", e instanceof Error ? e.message : e);
    return new Response(JSON.stringify({ error: "internal" }), { status: 500 });
  }
});
