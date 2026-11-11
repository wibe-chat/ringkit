-- Additive v2 support. All installations remain unenrolled by default.
begin;
alter table public.devices
  add column sdk_build_id text,
  add column telemetry_protocol integer not null default 1,
  add column capabilities text[] not null default '{}',
  add column host_version_code bigint,
  add column host_debuggable boolean,
  add column device_identity jsonb not null default '{}',
  add column diagnostics_enabled boolean not null default false,
  add column control_bytes_today bigint not null default 0,
  add column control_budget_day date;
alter table public.incidents add column identity jsonb not null default '{}';

create table public.diagnostic_requests (
  id uuid primary key default gen_random_uuid(),
  install_id uuid not null references public.devices(install_id),
  app_id text not null,
  kind text not null check(kind in ('full_buffer','diagnostic_session')),
  state text not null default 'queued' check(state in ('queued','offered','acknowledged','collecting','received','received_empty','cancelled','expired','failed')),
  requested_at timestamptz not null default now(),
  expires_at timestamptz not null default now()+interval '24 hours',
  offered_at timestamptz, acknowledged_at timestamptz, capture_until timestamptz,
  received_at timestamptz, updated_at timestamptz not null default now(),
  duration_ms integer not null default 1800000 check(duration_ms between 1000 and 1800000),
  max_calls integer not null default 20 check(max_calls between 1 and 20),
  max_bytes integer not null default 524288 check(max_bytes between 1024 and 8388608),
  received_bytes bigint not null default 0,
  record_count bigint not null default 0,
  call_count integer not null default 0,
  manifest jsonb,
  end_reason text,
  tester_observation jsonb
);
create unique index diagnostic_requests_active_idx on public.diagnostic_requests(install_id,kind)
  where state in ('queued','offered','acknowledged','collecting');
create index diagnostic_requests_install_idx on public.diagnostic_requests(install_id,requested_at desc);
create index diagnostic_requests_expiry_idx on public.diagnostic_requests(expires_at)
  where state in ('queued','offered','acknowledged','collecting');
create table public.diagnostic_parts (
  request_id uuid not null references public.diagnostic_requests(id) on delete cascade,
  report_id uuid not null,
  part integer not null check(part between 1 and 32),
  parts_total integer not null check(parts_total between 1 and 32 and part<=parts_total),
  payload_path text not null unique,
  payload_sha256 text not null,
  body_bytes integer not null check(body_bytes between 1 and 3145728),
  metadata jsonb not null,
  identity jsonb not null,
  received_at timestamptz not null default now(),
  primary key(request_id,report_id,part)
);

-- Auth is performed by the ingest boundary before this private metadata RPC.
create function public.ringkit_identity(p_install uuid,p_identity jsonb)
returns jsonb language plpgsql security invoker set search_path='' as $fn$
declare d public.devices%rowtype;
begin
  if jsonb_typeof(p_identity) is distinct from 'object' or octet_length(p_identity::text)>4096 then
    raise invalid_parameter_value using message='invalid identity';
  end if;
  update public.devices set
    sdk_build_id=case when p_identity='{}' then sdk_build_id else p_identity->>'sdk_build_id' end,
    telemetry_protocol=case when p_identity='{}' then telemetry_protocol else coalesce((p_identity->>'telemetry_protocol')::integer,1) end,
    capabilities=case when jsonb_typeof(p_identity->'capabilities')='array' then
      array(select jsonb_array_elements_text(p_identity->'capabilities')) when p_identity='{}' then capabilities else '{}' end,
    host_version_code=case when p_identity='{}' then host_version_code else (p_identity->>'host_version_code')::bigint end,
    host_debuggable=case when p_identity='{}' then host_debuggable else (p_identity->>'host_debuggable')::boolean end,
    app_version=coalesce(p_identity->>'app_version',app_version),
    plugin_version=coalesce(p_identity->>'plugin_version',plugin_version),
    device_identity=case when p_identity='{}'::jsonb then device_identity else p_identity end
    where install_id=p_install returning * into d;
  if not found then raise insufficient_privilege using message='unauthorized'; end if;
  return jsonb_build_object('telemetry_protocol',2,'diagnostics_enabled',d.diagnostics_enabled);
end $fn$;

