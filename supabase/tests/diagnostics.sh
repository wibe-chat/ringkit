#!/usr/bin/env bash
# Isolated SQL-only fixture. No production URLs or credentials are accepted.
set -euo pipefail
cd "$(dirname "$0")/../.."
scratch=$(mktemp -d "${TMPDIR:-/tmp}/ringkit-diagnostics.XXXXXX")
container=''
cleanup() {
  if [[ -n "$container" ]]; then docker stop "$container" >/dev/null; fi
  if [[ -n "${PG_BIN:-}" && -f "$scratch/data/postmaster.pid" ]]; then "$PG_BIN/pg_ctl" -D "$scratch/data" -m fast stop >/dev/null; fi
}
trap cleanup EXIT
if [[ -n "${PG_BIN:-}" ]]; then
  "$PG_BIN/initdb" -D "$scratch/data" -A trust --no-locale >/dev/null
  "$PG_BIN/pg_ctl" -D "$scratch/data" -l "$scratch/postgres.log" -o "-k $scratch -p 55485 -h ''" start >/dev/null
  psql_test() { "$PG_BIN/psql" -X -h "$scratch" -p 55485 -d postgres -v ON_ERROR_STOP=1 "$@"; }
else
  container=$(docker run --rm --detach --network none --tmpfs /var/lib/postgresql/data -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16-alpine)
  for attempt in {1..30}; do
    if docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
    sleep 1
  done
  psql_test() { docker exec -i "$container" psql -X -U postgres -v ON_ERROR_STOP=1 "$@"; }
fi
psql_test < supabase/tests/fresh_fixture.sql
psql_test < supabase/fresh-project/supabase/migrations/20261015141246_fresh_telemetry.sql
if [[ ${UPGRADE_TOKEN_UUID:-0} == 1 ]]; then
  psql_test <<'SQL'
alter table public.devices alter column device_token drop default;
alter table public.devices alter column device_token type uuid using device_token::uuid;
alter table public.devices alter column device_token set default gen_random_uuid();
SQL
fi
for migration in supabase/migrations/*_remote_diagnostics.sql; do psql_test < "$migration"; done
psql_test < supabase/tests/diagnostics_test.sql
if [[ -f supabase/tests/diagnostics_concurrency.sh ]]; then source supabase/tests/diagnostics_concurrency.sh; fi
printf 'Diagnostic SQL checks passed. Isolated logs: %s\n' "$scratch"
