// Offline boundary double: production handlers and request streams stay real.
export const calls: string[] = [];
export const argumentsSeen: Record<string, unknown>[] = [];
export const responses = new Map<string, unknown>();
export const failures = new Set<string>();
export let registrationError: { code: string; message: string } | null = null;
export function reset(error: typeof registrationError = null) {
  calls.length = 0;
  argumentsSeen.length = 0;
  responses.clear();
  failures.clear();
  registrationError = error;
}

// This double covers only exercised SDK operations; production types are checked
// separately against the real SDK with `deno check supabase/functions/*/index.ts`.
// deno-lint-ignore no-explicit-any
export function createClient(_url: string, _key: string): any {
  return {
    from(table: string) {
      calls.push(table);
      return {
        select() {
          return {
            eq() {
              return {
                single: () => ({ data: { value: "synthetic-client-key" }, error: null }),
                maybeSingle: () => ({ data: { app_id: "app-a", active: true }, error: null }),
              };
            },
          };
        },
      };
    },
    rpc(name: string, _args: unknown) {
      calls.push(name);
      argumentsSeen.push({ name, args: _args });
      if (failures.has(name)) return { data: null, error: { message: "synthetic failure" } };
      if (responses.has(name)) return { data: responses.get(name), error: null };
      switch (name) {
        case "ringkit_purge_rows": return { data: 0, error: null };
        case "ringkit_expired_log_objects": return { data: [], error: null };
        case "ringkit_register":
          return { data: { device_token: "synthetic-device-token" }, error: registrationError };
        case "ringkit_identity": return { data: { telemetry_protocol: 2, diagnostics_enabled: false }, error: null };
        case "ringkit_diagnostic_authorize": return { data: { status: "ok" }, error: null };
        case "ringkit_control": return { data: { status: "ok", telemetry_protocol: 2, requests: [] }, error: null };
        case "ringkit_diagnostic_receipt": return { data: { status: "ok", state: "received", duplicate: false }, error: null };
        default: throw new Error(`Unexpected RPC: ${name}`);
      }
    },
    storage: {
      from() {
        return {
          remove() { calls.push("storage.remove"); return { error: null }; },
          upload() { calls.push("storage.upload"); return { error: failures.has("storage.upload") ? { message: "synthetic failure" } : null }; },
        };
      },
    },
  };
}