-- Incident metadata and the durable receipt are one database transaction.
create function public.ringkit_record_incident_v2(
 p_install uuid,p_fingerprint text,p_type text,p_codepoint text,p_degradations text[],
 p_call_id text,p_device_ts timestamptz,p_kind text,p_payload_path text,p_clock_offset_ms bigint,p_identity jsonb)
returns boolean language plpgsql security invoker set search_path='' as $fn$
declare fresh boolean;
begin
  fresh:=public.ringkit_record_incident(p_install,p_fingerprint,p_type,p_codepoint,p_degradations,
    p_call_id,p_device_ts,p_kind,p_payload_path,p_clock_offset_ms);
  if fresh then update public.incidents set identity=p_identity where install_id=p_install and payload_path=p_payload_path; end if;
  return fresh;
end $fn$;

create function public.ringkit_diagnostic_enroll(p_install uuid,p_enabled boolean)
returns jsonb language plpgsql security invoker set search_path='' as $fn$
begin
  if p_enabled is null then raise invalid_parameter_value using message='missing enabled'; end if;
  perform 1 from public.devices where install_id=p_install for update;
  if not found then raise insufficient_privilege using message='unauthorized'; end if;
  update public.devices set diagnostics_enabled=p_enabled where install_id=p_install;
  if not p_enabled then update public.diagnostic_requests set state='cancelled',end_reason='disabled',updated_at=now()
    where install_id=p_install and state in ('queued','offered','acknowledged','collecting'); end if;
  return jsonb_build_object('enabled',p_enabled);
end $fn$;

create function public.ringkit_diagnostic_request(p_install uuid,p_kind text)
returns jsonb language plpgsql security invoker set search_path='' as $fn$
declare d public.devices%rowtype; r public.diagnostic_requests%rowtype;
begin
  select * into d from public.devices where install_id=p_install for update;
  if not found then raise insufficient_privilege using message='unauthorized'; end if;
  if p_kind not in ('full_buffer','diagnostic_session') then raise invalid_parameter_value using message='invalid kind'; end if;
  if d.killed or not d.diagnostics_enabled or d.telemetry_protocol<2 or not
    (case when p_kind='full_buffer' then 'correlated_pull' else 'diagnostic_session' end=any(d.capabilities)) then
    return jsonb_build_object('status','unsupported');
  end if;
  update public.diagnostic_requests set state='cancelled',updated_at=now(),end_reason='replaced'
    where install_id=p_install and kind=p_kind and state in ('queued','offered','acknowledged','collecting');
  insert into public.diagnostic_requests(install_id,app_id,kind,max_bytes)
    values(p_install,d.app_id,p_kind,case when p_kind='full_buffer' then 8388608 else 524288 end) returning * into r;
  return to_jsonb(r);
end $fn$;

create function public.ringkit_diagnostic_cancel(p_install uuid,p_request uuid)
returns jsonb language plpgsql security invoker set search_path='' as $fn$
declare r public.diagnostic_requests%rowtype;
begin
  perform 1 from public.devices where install_id=p_install for update;
  update public.diagnostic_requests set state='cancelled',updated_at=now(),end_reason='operator'
    where id=p_request and install_id=p_install and state in ('queued','offered','acknowledged','collecting') returning * into r;
  if not found then select * into r from public.diagnostic_requests where id=p_request and install_id=p_install; end if;
  return coalesce(to_jsonb(r),'{}');
end $fn$;

alter table public.devices add column if not exists last_control_at timestamptz;

alter table public.diagnostic_requests add column last_client_status jsonb;

create function public.ringkit_control(p_install uuid,p_token text,p_bytes integer,p_identity jsonb,p_request uuid,p_action text,p_status jsonb default '[]')
returns jsonb language plpgsql security invoker set search_path='' as $fn$
declare d public.devices%rowtype; r public.diagnostic_requests%rowtype; used bigint;
 today date:=(now() at time zone 'utc')::date; result jsonb; offers jsonb; states jsonb; item jsonb;
