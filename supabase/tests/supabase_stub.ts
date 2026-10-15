// Offline boundary double: production handlers and request streams stay real.
export const calls: string[] = [];
export let registrationError: { code: string; message: string } | null = null;
export function reset(error: typeof registrationError = null) {
  calls.length = 0;
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
      switch (name) {
        case "ringkit_purge_rows": return { data: 0, error: null };
        case "ringkit_expired_log_objects": return { data: [], error: null };
        case "ringkit_register":
          return { data: { device_token: "synthetic-device-token" }, error: registrationError };
        default: throw new Error(`Unexpected RPC: ${name}`);
      }
    },
    storage: {
      from() {
        return {
          remove() { throw new Error("Unexpected storage deletion"); },
          upload() { throw new Error("Unexpected storage upload"); },
        };
      },
    },
  };
}
