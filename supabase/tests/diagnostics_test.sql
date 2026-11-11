-- Synthetic identities only. Real PostgreSQL, security-invoker RPCs.
insert into public.devices(install_id,app_id,device_token,telemetry_protocol,capabilities,diagnostics_enabled)
values ('00000000-0000-4000-8000-000000000001','test','30000000-0000-4000-8000-000000000001',2,array['diagnostic_session','correlated_pull'],true),
('00000000-0000-4000-8000-000000000002','other','30000000-0000-4000-8000-000000000002',2,array['diagnostic_session'],true);
set role service_role;
do $$
declare r jsonb; id uuid; id2 uuid; report uuid := '10000000-0000-4000-8000-000000000001';
meta jsonb := '{"schema":1,"raw_bytes":10,"record_count":1,"call_ids":["test-call"],"final":true,"manifest":["10000000-0000-4000-8000-000000000001"]}';
begin
 r:=public.ringkit_diagnostic_request('00000000-0000-4000-8000-000000000001','full_buffer'); id:=(r->>'id')::uuid;
 if r->>'state'<>'queued' then raise exception 'not queued'; end if;
 r:=public.ringkit_control('00000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',100,'{}',null,null);
 if r->'requests'->0->>'state'<>'offered' then raise exception 'not offered'; end if;
 if (select acknowledged_at from public.diagnostic_requests where diagnostic_requests.id=(r->'requests'->0->>'id')::uuid) is not null then raise exception 'offer inferred acknowledgement'; end if;
 r:=public.ringkit_control('00000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',100,'{}',id,'acknowledged');
 r:=public.ringkit_diagnostic_receipt('00000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',id,report,2,2,'synthetic/second','hash-two',100,meta,'{"sdk_build_id":"build-old"}');
 if r->>'state'<>'collecting' then raise exception 'completed missing first part'; end if;
 r:=public.ringkit_diagnostic_receipt('00000000-0000-4000-8000-000000000002','30000000-0000-4000-8000-000000000002',id,report,1,2,'synthetic/wrong','hash-one',100,meta,'{}');
 if r->>'status'<>'unauthorized' then raise exception 'cross-install receipt'; end if;
 r:=public.ringkit_diagnostic_receipt('00000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',id,report,1,2,'synthetic/first','hash-one',100,meta,'{"sdk_build_id":"build-old"}');
 if r->>'state'<>'received' then raise exception 'all parts not received'; end if;
 r:=public.ringkit_diagnostic_receipt('00000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',id,report,1,2,'synthetic/extra','hash-one',100,meta,'{"sdk_build_id":"build-old"}');
 if r->>'duplicate'<>'true' then raise exception 'retry not idempotent'; end if;
 if (select sum(body_bytes) from public.diagnostic_parts)<>200 then raise exception 'duplicate charged'; end if;
 r:=public.ringkit_diagnostic_request('00000000-0000-4000-8000-000000000001','full_buffer'); id2:=(r->>'id')::uuid;
 perform public.ringkit_pull_landed('00000000-0000-4000-8000-000000000001',1,1);
 if (select state from public.diagnostic_requests q where q.id=id2)<>'queued' then raise exception 'legacy completion changed UUID request'; end if;
 perform public.ringkit_diagnostic_cancel('00000000-0000-4000-8000-000000000001',id2);
 r:=public.ringkit_diagnostic_receipt('00000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',id2,report,1,1,'synthetic/stale','hash',100,meta,'{}');
 if r->>'status'<>'cancelled' then raise exception 'cancelled request accepted'; end if;
 r:=public.ringkit_control('00000000-0000-4000-8000-000000000001','wrong',100,'{}',null,null);
 if r->>'status'<>'unauthorized' then raise exception 'wrong token'; end if;
end $$;
reset role;
do $$
begin
 if has_table_privilege('anon','public.diagnostic_parts','select') or has_table_privilege('authenticated','public.diagnostic_requests','select') then raise exception 'public read'; end if;
 if exists(select from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname like 'ringkit_diagnostic%' and (p.prosecdef or has_function_privilege('anon',p.oid,'execute'))) then raise exception 'unsafe RPC'; end if;
end $$;
set role service_role;
do $$
declare r jsonb; request uuid; second uuid; report uuid:=gen_random_uuid(); before_bytes bigint;
 i uuid:='00000000-0000-4000-8000-000000000001';