begin
  select * into d from public.devices where install_id=p_install and device_token::text=p_token for update;
  if not found then return jsonb_build_object('status','unauthorized'); end if;
  if p_bytes is null or p_bytes<0 or p_bytes>8192 then return jsonb_build_object('status','invalid'); end if;
  used:=case when d.control_budget_day=today then d.control_bytes_today else 0 end;
  result:=jsonb_build_object('telemetry_protocol',2,'diagnostics_enabled',d.diagnostics_enabled,
    'log_level',d.log_level,'pull_buffer',d.pull_requested,'sampled',d.sampled,'budget_kb',d.budget_kb,'kill',d.killed);
  if used+p_bytes>65536 then return result||jsonb_build_object('status','over_budget'); end if;
  update public.devices set control_budget_day=today,control_bytes_today=used+p_bytes,last_seen=now(),last_control_at=now() where install_id=p_install;
  perform public.ringkit_identity(p_install,p_identity);
  if jsonb_typeof(p_status)='array' and jsonb_array_length(p_status)<=6 then
    for item in select value from jsonb_array_elements(p_status) loop
      if item->>'outcome' in ('attempting','acknowledged_part','network_error','http_5xx','budget_deferred','auth_retry','rejected','retry_limit') then
        update public.diagnostic_requests set last_client_status=jsonb_build_object('outcome',item->>'outcome',
          'observed_at',now(),'device_wall_ms',item->'wall_ms','boot',item->'boot','elapsed_ms',item->'elapsed_ms',
          'report_id',item->'report_id','part',item->'part','pending_parts',item->'pending_parts')
          where id::text=item->>'id' and install_id=p_install and app_id=d.app_id;
      end if;
    end loop;
  end if;
  update public.diagnostic_requests set state='expired',updated_at=now(),end_reason='expired'
    where install_id=p_install and expires_at<=now() and state in ('queued','offered','acknowledged','collecting');
  update public.diagnostic_requests set state='cancelled',updated_at=now(),end_reason='app_changed'
    where install_id=p_install and app_id<>d.app_id and state in ('queued','offered','acknowledged','collecting');
  if d.killed or not d.diagnostics_enabled then
    update public.diagnostic_requests set state='cancelled',updated_at=now(),end_reason='disabled'
      where install_id=p_install and state in ('queued','offered','acknowledged','collecting');
  end if;
  if p_request is not null then
    select * into r from public.diagnostic_requests where id=p_request and install_id=p_install and app_id=d.app_id for update;
    if not found then return jsonb_build_object('status','unauthorized'); end if;
    if r.state in ('offered','acknowledged','collecting') then
      if p_action='acknowledged' and r.state='offered' then
        update public.diagnostic_requests set state='acknowledged',acknowledged_at=now(),
          capture_until=now()+make_interval(secs=>duration_ms/1000.0),expires_at=now()+interval '24 hours',updated_at=now()
          where id=p_request returning * into r;
      elsif p_action='collecting' and r.state='acknowledged' then
        update public.diagnostic_requests set state='collecting',updated_at=now() where id=p_request returning * into r;
      elsif p_action='failed' then
        update public.diagnostic_requests set state='failed',end_reason='device_failure',updated_at=now() where id=p_request returning * into r;
      end if;
    end if;
  end if;
  update public.diagnostic_requests set state='offered',offered_at=coalesce(offered_at,now()),updated_at=now()
    where install_id=p_install and state='queued';
  select coalesce(jsonb_agg(jsonb_build_object('id',q.id,'kind',q.kind,'state',q.state,'duration_ms',q.duration_ms,
      'max_calls',q.max_calls,'max_bytes',q.max_bytes) order by q.requested_at),'[]') into offers from public.diagnostic_requests q
    where q.install_id=p_install and q.state in ('offered','acknowledged','collecting');
  select coalesce(jsonb_agg(jsonb_build_object('id',q.id,'state',q.state)),'[]') into states from (
    select id,state from public.diagnostic_requests where install_id=p_install
      and state in ('received','received_empty','cancelled','expired','failed') order by updated_at desc limit 32
  ) q;
  return result||jsonb_build_object('status','ok','requests',offers,'request_states',states,'request_state',r.state,'server_wall_ms',floor(extract(epoch from now())*1000));
end $fn$;

