\set ON_ERROR_STOP on
begin;
do $test$
declare
  original jsonb;
  recovered jsonb;
  rejected boolean := false;
  install constant uuid := '00000000-0000-4000-8000-000000000001';
begin
  original := ringkit_register(install, 'original', 'maker', 'model', 31, '1', '1', 'app-a', 'example.app.a');
  if original->>'device_token' is null then raise exception 'Registration returned no token'; end if;
  recovered := ringkit_register(install, 'updated', null, null, null, null, null, 'app-a', null);
  if original->>'device_token' <> recovered->>'device_token' then
    raise exception 'Same-app token recovery changed identity';
  end if;
  if (select device_label from devices where install_id = install) <> 'updated' then
    raise exception 'Same-app metadata update failed';
  end if;
  begin
    perform ringkit_register(install, 'attacker', null, null, null, null, null, 'app-b', 'example.app.b');
  exception when insufficient_privilege then rejected := true;
  end;
  if not rejected then raise exception 'Cross-app registration was allowed'; end if;
  if not exists (select 1 from devices where install_id = install and app_id = 'app-a'
      and device_label = 'updated' and package_name = 'example.app.a'
      and device_token::text = original->>'device_token') then
    raise exception 'Rejected request mutated the original installation';
  end if;
  rejected := false;
  begin
    perform ringkit_register(install, null, null, null, null, null, null, null, null);
  exception when insufficient_privilege then rejected := true;
  end;
  if not rejected then raise exception 'Null app bypassed ownership'; end if;
  if has_function_privilege('anon', 'ringkit_register(uuid,text,text,text,integer,text,text,text,text)', 'execute')
      or has_function_privilege('authenticated', 'ringkit_register(uuid,text,text,text,integer,text,text,text,text)', 'execute') then
    raise exception 'Untrusted roles can call registration directly';
  end if;
  if not has_function_privilege('service_role', 'ringkit_register(uuid,text,text,text,integer,text,text,text,text)', 'execute') then
    raise exception 'Service role cannot register';
  end if;
end $test$;
rollback;
