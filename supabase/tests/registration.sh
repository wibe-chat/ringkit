#!/usr/bin/env bash
# Isolated PostgreSQL regression. Requires Docker and postgres:16-alpine.
set -euo pipefail
cd "$(dirname "$0")/../.."
container=$(docker run --rm --detach --network none \
  --tmpfs /var/lib/postgresql/data \
  -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16-alpine)
trap 'docker stop "$container" >/dev/null' EXIT
for attempt in {1..30}; do
  if docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
psql_test() { docker exec -i "$container" psql -U postgres -v ON_ERROR_STOP=1 "$@"; }
psql_test < supabase/tests/registration_fixture.sql
psql_test < supabase/migrations/20260723_m3_multiapp_segregation.sql
# REGISTRATION_BASELINE=1 demonstrates the pre-fix cross-app takeover failure.
if [[ ${REGISTRATION_BASELINE:-0} != 1 ]]; then
  psql_test < supabase/migrations/20261015110641_registration_app_binding.sql
fi
psql_test < supabase/tests/registration_test.sql
echo 'Registration ownership and privilege regressions passed.'
