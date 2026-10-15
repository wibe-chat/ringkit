-- Fresh projects only. No IF NOT EXISTS / replacement of existing RingKit data.
-- Apply this standalone migration tree, NOT the historical root delta migrations.
begin;
create table public.devices (
  install_id uuid primary key,
  device_token text not null unique default (gen_random_uuid()::text || gen_random_uuid()::text),
  app_id text not null check (btrim(app_id) <> ''), package_name text,
  device_label text not null default 'unlabeled', manufacturer text, model text,
  sdk_int integer, app_version text, plugin_version text,
  first_seen timestamptz not null default now(), last_seen timestamptz not null default now(),
  log_level text not null default 'info' check (log_level in ('none','error','info','verbose')),
  pull_requested boolean not null default false, sampled boolean not null default false,
  killed boolean not null default false, budget_kb integer not null default 200 check (budget_kb >= 0),
  bytes_today bigint not null default 0 check (bytes_today >= 0),
  budget_day date not null default ((now() at time zone 'utc')::date)
);
create index devices_app_id_idx on public.devices(app_id);
create index devices_last_seen_idx on public.devices(last_seen);
create table public.fleet_config (key text primary key, value text not null);
create table public.fleet_keys (
  api_key text primary key check (btrim(api_key) <> ''),
  app_id text not null check (btrim(app_id) <> ''), label text,
  active boolean not null default true, created_at timestamptz not null default now()
);
create table public.issues (
  fingerprint text primary key, incident_type text not null default 'unknown',
  codepoint text not null default 'unknown', degradations text[] not null default '{}',
  occurrence_count bigint not null default 0, device_count bigint not null default 0,
  status text not null default 'open',
  first_seen timestamptz not null default now(), last_seen timestamptz not null default now()
);
create table public.issue_devices (
  fingerprint text references public.issues(fingerprint),
  install_id uuid references public.devices(install_id),
  primary key(fingerprint,install_id)
);
create index issue_devices_install_idx on public.issue_devices(install_id);
create table public.incidents (
  id uuid primary key default gen_random_uuid(),
  install_id uuid not null references public.devices(install_id),
  fingerprint text references public.issues(fingerprint),
  kind text not null check (kind in ('incident','user_report','pull')),
  call_id text, device_ts timestamptz, server_ts timestamptz not null default now(),
  clock_offset_ms bigint, payload_path text not null
);
-- NULL timestamps still deduplicate incident retries; pulls have no fingerprint.
create unique index incidents_retry_idx on public.incidents(install_id,fingerprint,device_ts)
  nulls not distinct where fingerprint is not null;
create index incidents_install_ts_idx on public.incidents(install_id,server_ts desc);
create index incidents_server_ts_idx on public.incidents(server_ts);
create index incidents_fingerprint_idx on public.incidents(fingerprint);
create table public.heartbeats (
  install_id uuid references public.devices(install_id), day date not null,
  counters jsonb not null default '{}' check (jsonb_typeof(counters)='object'),
  fingerprints jsonb not null default '{}' check (jsonb_typeof(fingerprints)='object'),
  received_at timestamptz not null default now(), primary key(install_id,day)
);
create index heartbeats_day_idx on public.heartbeats(day);
create index heartbeats_received_at_idx on public.heartbeats(received_at);
create table public.fleet_daily (
  day date, app_id text, manufacturer text, model text, sdk_int integer,
  devices bigint not null, counters jsonb not null,
  primary key(day,app_id,manufacturer,model,sdk_int)
);

create function public.ringkit_register(
  p_install uuid, p_label text, p_manufacturer text, p_model text,
  p_sdk integer, p_app_version text, p_plugin_version text,
  p_app_id text default null, p_package_name text default null
) returns jsonb language plpgsql security invoker set search_path = '' as $fn$
declare d public.devices%rowtype;
begin
  if p_app_id is null or btrim(p_app_id) = '' then
    raise insufficient_privilege using message = 'unauthorized';
  end if;
  insert into public.devices (install_id, device_label, manufacturer, model, sdk_int, app_version, plugin_version, app_id, package_name)
  values (p_install, coalesce(p_label,'unlabeled'), p_manufacturer, p_model, p_sdk, p_app_version, p_plugin_version, p_app_id, p_package_name)
  on conflict (install_id) do update
    set device_label = coalesce(p_label, devices.device_label),
        manufacturer = coalesce(p_manufacturer, devices.manufacturer),
        model = coalesce(p_model, devices.model), sdk_int = coalesce(p_sdk, devices.sdk_int),
        app_version = coalesce(p_app_version, devices.app_version),
        plugin_version = coalesce(p_plugin_version, devices.plugin_version),
        package_name = coalesce(p_package_name, devices.package_name), last_seen = now()
    where devices.app_id = excluded.app_id
  returning * into d;
  if not found then raise insufficient_privilege using message = 'unauthorized'; end if;
  return jsonb_build_object('device_token',d.device_token,'log_level',d.log_level,
    'pull_buffer',d.pull_requested,'sampled',d.sampled,'budget_kb',d.budget_kb,'kill',d.killed);
end $fn$;

