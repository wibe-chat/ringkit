\set ON_ERROR_STOP on
begin;
do $$ begin
  if to_regprocedure('public.ringkit_checkin_gate(uuid,text,bigint,boolean)') is null then
    raise exception 'Fresh project has no atomic authenticated budget gate';
  end if;
end $$;
set local role service_role;
do $test$
declare
  id constant uuid := '00000000-0000-4000-8000-000000000010';
  token text;
  result jsonb;
  item record;
  rejected boolean;
begin
  token := public.ringkit_register(id,'test','maker','model',31,'1','1','app-a','test.app')->>'device_token';
  rejected := false;
  begin
    perform public.ringkit_register(id,null,null,null,null,null,null,' ',null);
  exception when insufficient_privilege then rejected := true; end;
  if not rejected then raise exception 'Blank app accepted'; end if;
  rejected := false;
  begin
    perform public.ringkit_checkin_gate(id,token,-1,false);
  exception when invalid_parameter_value then rejected := true; end;
  if not rejected then raise exception 'Negative bytes accepted'; end if;
  update public.devices set budget_kb=1 where install_id=id;
  result := public.ringkit_checkin_gate(id,'wrong',1,false);
  if result->>'status' <> 'unauthorized' or (select bytes_today from public.devices where install_id=id)<>0 then raise exception 'Bad token mutated budget'; end if;
  result := public.ringkit_checkin_gate(id,token,1024,false);
  if result->>'status'<>'ok' then raise exception 'Exact budget rejected'; end if;
  result := public.ringkit_checkin_gate(id,token,1,false);
  if result->>'status'<>'over_budget' or (select bytes_today from public.devices where install_id=id)<>1024 then raise exception 'Budget overspent'; end if;
  result := public.ringkit_checkin_gate(id,token,999999,true);
  if result->>'status'<>'ok' or (select bytes_today from public.devices where install_id=id)<>1024 then raise exception 'Pull consumed budget'; end if;
  update public.devices set budget_day=(now() at time zone 'utc')::date-1 where install_id=id;
  result := public.ringkit_checkin_gate(id,token,7,false);
  if result->>'status'<>'ok' or (select bytes_today from public.devices where install_id=id)<>7 then raise exception 'UTC budget did not reset'; end if;
  if not public.ringkit_record_incident(id,'fp','failure','point','{}',null,'2026-01-01','incident','incidents/test.gz',0) then raise exception 'First incident rejected'; end if;
  if public.ringkit_record_incident(id,'fp','failure','point','{}',null,'2026-01-01','incident','incidents/retry.gz',0) then raise exception 'Retry counted twice'; end if;
  perform public.ringkit_record_heartbeat(id,current_date,'{}','{"fp":1}');
  if (select occurrence_count from public.issues where fingerprint='fp')<>1 then raise exception 'Automatic incident counted twice by first heartbeat'; end if;
  perform public.ringkit_record_incident(id,'user-report','manual','manual','{}',null,'2026-01-01','user_report','incidents/user.gz',0);
  perform public.ringkit_record_incident(id,'user-report','manual','manual','{}',null,'2026-01-01','user_report','incidents/user-retry.gz',0);
  if (select occurrence_count from public.issues where fingerprint='user-report')<>1 then raise exception 'Manual report not counted exactly once'; end if;
  perform public.ringkit_record_incident(id,'null-ts','failure','point','{}',null,null,'incident','incidents/null.gz',null);
  if public.ringkit_record_incident(id,'null-ts','failure','point','{}',null,null,'incident','incidents/null-retry.gz',null) then raise exception 'Null timestamp retry counted twice'; end if;
  if not public.ringkit_record_incident(id,null,'pull','pull','{}',null,null,'pull','pulls/test.gz',null) then raise exception 'Pull payload rejected'; end if;
  perform public.ringkit_record_heartbeat(id,current_date,'{"calls":2}','{"fp":2}');
  perform public.ringkit_record_heartbeat(id,current_date,'{"calls":5}','{"fp":2}');
  if (select occurrence_count from public.issues where fingerprint='fp')<>5 or (select device_count from public.issues where fingerprint='fp')<>1 then raise exception 'Incident/delta aggregation wrong'; end if;
  if (select counters from public.heartbeats where install_id=id and day=current_date)<>'{"calls":5}'::jsonb then raise exception 'Counters added instead of replaced'; end if;
  if (select fingerprints from public.heartbeats where install_id=id and day=current_date)<>'{"fp":5}'::jsonb then raise exception 'Fingerprint deltas not accumulated'; end if;
  rejected := false;
  begin
    perform public.ringkit_record_heartbeat(id,current_date,'{"calls":-1}','{}');
  exception when invalid_parameter_value then rejected := true; end;
  if not rejected or (select counters from public.heartbeats where install_id=id and day=current_date)<>'{"calls":5}'::jsonb then raise exception 'Invalid heartbeat mutated counters'; end if;
  update public.devices set pull_requested=true where install_id=id;
  perform public.ringkit_pull_landed(id,1,2);
  if not (select pull_requested from public.devices where install_id=id) then raise exception 'Pull cleared early'; end if;
  perform public.ringkit_pull_landed(id,2,2);
  if (select pull_requested from public.devices where install_id=id) then raise exception 'Pull not cleared'; end if;
  perform public.ringkit_register('00000000-0000-4000-8000-000000000011','other','maker','model',31,'1','1','app-b','other.app');
  perform public.ringkit_record_heartbeat('00000000-0000-4000-8000-000000000011',current_date,'{"calls":9}','{"fp":1}');
  if (select device_count from public.issues where fingerprint='fp')<>2 then raise exception 'Distinct device not counted'; end if;
  perform public.ringkit_rollup_day(current_date);
  perform public.ringkit_rollup_day(current_date);
  if (select counters from public.fleet_daily where day=current_date and app_id='app-a')<>'{"calls":5}'::jsonb then raise exception 'Rollup not idempotent'; end if;
  if (select counters from public.fleet_daily where day=current_date and app_id='app-b')<>'{"calls":9}'::jsonb then raise exception 'Rollup mixed apps'; end if;
  update public.devices set app_id='renamed' where install_id=id;
  perform public.ringkit_rollup_day(current_date);
  if exists(select from public.fleet_daily where day=current_date and app_id='app-a') then raise exception 'Rollup retained stale dimensions'; end if;
  update public.incidents set server_ts=now()-interval '61 days';
  update public.heartbeats set received_at=now()-interval '61 days';
  perform public.ringkit_purge_rows(60);
  if exists(select from public.incidents) or exists(select from public.heartbeats) or not exists(select from public.fleet_daily) then raise exception 'Retention deleted wrong rows'; end if;
  rejected := false;
  begin
    perform public.ringkit_purge_rows(-1);
  exception when invalid_parameter_value then rejected := true; end;
  if not rejected then raise exception 'Negative retention accepted'; end if;
  for item in select c.oid,c.relname,c.relrowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind='r' loop
    if not item.relrowsecurity or has_table_privilege('anon',item.oid,'select,insert,update,delete') or has_table_privilege('authenticated',item.oid,'select,insert,update,delete') then raise exception 'Untrusted access to %',item.relname; end if;
  end loop;
  for item in select p.oid,p.proname from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname like 'ringkit_%' loop
    if has_function_privilege('anon',item.oid,'execute') or has_function_privilege('authenticated',item.oid,'execute') then raise exception 'Untrusted RPC access to %',item.proname; end if;
  end loop;
end $test$;
reset role;
insert into storage.objects(bucket_id,name,created_at) values ('ringkit-logs','expired.gz',now()-interval '61 days'),('ringkit-logs','current.gz',now()),('other','unrelated.gz',now()-interval '61 days');
set local role service_role;
do $$ begin
  if (select array_agg(name) from public.ringkit_expired_log_objects(60,100))<>array['expired.gz'] then raise exception 'Wrong storage expiry selection'; end if;
end $$;
reset role;
do $$ begin
  if not exists(select from storage.buckets where id='ringkit-logs' and not public) then raise exception 'Logs bucket not private'; end if;
  if (select count(*) from storage.objects)<>3 then raise exception 'SQL deleted storage metadata'; end if;
end $$;
rollback;
