-- Minimal test-only schema for the checked-in migrations, NOT a backend bootstrap.
create role anon;
create role authenticated;
create role service_role;
create table devices (
  install_id uuid primary key,
  device_token uuid not null default gen_random_uuid(),
  device_label text, manufacturer text, model text, sdk_int integer,
  app_version text, plugin_version text,
  last_seen timestamptz not null default now(),
  log_level text default 'error', pull_requested boolean default false,
  sampled boolean default false, budget_kb integer default 200,
  killed boolean default false
);
create table fleet_config (key text primary key, value text);
create table fleet_daily (
  day date, manufacturer text, model text, sdk_int integer,
  devices bigint, counters jsonb,
  primary key (day, manufacturer, model, sdk_int)
);
create table heartbeats (install_id uuid, day date, counters jsonb);