create function public.ringkit_checkin_gate(p_install uuid,p_token text,p_bytes bigint,p_exempt boolean)
returns jsonb language plpgsql security invoker set search_path = '' as $fn$
declare
  d public.devices%rowtype;
  today date := (now() at time zone 'utc')::date;
  used bigint;
  result jsonb;
begin
  if p_bytes is null or p_bytes < 0 or p_exempt is null then raise invalid_parameter_value using message='invalid budget request'; end if;
  select * into d from public.devices where install_id=p_install and device_token=p_token for update;
  if not found then return jsonb_build_object('status','unauthorized'); end if;
  used := case when d.budget_day=today then d.bytes_today else 0 end;
  result := jsonb_build_object('log_level',d.log_level,'pull_buffer',d.pull_requested,
    'sampled',d.sampled,'budget_kb',d.budget_kb,'kill',d.killed);
  if not p_exempt and p_bytes > d.budget_kb::bigint*1024-used then
    return result || jsonb_build_object('status','over_budget');
  end if;
  update public.devices set bytes_today=used+case when p_exempt then 0 else p_bytes end,
    budget_day=today,last_seen=now() where install_id=p_install;
  return result || jsonb_build_object('status','ok');
end $fn$;

create function public.ringkit_record_incident(
  p_install uuid,p_fingerprint text,p_type text,p_codepoint text,p_degradations text[],
  p_call_id text,p_device_ts timestamptz,p_kind text,p_payload_path text,p_clock_offset_ms bigint
) returns boolean language plpgsql security invoker set search_path = '' as $fn$
declare inserted integer;
begin
  if p_kind not in ('incident','user_report','pull') or p_kind is null
    or (p_kind <> 'pull' and (p_fingerprint is null or btrim(p_fingerprint)=''))
    or (p_kind = 'pull' and p_fingerprint is not null) then
    raise invalid_parameter_value using message='invalid incident';
  end if;
  -- Match heartbeat lock order, including the device lock needed by FK checks.
  perform 1 from public.devices where install_id=p_install for update;
  if not found then raise foreign_key_violation using message='unknown installation'; end if;
  if p_fingerprint is not null then
    insert into public.issues(fingerprint,incident_type,codepoint,degradations)
    values(p_fingerprint,p_type,p_codepoint,p_degradations)
    on conflict(fingerprint) do update set incident_type=excluded.incident_type,
      codepoint=excluded.codepoint,degradations=excluded.degradations;
  end if;
  insert into public.incidents(install_id,fingerprint,kind,call_id,device_ts,payload_path,clock_offset_ms)
  values(p_install,p_fingerprint,p_kind,p_call_id,p_device_ts,p_payload_path,p_clock_offset_ms)
  on conflict do nothing;
  get diagnostics inserted = row_count;
  if inserted=0 then return false; end if;
  if p_fingerprint is not null then
    insert into public.issue_devices(fingerprint,install_id) values(p_fingerprint,p_install) on conflict do nothing;
    get diagnostics inserted = row_count;
    -- Automatic occurrences arrive in heartbeat deltas, including the occurrence
    -- that produced this payload. Only manual reports lack that heartbeat count.
    update public.issues set occurrence_count=occurrence_count+case when p_kind='user_report' then 1 else 0 end,
      device_count=device_count+inserted,last_seen=now()
      where fingerprint=p_fingerprint;
  end if;
  return true;
end $fn$;

create function public.ringkit_record_heartbeat(p_install uuid,p_day date,p_counters jsonb,p_fingerprints jsonb)
returns void language plpgsql security invoker set search_path = '' as $fn$
declare entry record; delta bigint; added integer;
begin
  if p_counters is null or p_fingerprints is null or jsonb_typeof(p_counters)<>'object'
    or jsonb_typeof(p_fingerprints)<>'object' then raise invalid_parameter_value using message='invalid heartbeat'; end if;
  -- Validate integer counters before they can poison the nightly aggregation.
  for entry in select value from jsonb_each_text(p_counters) loop
    if entry.value is null or entry.value !~ '^[0-9]+$' then raise invalid_parameter_value using message='invalid counter'; end if;
    delta := entry.value::bigint;
  end loop;
  -- Serialize heartbeat deltas for this installation; sorted fingerprint locks
  -- below use the same order for every installation to avoid deadlocks.
  perform 1 from public.devices where install_id=p_install for update;
  if not found then raise foreign_key_violation using message='unknown installation'; end if;
  insert into public.heartbeats(install_id,day,counters) values(p_install,p_day,p_counters)
    on conflict(install_id,day) do update set counters=excluded.counters,received_at=now();
  for entry in select key,value from jsonb_each_text(p_fingerprints) order by key loop
    if entry.value is null or entry.value !~ '^[0-9]+$' or btrim(entry.key)='' then raise invalid_parameter_value using message='invalid fingerprint delta'; end if;
    delta := entry.value::bigint;
    if delta=0 then continue; end if;
    insert into public.issues(fingerprint) values(entry.key)
      on conflict(fingerprint) do update set last_seen=now();
    insert into public.issue_devices(fingerprint,install_id) values(entry.key,p_install) on conflict do nothing;
    get diagnostics added = row_count;
    update public.issues set occurrence_count=occurrence_count+delta,device_count=device_count+added,last_seen=now()
      where fingerprint=entry.key;
    update public.heartbeats set fingerprints=jsonb_set(fingerprints,array[entry.key],
      to_jsonb(coalesce((fingerprints->>entry.key)::bigint,0)+delta))
      where install_id=p_install and day=p_day;
  end loop;
