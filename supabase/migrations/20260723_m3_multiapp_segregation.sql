-- SCOPE-4 M3 (PLAN v1.3.15): multi-app segregation — per-app fleet keys +
-- app_id/package_name on devices. Additive + backward-compatible: the legacy
-- single fleet key is migrated into fleet_keys as app_id='default', so every
-- already-registered device and any handed-out key keeps working. Applied to
-- project nqfqrdkcgibeqlpmpnko on 2026-07-23 via the Supabase MCP (the M0/M1
-- base schema was likewise applied via MCP; this file records the delta).

-- 1. App-identity columns (app_id = authoritative from the key; package_name = client self-report).
alter table devices add column if not exists app_id text;
alter table devices add column if not exists package_name text;

-- 2. Per-app keys: the key an app registers with determines its app_id.
create table if not exists fleet_keys (
  api_key    text primary key,
  app_id     text not null,
  label      text,
  active     boolean not null default true,
  created_at timestamptz not null default now()
);
alter table fleet_keys enable row level security; -- deny-all: service role / edge fns only

-- 3. Migrate the existing fleet key so nothing in the field breaks.
insert into fleet_keys (api_key, app_id, label)
select value, 'default', 'migrated M0 fleet key'
from fleet_config where key = 'api_key'
on conflict (api_key) do nothing;

-- 4. Backfill devices that registered under the migrated key.
update devices set app_id = 'default' where app_id is null;

-- 5. ringkit_register now persists app_id + package_name.
drop function if exists ringkit_register(uuid, text, text, text, integer, text, text);
create function ringkit_register(
  p_install uuid, p_label text, p_manufacturer text, p_model text,
  p_sdk integer, p_app_version text, p_plugin_version text,
  p_app_id text default null, p_package_name text default null
) returns jsonb language plpgsql security definer set search_path to 'public' as $fn$
declare d devices%rowtype;
begin
  insert into devices (install_id, device_label, manufacturer, model, sdk_int, app_version, plugin_version, app_id, package_name)
  values (p_install, coalesce(p_label,'unlabeled'), p_manufacturer, p_model, p_sdk, p_app_version, p_plugin_version, p_app_id, p_package_name)
  on conflict (install_id) do update
    set device_label = coalesce(p_label, devices.device_label),
        manufacturer = coalesce(p_manufacturer, devices.manufacturer),
        model = coalesce(p_model, devices.model),
        sdk_int = coalesce(p_sdk, devices.sdk_int),
        app_version = coalesce(p_app_version, devices.app_version),
        plugin_version = coalesce(p_plugin_version, devices.plugin_version),
        app_id = coalesce(p_app_id, devices.app_id),
        package_name = coalesce(p_package_name, devices.package_name),
        last_seen = now()
  returning * into d;
  return jsonb_build_object(
    'device_token', d.device_token, 'log_level', d.log_level,
    'pull_buffer', d.pull_requested, 'sampled', d.sampled,
    'budget_kb', d.budget_kb, 'kill', d.killed);
end $fn$;
-- Supabase grants EXECUTE to anon+authenticated explicitly on new public functions,
-- so a bare `from public` is NOT enough — revoke all three (advisor-verified: without
-- anon/authenticated the register RPC is callable unauthenticated via /rest/v1/rpc).
revoke all on function ringkit_register(uuid,text,text,text,integer,text,text,text,text) from public, anon, authenticated;

-- 6. fleet_daily gains an app_id dimension for per-app heat-maps.
alter table fleet_daily add column if not exists app_id text not null default 'default';
alter table fleet_daily drop constraint fleet_daily_pkey;
alter table fleet_daily add primary key (day, app_id, manufacturer, model, sdk_int);

create or replace function ringkit_rollup_day(target_day date)
returns void language sql security definer set search_path to 'public' as $fn$
  insert into fleet_daily (day, app_id, manufacturer, model, sdk_int, devices, counters)
  select g.day, g.app_id, g.manufacturer, g.model, g.sdk_int, g.devices, c.counters
  from (
    select h.day,
           coalesce(d.app_id, 'default')  as app_id,
           coalesce(d.manufacturer, '?')  as manufacturer,
           coalesce(d.model, '?')         as model,
           coalesce(d.sdk_int, 0)         as sdk_int,
           count(distinct h.install_id)   as devices
    from heartbeats h
    join devices d using (install_id)
    where h.day = target_day
    group by 1, 2, 3, 4, 5
  ) g
  cross join lateral (
    select coalesce(jsonb_object_agg(k, v), '{}'::jsonb) as counters
    from (
      select e.key as k, sum(e.value::bigint) as v
      from heartbeats h2
      join devices d2 using (install_id)
      cross join lateral jsonb_each_text(h2.counters) e
      where h2.day = g.day
        and coalesce(d2.app_id, 'default')  = g.app_id
        and coalesce(d2.manufacturer, '?')  = g.manufacturer
        and coalesce(d2.model, '?')         = g.model
        and coalesce(d2.sdk_int, 0)         = g.sdk_int
      group by e.key
    ) sums
  ) c
  on conflict (day, app_id, manufacturer, model, sdk_int) do update
    set devices = excluded.devices, counters = excluded.counters;
$fn$;
