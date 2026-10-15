#!/usr/bin/env bash
# Real SQL regression, not a hosted Supabase/Storage/Edge Function deployment.
# Default: isolated Docker PG16. Alternative: PG_BIN=/path/to/postgres/bin
# creates its OWN temporary cluster. Never accepts a production database URL.
set -euo pipefail
cd "$(dirname "$0")/../.."
scratch=$(mktemp -d "${TMPDIR:-/tmp}/ringkit-fresh.XXXXXX")
container=''
cleanup() {
  if [[ -n "$container" ]]; then docker stop "$container" >/dev/null; fi
  if [[ -n "${PG_BIN:-}" && -f "$scratch/data/postmaster.pid" ]]; then
    "$PG_BIN/pg_ctl" -D "$scratch/data" -m fast stop >/dev/null
  fi
  # Keep the exact scratch directory for diagnosis; it contains synthetic data.
}
trap cleanup EXIT
if [[ -n "${PG_BIN:-}" ]]; then
  "$PG_BIN/initdb" -D "$scratch/data" -A trust --no-locale >/dev/null
  "$PG_BIN/pg_ctl" -D "$scratch/data" -l "$scratch/postgres.log" \
    -o "-k $scratch -p 55484 -h ''" start >/dev/null
  psql_test() { "$PG_BIN/psql" -X -h "$scratch" -p 55484 -d postgres -v ON_ERROR_STOP=1 "$@"; }
else
  container=$(docker run --rm --detach --network none --tmpfs /var/lib/postgresql/data \
    -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16-alpine)
  for attempt in {1..30}; do
    if docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
    sleep 1
  done
  psql_test() { docker exec -i "$container" psql -X -U postgres -v ON_ERROR_STOP=1 "$@"; }
fi
psql_test < supabase/tests/fresh_fixture.sql
if [[ ${FRESH_BASELINE:-0} != 1 ]]; then
  psql_test < supabase/fresh-project/supabase/migrations/20261015141246_fresh_telemetry.sql
fi
psql_test < supabase/tests/fresh_test.sql
psql_test < supabase/tests/registration_test.sql
source supabase/tests/fresh_concurrency.sh

# Concurrent gate requests must share one row lock, not both admit 700 bytes.
psql_test <<'SQL'
insert into public.devices(install_id,app_id,device_token,budget_kb)
values ('00000000-0000-4000-8000-000000000099','test','synthetic-test-token',1);
SQL
psql_test >"$scratch/gate-a.log" <<'SQL' &
set application_name='ringkit-fresh-gate-a';
begin;
set local role service_role;
select public.ringkit_checkin_gate('00000000-0000-4000-8000-000000000099','synthetic-test-token',700,false);
select pg_sleep(2);
commit;
SQL
first=$!
# Observe the first transaction holding its budget update before the competitor.
locked=false
for attempt in {1..30}; do
  if [[ $(psql_test -Atc "select exists(select from pg_stat_activity where application_name='ringkit-fresh-gate-a' and wait_event='PgSleep')") == t ]]; then
    locked=true
    break
  fi
  sleep 0.1
done
if [[ "$locked" != true ]]; then
  wait "$first"
  echo 'ERROR: did not observe the first budget transaction holding its lock' >&2
  exit 1
fi
psql_test >"$scratch/gate-b.log" <<'SQL' &
set role service_role;
select public.ringkit_checkin_gate('00000000-0000-4000-8000-000000000099','synthetic-test-token',700,false);
SQL
second=$!
wait "$first"
wait "$second"
psql_test <<'SQL'
do $$ begin
  if (select bytes_today from public.devices where install_id='00000000-0000-4000-8000-000000000099')<>700 then
    raise exception 'Concurrent gate overspent';
  end if;
end $$;
SQL
if psql_test < supabase/fresh-project/supabase/migrations/20261015141246_fresh_telemetry.sql >"$scratch/collision.log" 2>&1; then
  echo 'ERROR: fresh baseline accepted existing RingKit objects' >&2
  exit 1
fi
psql_test <<'SQL'
do $$ begin
  if (select bytes_today from public.devices where install_id='00000000-0000-4000-8000-000000000099')<>700 then
    raise exception 'Collision modified existing data';
  end if;
end $$;
SQL
echo "Fresh schema, ownership, ACLs, budget concurrency and collision regressions passed. SQL-only logs: $scratch"