end $fn$;

create function public.ringkit_pull_landed(p_install uuid,p_part integer,p_of integer)
returns void language plpgsql security invoker set search_path = '' as $fn$
begin
  if p_part is null or p_of is null or p_part<1 or p_of<1 or p_part>p_of then
    raise invalid_parameter_value using message='invalid pull part';
  end if;
  if p_part=p_of then update public.devices set pull_requested=false where install_id=p_install; end if;
end $fn$;

create function public.ringkit_rollup_day(target_day date)
returns void language plpgsql security invoker set search_path = '' as $fn$
begin
  if target_day is null then raise invalid_parameter_value using message='missing day'; end if;
  perform pg_advisory_xact_lock(724510,target_day-date '2000-01-01');
  delete from public.fleet_daily where day=target_day;
  insert into public.fleet_daily(day,app_id,manufacturer,model,sdk_int,devices,counters)
  select target_day,g.app_id,g.manufacturer,g.model,g.sdk_int,g.devices,
    coalesce((select jsonb_object_agg(s.key,s.total) from (
      select e.key,sum(e.value::bigint) total from public.heartbeats h
      join public.devices d using(install_id) cross join lateral jsonb_each_text(h.counters) e
      where h.day=target_day and d.app_id=g.app_id and coalesce(d.manufacturer,'?')=g.manufacturer
        and coalesce(d.model,'?')=g.model and coalesce(d.sdk_int,0)=g.sdk_int group by e.key
    ) s),'{}'::jsonb)
  from (select d.app_id,coalesce(d.manufacturer,'?') manufacturer,coalesce(d.model,'?') model,
    coalesce(d.sdk_int,0) sdk_int,count(*) devices
    from public.heartbeats h join public.devices d using(install_id)
    where h.day=target_day group by 1,2,3,4) g;
end $fn$;

create function public.ringkit_purge_rows(p_max_age_days integer)
returns jsonb language plpgsql security invoker set search_path = '' as $fn$
declare removed_incidents bigint; removed_heartbeats bigint; cutoff timestamptz;
begin
  if p_max_age_days is null or p_max_age_days<0 then raise invalid_parameter_value using message='invalid retention'; end if;
  cutoff := now()-make_interval(days=>p_max_age_days);
  delete from public.incidents where server_ts<cutoff;
  get diagnostics removed_incidents = row_count;
  delete from public.heartbeats where received_at<cutoff;
  get diagnostics removed_heartbeats = row_count;
  return jsonb_build_object('incidents',removed_incidents,'heartbeats',removed_heartbeats);
end $fn$;

create function public.ringkit_expired_log_objects(p_max_age_days integer,p_limit integer)
returns table(name text) language plpgsql security invoker set search_path = '' as $fn$
begin
  if p_max_age_days is null or p_max_age_days<0 or p_limit is null or p_limit<1 or p_limit>1000 then
    raise invalid_parameter_value using message='invalid retention batch';
  end if;
  return query select o.name from storage.objects o
    where o.bucket_id='ringkit-logs' and o.created_at<now()-make_interval(days=>p_max_age_days)
    order by o.created_at,o.name limit p_limit;
end $fn$;

-- No public-client policies. Edge Functions/dashboard use service_role, whose
-- standard Supabase BYPASSRLS privilege is required. RPCs need no definer rights.
do $acl$
declare object_name text; signature text;
begin
  foreach object_name in array array['devices','fleet_config','fleet_keys','issues','issue_devices','incidents','heartbeats','fleet_daily'] loop
    execute format('alter table public.%I enable row level security',object_name);
    execute format('revoke all on public.%I from public,anon,authenticated',object_name);
    execute format('grant select,insert,update,delete on public.%I to service_role',object_name);
  end loop;
  foreach signature in array array[
    'ringkit_register(uuid,text,text,text,integer,text,text,text,text)',
    'ringkit_checkin_gate(uuid,text,bigint,boolean)',
    'ringkit_record_incident(uuid,text,text,text,text[],text,timestamptz,text,text,bigint)',
    'ringkit_record_heartbeat(uuid,date,jsonb,jsonb)', 'ringkit_pull_landed(uuid,integer,integer)',
    'ringkit_rollup_day(date)', 'ringkit_purge_rows(integer)', 'ringkit_expired_log_objects(integer,integer)'
  ] loop
    execute 'revoke all on function public.'||signature||' from public,anon,authenticated';
    execute 'grant execute on function public.'||signature||' to service_role';
  end loop;
end $acl$;
grant usage on schema public to service_role;
-- A pre-existing bucket is a collision too: never change another bucket's access.
insert into storage.buckets(id,name,public) values('ringkit-logs','ringkit-logs',false);
commit;