begin
 r:=public.ringkit_diagnostic_request(i,'diagnostic_session'); request:=(r->>'id')::uuid;
 perform public.ringkit_control(i,'30000000-0000-4000-8000-000000000001',100,'{}',null,null);
 r:=public.ringkit_diagnostic_authorize(i,'wrong',request);
 if r->>'status'<>'unauthorized' then raise exception 'pre-upload auth'; end if;
 r:=public.ringkit_diagnostic_authorize(i,'30000000-0000-4000-8000-000000000001',request);
 if r->>'status'<>'not_acknowledged' then raise exception 'upload before ack'; end if;
 perform public.ringkit_control(i,'30000000-0000-4000-8000-000000000001',100,'{}',request,'acknowledged');
 select received_bytes into before_bytes from public.diagnostic_requests where id=request;
 r:=public.ringkit_diagnostic_receipt(i,'30000000-0000-4000-8000-000000000001',request,report,1,1,'synthetic/large','hash-large',524289,
   jsonb_build_object('schema',1,'raw_bytes',10,'final',true,'manifest',jsonb_build_array(report),'record_count',0,'call_ids','[]'::jsonb),'{}');
 if r->>'status'<>'over_budget' or (select received_bytes from public.diagnostic_requests where id=request)<>before_bytes then raise exception 'session budget'; end if;
 r:=public.ringkit_diagnostic_receipt(i,'30000000-0000-4000-8000-000000000001',request,report,1,1,'synthetic/empty','hash-empty',100,
   jsonb_build_object('schema',1,'raw_bytes',10,'final',true,'manifest',jsonb_build_array(report),'record_count',0,'call_ids','[]'::jsonb),'{}');
 if r->>'state'<>'received_empty' then raise exception 'empty implied evidence'; end if;
 r:=public.ringkit_diagnostic_request(i,'diagnostic_session'); request:=(r->>'id')::uuid;
 r:=public.ringkit_diagnostic_request(i,'diagnostic_session'); second:=(r->>'id')::uuid;
 r:=public.ringkit_control(i,'30000000-0000-4000-8000-000000000001',100,'{}',request,'acknowledged');
 if r->>'request_state'<>'cancelled' then raise exception 'replacement resurrected old'; end if;
 update public.diagnostic_requests set expires_at=now()-interval '1 second' where id=second;
 r:=public.ringkit_control(i,'30000000-0000-4000-8000-000000000001',100,'{}',second,'acknowledged');
 if r->>'request_state'<>'expired' then raise exception 'expired request acknowledged'; end if;
 update public.devices set control_bytes_today=65536,control_budget_day=(now() at time zone 'utc')::date where install_id=i;
 r:=public.ringkit_control(i,'30000000-0000-4000-8000-000000000001',1,'{}',null,null);
 if r->>'status'<>'over_budget' then raise exception 'control budget not bounded'; end if;
 update public.devices set control_bytes_today=0 where install_id=i;
end $$;
reset role;

set role service_role;
do $$
declare i uuid:='00000000-0000-4000-8000-000000000001';
begin
 perform public.ringkit_identity(i,'{"sdk_build_id":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","telemetry_protocol":2,"capabilities":["diagnostic_session"],"host_debuggable":true,"host_version_code":9}');
 perform public.ringkit_identity(i,'{}');
 if (select sdk_build_id is null from public.devices where install_id=i) then raise exception 'absent descriptor erased identity'; end if;
 perform public.ringkit_identity(i,'{"plugin_version":"0.2.0","app_version":"old"}');
 if exists(select from public.devices where install_id=i and (sdk_build_id is not null or telemetry_protocol<>1 or cardinality(capabilities)<>0 or host_debuggable is not null or host_version_code is not null)) then raise exception 'legacy downgrade retained identity'; end if;
end $$;
reset role;

set role service_role;
do $$
declare r jsonb; i uuid:='00000000-0000-4000-8000-000000000001'; q uuid;
begin
 update public.devices set telemetry_protocol=2,capabilities=array['diagnostic_session'],diagnostics_enabled=true where install_id=i;
 r:=public.ringkit_diagnostic_request(i,'diagnostic_session'); q:=(r->>'id')::uuid;
 r:=public.ringkit_control(i,'30000000-0000-4000-8000-000000000001',100,'{}',null,null);
 if r->'requests'->0 ? 'tester_observation' then raise exception 'operator observation shipped back to client'; end if;
 update public.devices set app_id='renamed-app' where install_id=i;
 r:=public.ringkit_control(i,'30000000-0000-4000-8000-000000000001',100,'{}',null,null);
 if not exists(select from jsonb_array_elements(r->'request_states') s where s->>'id'=q::text and s->>'state'='cancelled') then raise exception 'renamed app stranded session'; end if;
 if (select last_control_at from public.devices where install_id=i) is null then raise exception 'missing control contact'; end if;
 update public.devices set app_id='test' where install_id=i;
end $$;
reset role;
