# Use your own Supabase project

RingKit works without Supabase. This optional backend collects RingKit diagnostic
incidents, daily counters and explicitly requested log-buffer pulls; it is not a
call-media server or a general-purpose analytics SDK. Data goes to **your project**,
not a RingKit-operated service. Each app needs only a project URL and its RingKit
fleet registration key after the one-time backend setup below.

Use a **new, dedicated Supabase project** for these instructions. The fresh schema
is separate from the legacy upgrade migrations in `supabase/migrations/`. Never
apply both trees, and never apply the fresh baseline to an existing deployment.
Existing operators should follow [the upgrade notes](TELEMETRY.md#backend-deployment-status).

## 1. Create the project and database

Create a project in [Supabase](https://supabase.com/dashboard). Keep its database
password in your password manager. Record the project reference and project URL
(`https://YOUR_PROJECT_REF.supabase.co`); neither is an administrator credential.
Review current Supabase quotas and billing for your workload.

Clone this repository and run all commands below from the repository root. With
Node.js installed, authenticate the Supabase CLI using the account that owns your
new project:

```sh
npx supabase@latest login
npx supabase@latest --workdir supabase/fresh-project db push --project-ref YOUR_PROJECT_REF --dry-run
npx supabase@latest --workdir supabase/fresh-project db push --project-ref YOUR_PROJECT_REF
```

Check that the dry run lists **only the fresh telemetry baseline**. Supply the
database password through the CLI prompt, not in a committed file or command-line
argument. Do not run a plain `supabase db push` from the repository root: that
selects the legacy, delta-only migrations instead.

The baseline creates the telemetry tables, service-role-only bookkeeping RPCs and
the **private** `ringkit-logs` Storage bucket. It creates no fleet keys, dashboard
password, public access policy, scheduler or live function deployment. RLS is
enabled and ordinary `anon`/`authenticated` clients cannot read the telemetry.
Keep the Data API enabled with `public` exposed: the server functions use it with
service-role privileges. Do **not** grant anonymous access to make a client work.

## 2. Configure maintenance and deploy the functions

Generate a strong, unique random maintenance password in your password manager.
In your project's **Edge Functions → Secrets**, create `RINGKIT_PURGE_SECRET`
with that value. It must be different from every fleet key and dashboard password.
Never put it in Flutter, Git, screenshots or logs.

Deploy the two checked-in functions from the repository root:

```sh
npx supabase@latest functions deploy checkin --project-ref YOUR_PROJECT_REF --no-verify-jwt --use-api
npx supabase@latest functions deploy purge --project-ref YOUR_PROJECT_REF --no-verify-jwt --use-api
```

`--use-api` bundles remotely without local Docker. `--no-verify-jwt` is intentional:
these functions validate their **own** bearer credentials, not Supabase Auth JWTs.
`checkin` accepts a fleet key for registration, then an installation token for
uploads. `purge` accepts only the separate maintenance secret and rejects calls
when the secret is unset.

Hosted Supabase supplies `SUPABASE_URL` and the legacy
`SUPABASE_SERVICE_ROLE_KEY` used by these functions. These are server-side runtime
values, not app settings. See [Supabase function secrets](https://supabase.com/docs/guides/functions/secrets).

## 3. Create your app's RingKit key

In the project's SQL Editor, run this once, replacing the app ID and label:

```sql
insert into public.fleet_keys (api_key, app_id, label)
values (
  replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''),
  'my-android-app',
  'My Android app — production'
)
returning api_key, app_id;
```

Save the returned `api_key` privately. This is the **RingKit fleet key**. It is
**not** your Supabase publishable/anon key, service-role key, project ID, database
password or purge secret. Use a separate app ID/key for each application.

A mobile app key is extractable from the app; it permits registration, not
administration or database reads. Add deployment-appropriate rate/abuse controls
before a large public rollout. Per-device budgets alone do not prevent an attacker
from registering many installations. Treat all uploaded data as untrusted.

## 4. Configure Flutter

Add the following to your existing RingKit initialization, after the user has
opted into diagnostics. `config` is the `RingKitConfig` you already use for calls:

```dart
config.telemetry = RingKitTelemetryConfig(
  endpoint: 'https://YOUR_PROJECT_REF.supabase.co', // base URL, no /functions suffix
  apiKey: const String.fromEnvironment('RINGKIT_FLEET_KEY'),
  deviceLabel: 'test-device', // avoid personal names or phone numbers
  uploadLevel: RingKitLogLevel.info,
  dailyUploadBudgetKb: 200,
);
await RingKit.initialize(config);
```

Supply `RINGKIT_FLEET_KEY` through your private build configuration (for example,
an ignored `--dart-define-from-file=.env.ringkit.json` file). Build-time defines
are **not secret storage** in a distributed APK. Never use them for administrator
credentials. Do not print the config object or key. Use the same telemetry config
in every foreground/background initialization; initialization is last-writer-wins.
Set `config.telemetry = null` and initialize again to disable uploads.

The example app also has telemetry settings: enter the **base project URL**, the
**RingKit fleet key**, and a non-personal device label, then enable telemetry.
There is no need to install `supabase_flutter` or sign an app user into Supabase.

## 5. Schedule retention and rollups

Without this step, retained payloads keep accumulating and daily heatmaps are not
refreshed. In the Supabase Dashboard enable **Cron (`pg_cron`)** and **`pg_net`**,
then open **Vault** and create two named secrets:

| Vault name | Value |
| --- | --- |
| `ringkit_project_url` | Your base project URL |
| `ringkit_purge_secret` | The same value as the Edge Function's `RINGKIT_PURGE_SECRET` |

Keep those values out of the scheduler command itself. Run this in SQL Editor
as the project administrator:

```sql
select cron.schedule('ringkit-rollup', '10 0 * * *', $$
  select public.ringkit_rollup_day((now() at time zone 'utc')::date - n)
  from generate_series(1, 7) as n;
$$);

select cron.schedule('ringkit-purge', '30 0 * * *', $$
  select net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets
            where name = 'ringkit_project_url') || '/functions/v1/purge',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || (select decrypted_secret
        from vault.decrypted_secrets where name = 'ringkit_purge_secret')
    ),
    body := '{"max_age_days":60}'::jsonb,
    timeout_milliseconds := 30000
  );
$$);
```

Use UTC scheduling. The rollup revisits seven days to incorporate delayed
heartbeats. Device byte budgets reset lazily on the next authenticated check-in
after UTC midnight; no separate reset job is needed. Purge deletes old incident
and heartbeat rows and removes old payloads through the Storage API. Aggregate
issue and daily-rollup data are retained; this is not an all-data erasure policy.
Never delete `storage.objects` directly. Never use `max_age_days: 0` as a health
check: it is a destructive operator action.

Monitor both Cron history **and the HTTP response** in pg_net/Edge Function logs:
a successful enqueue does not prove purge returned HTTP 200. Resolve failures
and rerun the ordinary 60-day purge when necessary. Rotating the maintenance
secret requires updating both the function secret and Vault.
See [Supabase's scheduled-function guide](https://supabase.com/docs/guides/functions/schedule-functions).

## 6. Verify your deployment before distributing it

1. Confirm all eight telemetry tables have RLS enabled and `ringkit-logs` is
   private. Run the project's Supabase Security Advisor; inspect unexpected
   public table, function, view or Storage grants.
2. POST to `checkin` without authorization and to `purge` with a fleet key: both
   must return 401. Never test purge with a valid secret and a zero-day retention.
3. Enable telemetry in your own test app. Use its **Report issue now** action;
   confirm an installation under your app ID, an incident row, and a payload in
   the private bucket. Registration happens lazily, so enabling alone may not
   immediately create a row. Keep real credentials out of captured evidence.
4. Confirm the nightly rollup and 60-day purge complete successfully. Confirm
   an ordinary Supabase client cannot read tables or call bookkeeping RPCs.
5. Review consent and privacy disclosures. Default uploads redact caller PII;
   verbose capture and full-buffer pulls require special care and tester consent.

The repository has local PostgreSQL contract/privilege tests and offline function
tests. Those do **not** verify your hosted project's gateway, secrets, Storage
service or scheduled jobs. Complete these deployment checks on your own project.

For a web dashboard, follow the [administrator dashboard guide](../tools/telemetry-dashboard/README.md).
It is optional: SQL Editor and Storage already let you inspect your own data.
Dashboard access covers **all apps** and is only for trusted administrators.

## Local regression checks

From the repository root, see the runner's prerequisites before executing:

```sh
bash supabase/tests/fresh.sh
deno test --config supabase/tests/deno.json --allow-env supabase/tests/security_test.ts
node --test tools/telemetry-dashboard/dashboard.test.mjs
```

No test should use a production project or production credentials.
