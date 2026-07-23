# ringkit fleet — telemetry debug dashboard

A password-protected web dashboard over the ringkit telemetry backend, for you and
your app developers to debug field issues. It shows **all apps**: browse devices,
drill into a device's incidents, read the decompressed black-box log per incident,
and trigger the two safe debug knobs (raise a device's capture level, request a
full-buffer pull). It runs as a single **Netlify Function** that reads Supabase with
the service role — so the powerful key stays server-side and never reaches the browser.

It deliberately does **not** expose the fleet API keys, device tokens, or the kill
switch (kill stays an admin action in Supabase Studio).

The function is **zero-dependency** (it talks to Supabase over plain REST with `fetch`),
so it deploys by drag-and-drop with no build step.

## What you need

- A **Netlify account** (free tier is fine).
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
   - `SUPABASE_URL` = `https://nqfqrdkcgibeqlpmpnko.supabase.co`
   - `SUPABASE_SERVICE_ROLE_KEY` = your Supabase **service_role** key
4. **Redeploy once** (Deploys → *Trigger deploy*, or re-drop the zip) so the function
   picks up the new environment variables.

## Deploy (Netlify CLI — alternative)

```bash
cd tools/telemetry-dashboard
npx netlify-cli login
npx netlify-cli env:set SUPABASE_URL "https://nqfqrdkcgibeqlpmpnko.supabase.co"
npx netlify-cli env:set SUPABASE_SERVICE_ROLE_KEY "<your service_role key>"
npx netlify-cli deploy --prod
```

## Signing in

Open the deployed URL and enter the **dashboard password**. It's stored server-side in
the Supabase `fleet_config` table under `key='dashboard_password'`. To rotate it:

```sql
update fleet_config set value = encode(gen_random_bytes(18),'hex')
where key = 'dashboard_password' returning value;
```

Hand the URL + password to a developer and they can debug immediately — nothing else
to install on their side. Keep both private: anyone with them can read all fleet data.

## Notes

- **Env vars only** — no secrets are committed. `SUPABASE_URL` +
  `SUPABASE_SERVICE_ROLE_KEY` live in Netlify; the dashboard password lives in
  `fleet_config`.
- **Reads with the service role**, which bypasses the deny-all RLS — that's why the key
  must stay in the Netlify function and never in a browser/static file.
- **Multi-app**: use the *all apps* filter or pick one `app_id`. Every device shows its
  `app_id` + `package_name`.
- The two knobs (capture level, request pull) write to `devices`; they're reversible and
  safe. The kill switch and budgets are intentionally not exposed here.
