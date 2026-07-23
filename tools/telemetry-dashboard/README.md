# ringkit fleet — telemetry debug dashboard

A password-protected web dashboard over the ringkit telemetry backend, for you and
your app developers to debug field issues. It shows **all apps**: browse devices,
drill into a device's incidents, read the decompressed black-box log per incident,
and trigger the two safe debug knobs (raise a device's capture level, request a
full-buffer pull). It runs as a single **Netlify Function** that reads Supabase with
the service role — so the powerful key stays server-side and never reaches the browser.

It deliberately does **not** expose the fleet API keys, device tokens, or the kill
switch (kill stays an admin action in Supabase Studio).

## What you need

- A **Netlify account** (free tier is fine).
- Your Supabase **service_role key**: Supabase dashboard → your project → *Settings →
  API → Project API keys → `service_role` → Reveal*. This is secret (full DB access);
  it only ever lives in Netlify's server-side environment.

## Deploy (Netlify CLI — quickest)

```bash
cd tools/telemetry-dashboard
npm install
npx netlify-cli login                 # opens a browser once
npx netlify-cli deploy --build --prod # creates/links a site and deploys
```

Then set the two environment variables (Netlify UI → *Site configuration → Environment
variables*, or the CLI below) and redeploy:

```bash
npx netlify-cli env:set SUPABASE_URL "https://nqfqrdkcgibeqlpmpnko.supabase.co"
npx netlify-cli env:set SUPABASE_SERVICE_ROLE_KEY "<your service_role key>"
npx netlify-cli deploy --build --prod
```

## Deploy (Netlify UI — from Git)

1. Push this repo to GitHub (or connect it).
2. Netlify → *Add new site → Import an existing project* → pick the repo.
3. Set **Base directory** to `tools/telemetry-dashboard`.
4. Add the two environment variables above under *Environment variables*.
5. Deploy. Netlify installs `@supabase/supabase-js` and serves the function.

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
