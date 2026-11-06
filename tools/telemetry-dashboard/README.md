# ringkit fleet — telemetry administrator dashboard

A password-protected web dashboard for **trusted fleet administrators**. It shows
daily call outcomes, device/Android failure heatmaps, grouped issues, captured log
timelines and missing check-ins across **all apps**. Browse devices and change
diagnostic controls (raise a device's capture level, request a full-buffer pull).
It runs as a single **Netlify Function** that reads Supabase with
the service role — so the powerful key stays server-side and never reaches the browser.

The password grants **all-app administrator access**, including listing, creating,
renaming, revoking and deleting fleet API keys. App filters are not authorization
boundaries. Do not give this password to tenant/app developers who should only see
their own app. Device tokens and the service-role key are not returned to the browser;
the kill switch stays an action in Supabase Studio.

The function is **zero-dependency** (it talks to Supabase over plain REST with `fetch`),
so it deploys by drag-and-drop with no build step.

## What you need

- A **Netlify account** (free tier is fine).
- A telemetry backend in your own project; follow [Supabase setup](../../docs/SUPABASE_SETUP.md)
  first. Existing deployments should review the [upgrade notes](../../docs/TELEMETRY.md#backend-deployment-status).
- Your Supabase **service_role key**: Supabase dashboard → your project → *Settings →
  API → Project API keys → `service_role` → Reveal*. This is secret (full DB access);
  it only ever lives in Netlify's server-side environment.

## Deploy (drag-and-drop a zip — no CLI)

1. Zip **the contents** of this folder (so `netlify.toml` is at the zip root):
   `netlify.toml`, `netlify/`, `public/`, `package.json`.
   (Or run `tools/telemetry-dashboard/make-zip.sh` — it writes `ringkit-dashboard-netlify.zip`.)
2. Netlify → *Sites* → drag the zip onto the **"Deploy manually / drag and drop"** area.
   Netlify creates a site and deploys the function (nothing to install — zero deps).
3. Open the new site → *Site configuration → Environment variables* → add:
   - `SUPABASE_URL` = `https://<your-project-ref>.supabase.co`
   - `SUPABASE_SERVICE_ROLE_KEY` = your Supabase **service_role** key
4. **Redeploy once** (Deploys → *Trigger deploy*, or re-drop the zip) so the function
   picks up the new environment variables.

## Deploy (Netlify CLI — alternative)

```bash
cd tools/telemetry-dashboard
npx netlify-cli login
npx netlify-cli env:set SUPABASE_URL "https://<your-project-ref>.supabase.co"
# Set SUPABASE_SERVICE_ROLE_KEY in Netlify's protected server-side environment UI.
# Avoid placing real credentials in shell history or deployment logs.
npx netlify-cli deploy --prod
```

## Signing in

Open the deployed URL and enter the **dashboard password**. It's stored server-side in
the Supabase `fleet_config` table under `key='dashboard_password'`. To rotate it:

```sql
insert into public.fleet_config (key, value)
values ('dashboard_password',
  replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''))
on conflict (key) do update set value = excluded.value
returning value;
```

Share access only with trusted fleet administrators. Anyone with the password can read
all fleet data and manage every app's keys. Use a strong unique password and review
deployment access separately from app-key distribution.

## Notes

- **Server-side configuration** — never commit secrets. `SUPABASE_URL` +
  `SUPABASE_SERVICE_ROLE_KEY` live in Netlify; the dashboard password lives in
  `fleet_config`.
- **Reads with the service role**, which bypasses the deny-all RLS — that's why the key
  must stay in the Netlify function and never in a browser/static file.
- **Multi-app**: use the *all apps* filter or pick one `app_id`. Every device shows its
  `app_id` + `package_name`.
- Capture-level changes and full-buffer pulls can expose sensitive diagnostic data;
  use them only with appropriate tester consent. Key deletion/revocation can disrupt
  registration. The kill switch and budgets are not exposed here.

## Reading the analytics

- **Overview:** select an app and 7, 30 or 60 days, or a custom range up to 60 days.
  Trends and the manufacturer/model/Android API heatmap use completed UTC days.
  Heartbeat rollups arrive asynchronously: a gap means no rollup was reported.
  Refresh after late data is rolled up. Heartbeat retry behavior can also repeat
  counters; these reports are operational telemetry, not billing records.
- **Outcomes:** accepts do not prove media connected. Daily counters are events,
  not distinct calls or a cohort. Show-failure rate is failures divided by shows
  plus failures; degradation events are separate and can occur several times for
  one call. Reporting device-days summed across dates are not unique devices.
- **Issues:** the all-app view shows fleet-wide occurrences and affected devices.
  Selecting an app counts only its affected devices. Per-app occurrences are
  unavailable in the current schema and are displayed as unavailable; issue dates
  remain fleet-wide.
- **Timeline:** search retained incident/pull uploads by app, install UUID, exact
  metadata call ID and upload date. A payload can contain several calls; the exact
  call-ID filter inside the log applies to individual records. Records are ordered
  by boot count and elapsed time where available. This is captured diagnostic
  context, not a complete history of healthy calls.
- **Missing check-ins:** choose 24, 48, 72 or 168 hours since last contact. This does
  not determine whether a device is offline, uninstalled or affected by OEM policy.
  Existing capture and pull controls remain available from a device.

Devices, check-ins and upload timelines have 200-row pages. Aggregate reads stop
at 20,000 rows and issues display at most 200 groups; an explicit warning marks
partial reports. Narrow the app/date selection when a cap is reached. Very large
fleets may need database-side aggregates to avoid these bounds. Log downloads are
limited to 4 MiB compressed and 6 MiB decompressed; oversized requests are rejected.
At most the latest 10,000 JSONL records are returned, with a visible truncation
notice, and the viewer initially renders 500 records with a load-more control.

Analytics uses the existing telemetry tables and private log bucket; it requires
no database migration or mobile SDK update. Alert delivery is not configured.

## Offline regression tests

Run `node --test tools/telemetry-dashboard/*.test.mjs` from the repository root.
Tests use synthetic credentials and do not contact a backend. They cover
authentication, app scope, UTC ranges, pagination and truncation, clock ordering,
private-bucket path validation, bounded payloads and browser navigation races.
