-- An installation's app is authoritative and immutable through registration.
-- The conflict predicate is checked while holding the row lock, including when
-- another transaction concurrently creates the same installation.
begin;
create or replace function public.ringkit_register(
  p_install uuid, p_label text, p_manufacturer text, p_model text,
  p_sdk integer, p_app_version text, p_plugin_version text,
  p_app_id text default null, p_package_name text default null
) returns jsonb language plpgsql security definer set search_path = '' as $fn$
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
        model = coalesce(p_model, devices.model),
        sdk_int = coalesce(p_sdk, devices.sdk_int),
        app_version = coalesce(p_app_version, devices.app_version),
        plugin_version = coalesce(p_plugin_version, devices.plugin_version),
        package_name = coalesce(p_package_name, devices.package_name),
        last_seen = now()
    where devices.app_id = excluded.app_id
  returning * into d;
  if not found then
    raise insufficient_privilege using message = 'unauthorized';
  end if;
  return jsonb_build_object(
    'device_token', d.device_token, 'log_level', d.log_level,
    'pull_buffer', d.pull_requested, 'sampled', d.sampled,
    'budget_kb', d.budget_kb, 'kill', d.killed);
end $fn$;
revoke all on function public.ringkit_register(uuid,text,text,text,integer,text,text,text,text) from public, anon, authenticated;
grant execute on function public.ringkit_register(uuid,text,text,text,integer,text,text,text,text) to service_role;
commit;
