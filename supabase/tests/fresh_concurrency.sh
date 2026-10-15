# Sourced by fresh.sh: psql_test connects only to its isolated disposable DB.
# Force the old inversion: heartbeat owns device, incident owns issue then needs
# the FK's device lock; heartbeat then needs issue. Consistent device-first RPCs
# make the incident wait before it can own the issue, so both requests commit.
psql_test <<'SQL'
insert into public.devices(install_id,app_id) values ('00000000-0000-4000-8000-000000000098','test');
insert into public.issues(fingerprint) values('concurrent-incident');
SQL
psql_test >"$scratch/heartbeat-lock.log" 2>&1 <<'SQL' &
set application_name='ringkit-fresh-heartbeat';
set statement_timeout='10s';
begin;
set local role service_role;
select 1 from public.devices where install_id='00000000-0000-4000-8000-000000000098' for update;
select pg_sleep(2);
select public.ringkit_record_heartbeat('00000000-0000-4000-8000-000000000098',current_date,'{}','{"concurrent-incident":1}');
commit;
SQL
heartbeat_pid=$!
locked=false
for attempt in {1..30}; do
  if [[ $(psql_test -Atc "select exists(select from pg_stat_activity where application_name='ringkit-fresh-heartbeat' and wait_event='PgSleep')") == t ]]; then
    locked=true
    break
  fi
  sleep 0.1
done
if [[ "$locked" != true ]]; then
  wait "$heartbeat_pid"
  echo 'ERROR: heartbeat did not acquire its device lock' >&2
  exit 1
fi
psql_test >"$scratch/incident-lock.log" 2>&1 <<'SQL' &
set statement_timeout='10s';
set role service_role;
select public.ringkit_record_incident('00000000-0000-4000-8000-000000000098','concurrent-incident','failure','point','{}',null,'2026-01-01','incident','incidents/concurrent.gz',0);
SQL
incident_pid=$!
heartbeat_status=0
incident_status=0
wait "$heartbeat_pid" || heartbeat_status=$?
wait "$incident_pid" || incident_status=$?
if [[ "$heartbeat_status" != 0 || "$incident_status" != 0 ]]; then
  cat "$scratch/heartbeat-lock.log" "$scratch/incident-lock.log" >&2
  echo 'ERROR: concurrent heartbeat/incident did not both commit' >&2
  exit 1
fi
psql_test <<'SQL'
do $$ begin
  if (select count(*) from public.incidents where fingerprint='concurrent-incident')<>1
    or (select occurrence_count from public.issues where fingerprint='concurrent-incident')<>1
    or (select device_count from public.issues where fingerprint='concurrent-incident')<>1 then
    raise exception 'Concurrent heartbeat/incident aggregation is incorrect';
  end if;
end $$;
SQL