create function public.ringkit_diagnostic_authorize(p_install uuid,p_token text,p_request uuid)
returns jsonb language plpgsql security invoker set search_path='' as $fn$
declare d public.devices%rowtype; r public.diagnostic_requests%rowtype;
begin
  select * into d from public.devices where install_id=p_install and device_token::text=p_token;
  if not found then return jsonb_build_object('status','unauthorized'); end if;
  select * into r from public.diagnostic_requests where id=p_request and install_id=p_install and app_id=d.app_id;
  if not found then return jsonb_build_object('status','unauthorized'); end if;
  if d.killed or not d.diagnostics_enabled then return jsonb_build_object('status','cancelled'); end if;
  if r.state in ('cancelled','expired','failed') then return jsonb_build_object('status',r.state); end if;
  if r.expires_at<=now() then return jsonb_build_object('status','expired'); end if;
  if r.state not in ('acknowledged','collecting','received','received_empty') then return jsonb_build_object('status','not_acknowledged'); end if;
  return jsonb_build_object('status','ok','kind',r.kind,'max_calls',r.max_calls);
end $fn$;

-- Storage has already durably stored AND parsed the payload. The private RPC
-- commits a receipt once. Lock order is always device -> request -> parts.
create function public.ringkit_diagnostic_receipt(
 p_install uuid,p_token text,p_request uuid,p_report uuid,p_part integer,p_of integer,
 p_path text,p_hash text,p_bytes integer,p_meta jsonb,p_identity jsonb)
returns jsonb language plpgsql security invoker set search_path='' as $fn$
declare d public.devices%rowtype; r public.diagnostic_requests%rowtype; old public.diagnostic_parts%rowtype;
 records bigint; calls integer; expected jsonb; complete boolean;
begin
  select * into d from public.devices where install_id=p_install and device_token::text=p_token for update;
  if not found then return jsonb_build_object('status','unauthorized'); end if;
  select * into r from public.diagnostic_requests where id=p_request and install_id=p_install and app_id=d.app_id for update;
  if not found then return jsonb_build_object('status','unauthorized'); end if;
  if d.killed or not d.diagnostics_enabled then return jsonb_build_object('status','cancelled'); end if;
  if r.state in ('cancelled','expired','failed') then return jsonb_build_object('status',r.state); end if;
  if r.expires_at<=now() and r.state not in ('received','received_empty') then
    update public.diagnostic_requests set state='expired',updated_at=now(),end_reason='expired' where id=p_request;
    return jsonb_build_object('status','expired');
  end if;
  select * into old from public.diagnostic_parts where request_id=p_request and report_id=p_report and part=p_part;
  if found then
    if old.payload_sha256<>p_hash or old.parts_total<>p_of or old.metadata<>p_meta or old.identity<>p_identity then return jsonb_build_object('status','conflict'); end if;
    return jsonb_build_object('status','ok','state',r.state,'duplicate',true,'payload_path',old.payload_path);
  end if;
  if r.state not in ('acknowledged','collecting') then return jsonb_build_object('status','not_acknowledged'); end if;
  if p_report is null or p_part is null or p_of is null or p_part<1 or p_part>p_of or p_of>32 or p_bytes is null or p_bytes<1 or
     p_bytes>3145728 or jsonb_typeof(p_meta) is distinct from 'object' or p_meta->>'schema' is distinct from '1' or
     jsonb_typeof(p_meta->'call_ids') is distinct from 'array' or jsonb_array_length(p_meta->'call_ids')>20 or
     (p_meta->>'record_count') is null or (p_meta->>'record_count')::bigint<0 then return jsonb_build_object('status','invalid'); end if;
  if exists(select from public.diagnostic_parts where request_id=p_request and report_id=p_report and (parts_total<>p_of or identity<>p_identity)) then
    return jsonb_build_object('status','conflict');
  end if;
  if coalesce((p_meta->>'raw_bytes')::bigint,-1)<0 or coalesce((p_meta->>'raw_bytes')::bigint,0)+(select coalesce(sum((metadata->>'raw_bytes')::bigint),0) from public.diagnostic_parts where request_id=p_request)>5242880 then return jsonb_build_object('status','over_budget'); end if;
  if r.received_bytes+p_bytes>r.max_bytes then return jsonb_build_object('status','over_budget'); end if;
  expected:=r.manifest;
  if coalesce((p_meta->>'final')::boolean,false) then
    if jsonb_typeof(p_meta->'manifest') is distinct from 'array' or jsonb_array_length(p_meta->'manifest') not between 1 and 21 then
      return jsonb_build_object('status','invalid');
    end if;
    if expected is not null and expected<>p_meta->'manifest' then return jsonb_build_object('status','conflict'); end if;
    expected:=p_meta->'manifest';
  end if;
  if expected is not null and (not expected ? p_report::text or exists(
    select from public.diagnostic_parts where request_id=p_request and not expected ? report_id::text)) then
    return jsonb_build_object('status','conflict');
  end if;
  select count(distinct call_id) into calls from (
    select jsonb_array_elements_text(metadata->'call_ids') call_id from public.diagnostic_parts where request_id=p_request
    union all select jsonb_array_elements_text(p_meta->'call_ids')
  ) c;
  if r.kind='diagnostic_session' and calls>r.max_calls then return jsonb_build_object('status','over_budget'); end if;
  insert into public.diagnostic_parts(request_id,report_id,part,parts_total,payload_path,payload_sha256,body_bytes,metadata,identity)
    values(p_request,p_report,p_part,p_of,p_path,p_hash,p_bytes,p_meta,p_identity);
  select coalesce(sum((metadata->>'record_count')::bigint),0) into records from public.diagnostic_parts where request_id=p_request;
  complete:=expected is not null and not exists(
    select from jsonb_array_elements_text(expected) e(report)
    where not exists(select from public.diagnostic_parts p where p.request_id=p_request and p.report_id::text=e.report
      group by p.report_id having count(*)=max(p.parts_total)));
  update public.diagnostic_requests set received_bytes=received_bytes+p_bytes,record_count=records,call_count=calls,
    manifest=expected,state=case when complete then case when records=0 then 'received_empty' else 'received' end else 'collecting' end,
    received_at=case when complete then now() else null end,updated_at=now(),end_reason=coalesce(p_meta->>'end_reason',end_reason)
    where id=p_request returning * into r;
  return jsonb_build_object('status','ok','state',r.state,'duplicate',false,'payload_path',p_path);
