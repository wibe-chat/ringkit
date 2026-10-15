# ringkit fleet — telemetry administrator dashboard

A password-protected web dashboard for **trusted fleet administrators**. It shows **all apps**: browse devices,
drill into a device's incidents, read the decompressed black-box log per incident,
and change diagnostic controls (raise a device's capture level, request a
full-buffer pull). It runs as a single **Netlify Function** that reads Supabase with
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

## Offline regression tests

Run `node --test tools/telemetry-dashboard/dashboard.test.mjs` from the repository root.
Tests use synthetic credentials and do not contact a backend.
