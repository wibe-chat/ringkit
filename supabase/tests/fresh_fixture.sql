-- SQL-only stand-ins for Supabase-managed roles and Storage metadata.
-- This fixture does not emulate Storage HTTP, PostgREST, RLS policies or cron.
do $$ begin
  if not exists (select from pg_roles where rolname='anon') then create role anon; end if;
  if not exists (select from pg_roles where rolname='authenticated') then create role authenticated; end if;
  if not exists (select from pg_roles where rolname='service_role') then create role service_role bypassrls; end if;
end $$;
alter role service_role bypassrls;
create schema storage;
create table storage.buckets (id text primary key, name text not null, public boolean not null default false);
create table storage.objects (id uuid primary key default gen_random_uuid(), bucket_id text, name text, created_at timestamptz default now());
grant usage on schema storage to service_role;
grant select on storage.objects to service_role;