end $fn$;

-- Retain the existing Storage API cleanup; extend only private row retention.
create or replace function public.ringkit_purge_rows(p_max_age_days integer)
returns jsonb language plpgsql security invoker set search_path='' as $fn$
declare a bigint; b bigint; c bigint; cutoff timestamptz;
begin
  if p_max_age_days is null or p_max_age_days<0 then raise invalid_parameter_value using message='invalid retention'; end if;
  cutoff:=now()-make_interval(days=>p_max_age_days);
  update public.diagnostic_requests set state='expired',updated_at=now(),end_reason='expired'
    where expires_at<=now() and state in ('queued','offered','acknowledged','collecting');
  delete from public.diagnostic_requests where updated_at<cutoff and state not in ('queued','offered','acknowledged','collecting');
  get diagnostics c=row_count;
  delete from public.incidents where server_ts<cutoff; get diagnostics a=row_count;
  delete from public.heartbeats where received_at<cutoff; get diagnostics b=row_count;
  return jsonb_build_object('incidents',a,'heartbeats',b,'diagnostic_requests',c);
end $fn$;

do $acl$
declare obj text; signature text;
begin
  foreach obj in array array['diagnostic_requests','diagnostic_parts'] loop
    execute format('alter table public.%I enable row level security',obj);
    execute format('revoke all on public.%I from public,anon,authenticated',obj);
    execute format('grant select,insert,update,delete on public.%I to service_role',obj);
  end loop;
  foreach signature in array array[
    'ringkit_record_incident_v2(uuid,text,text,text,text[],text,timestamptz,text,text,bigint,jsonb)',
    'ringkit_diagnostic_authorize(uuid,text,uuid)','ringkit_diagnostic_enroll(uuid,boolean)','ringkit_identity(uuid,jsonb)','ringkit_diagnostic_request(uuid,text)','ringkit_diagnostic_cancel(uuid,uuid)',
    'ringkit_control(uuid,text,integer,jsonb,uuid,text,jsonb)',
    'ringkit_diagnostic_receipt(uuid,text,uuid,uuid,integer,integer,text,text,integer,jsonb,jsonb)'
  ] loop
    execute 'revoke all on function public.'||signature||' from public,anon,authenticated';
    execute 'grant execute on function public.'||signature||' to service_role';
  end loop;
end $acl$;
commit;
