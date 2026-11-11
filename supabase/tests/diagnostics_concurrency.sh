psql_test <<'SQL'
update public.devices set telemetry_protocol=2,capabilities=array['diagnostic_session'] where install_id='00000000-0000-4000-8000-000000000001';
SQL
# Device-row serialization must preserve cancellation and one active request.
psql_test >"$scratch/request-a.log" <<'SQL' &
set application_name='ringkit-diagnostics-a';
begin;
set local role service_role;
select public.ringkit_diagnostic_request('00000000-0000-4000-8000-000000000001','diagnostic_session');
select pg_sleep(1);
commit;
SQL
first=$!
locked=false
for attempt in {1..40}; do
  if [[ $(psql_test -Atc "select exists(select from pg_stat_activity where application_name='ringkit-diagnostics-a' and wait_event='PgSleep')") == t ]]; then locked=true; break; fi
  sleep 0.05
done
if [[ "$locked" != true ]]; then wait "$first"; echo 'Missing concurrency barrier' >&2; exit 1; fi
psql_test >"$scratch/request-b.log" <<'SQL' &
set role service_role;
select public.ringkit_diagnostic_request('00000000-0000-4000-8000-000000000001','diagnostic_session');
SQL
second=$!
wait "$first"
wait "$second"
psql_test <<'SQL'
do $$ begin
 if (select count(*) from public.diagnostic_requests where install_id='00000000-0000-4000-8000-000000000001' and kind='diagnostic_session' and state in ('queued','offered','acknowledged','collecting'))<>1 then raise exception 'concurrent request invariant'; end if;
end $$;
SQL

# Cancellation and receipt contend on the same device row. Once cancellation
# commits, the waiting upload must neither charge bytes nor create a receipt.
psql_test <<'SQL'
update public.diagnostic_requests set state='cancelled' where state in ('queued','offered','acknowledged','collecting');
insert into public.diagnostic_requests(id,install_id,app_id,kind,state)
values ('40000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000001','test','diagnostic_session','acknowledged');
SQL
psql_test >"$scratch/cancel-race.log" <<'SQL' &
set application_name='ringkit-cancel-race';
begin;
set local role service_role;
select public.ringkit_diagnostic_cancel('00000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000001');
select pg_sleep(1);
commit;
SQL
first=$!
locked=false
for attempt in {1..40}; do
  if [[ $(psql_test -Atc "select exists(select from pg_stat_activity where application_name='ringkit-cancel-race' and wait_event='PgSleep')") == t ]]; then locked=true; break; fi
  sleep 0.05
done
if [[ "$locked" != true ]]; then wait "$first"; echo 'Missing cancel barrier' >&2; exit 1; fi
psql_test >"$scratch/receipt-race.log" <<'SQL' &
set role service_role;
do $$ declare r jsonb; begin
 r:=public.ringkit_diagnostic_receipt('00000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000001','50000000-0000-4000-8000-000000000001',1,1,'synthetic/race','hash-race',100,'{"schema":1,"record_count":1,"raw_bytes":10,"call_ids":["race"],"final":true,"manifest":["50000000-0000-4000-8000-000000000001"]}','{}');
 if r->>'status'<>'cancelled' then raise exception 'race resurrected cancellation'; end if;
end $$;
SQL
second=$!
wait "$first"
wait "$second"
psql_test <<'SQL'
do $$ begin
 if exists(select from public.diagnostic_parts where request_id='40000000-0000-4000-8000-000000000001') then raise exception 'cancelled bytes stored'; end if;
end $$;
SQL
