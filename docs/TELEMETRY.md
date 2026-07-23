# ringkit fleet telemetry — wire contract

Telemetry is **off unless the app configures it** (`RingKitConfig.telemetry`). When enabled, a
device ships only three things: a daily heartbeat, deduplicated incident reports, and — only
when an operator requests it — a full log-buffer pull. Logs otherwise stay on the device.
Uploads never use FCM or any high-priority channel and never run on the call-show path.

## Endpoint

```
POST {endpoint}/functions/v1/checkin
Content-Type: application/json
Content-Encoding: gzip            (optional but recommended)
Authorization: Bearer <fleet apiKey>      for kind=register
Authorization: Bearer <device_token>      for every other kind
```

Each app registers with its **own** fleet apiKey; the key determines the app the device belongs to
(see "Multiple apps" below). One backend can serve many apps this way.

## Request

```jsonc
{ "v": 1, "kind": "register|heartbeat|incident|user_report|pull",
  "install_id": "<uuid>", "device_wall_ms": 0, "boot_count": 3,
  "device": { "label": "…", "manufacturer": "…", "model": "…", "sdk_int": 34,
              "app_version": "…", "plugin_version": "…",
              "package_name": "com.acme.app" },                     // register (+ heartbeat refresh)
  "heartbeat": { "day": "2026-07-22", "counters": {…}, "fingerprints": {…}, "dropped_logs": 0 },
  "incident":  { "fingerprint": "…", "incident_type": "…", "codepoint": "…",
                 "degradations": ["fgsDenied"], "call_id": "…", "device_ts_ms": 0,
                 "records_gzip_b64": "…" },                          // also used by user_report
  "pull":      { "records_gzip_b64": "…", "part": 1, "of": 1 } }
```

Unknown **fields** are ignored (forward compatibility); unknown **kinds** are a 400.

## Response (every kind — the fleet steering channel)

```jsonc
{ "device_token": "…",            // register only (stable across re-registers)
  "log_level": "info",            // none|error|info|verbose — apply immediately
  "pull_buffer": false,           // true = operator wants the full buffer next
  "sampled": false,
  "budget_kb": 200,               // per-device daily upload budget
  "kill": false }                 // true = stop all telemetry traffic (sink keeps writing locally)
```

Incident responses add `"duplicate": true|false` (idempotent-retry detection).

## Failure semantics

| Status | Meaning | Client behavior |
|---|---|---|
| 401 | bad apiKey / device token | re-register once, then back off |
| 413 | daily budget exceeded (body still returns the knobs) | keep files on device; retry after midnight |
| 429 / 5xx | transient | exponential backoff; files stay on device — nothing is ever lost |

Incident uploads are idempotent per (install_id, fingerprint, device_ts): a retried upload is
acknowledged (`duplicate: true`) without a second stored payload. Pulls are exempt from the
budget (they are operator-initiated) and clear the `pull_buffer` flag when the last part lands.

## Client behavior (what the plugin's uploader actually does)

- **Registration** happens lazily before the first upload of any kind; the token persists on
  the device (never backed up, install-fenced — a cloned image re-registers cleanly).
- **Incidents** ship once per fingerprint per device per UTC day, via an expedited WorkManager
  one-shot. Repeat occurrences stay local and reach the server as heartbeat fingerprint deltas.
  A snapshot that could not ship (offline, budget) is retried by the daily heartbeat's
  reconciliation sweep — nothing is lost, arrival is just delayed.
- **Heartbeats** run in one daily periodic WorkManager slot. Missed days ship as separate
  catch-up day-buckets (up to 7 kept) oldest-first; the newest beat carries the fingerprint
  deltas and `dropped_logs`. Same-day re-beats replace that day's counters server-side.
- **Pulls** upload the entire on-device buffer, chunked into parts; Wi-Fi is preferred for a
  couple of backoff rounds, then the pull ships on metered anyway. `user_report` ("Report
  issue now") ships the past ~30 minutes under the fixed fingerprint `user-report`; flag a
  pull when you need the whole buffer.
- **Knobs apply atomically on every response**: `log_level` switches the device's capture
  level immediately (no reinstall), `budget_kb` overrides the configured budget, `kill: true`
  cancels all telemetry work and stops every data upload while local capture keeps running.
  A killed device still sends one ~250 B `register` knob poll per day — that is the only way
  `kill: false` can ever reach it.
- **Privacy**: caller names/handles/avatar URLs are redacted in uploads unless the effective
  level is `verbose` (server override, else the configured upload level); the apiKey and
  device token never appear in any log or payload records at any level.

## Server side (summary)

- Postgres tables: `devices` (identity + `app_id` + `package_name` + knobs + budget), `fleet_keys`
  (one row per app: `api_key → app_id`), `issues` (one row per unique failure fingerprint),
  `issue_devices`, `incidents` (payload index), `heartbeats`, `fleet_daily` (nightly roll-up for
  heat-maps, keyed by `app_id` + device attributes). Payloads live in the private `ringkit-logs`
  storage bucket (`incidents/{install_id}/…`, `pulls/{install_id}/…`), gzipped JSONL.
- Retention: 60 days for incidents/heartbeats/payload objects (nightly purge); heat-map
  roll-ups are kept.
- All tables are deny-by-default (no anonymous access); only the edge functions (service role)
  and the dashboard can touch data.

## Multiple apps (per-app keys)

The backend is multi-app: every device carries an `app_id`, and `app_id` is decided by the **fleet
key the app registered with** (in `fleet_keys`), not by anything the client sends — so an app can't
masquerade as another. Give each app its own key:

```sql
-- mint a key for a new app (any opaque random string)
insert into fleet_keys (api_key, app_id, label)
values ('<random-key>', 'acme-driver-app', 'Acme Driver — prod');
-- revoke one app without touching the others
update fleet_keys set active = false where api_key = '<random-key>';
```

Hand that app's developer *its* key + the endpoint. Every device running that app then reports under
`app_id = 'acme-driver-app'`, so all the dashboard queries below filter/group by app with a
`join fleet_keys` or a `where app_id = …`. A common key across apps still works (they'd share one
`app_id`) but you lose per-app attribution, budgeting, and revocation — prefer one key per app.
`device.package_name` is stored next to `app_id` as a self-reported cross-check (flag mismatches).

## Cost model

Per device per day: one ~1 KB heartbeat + incidents only on failure (deduped, ≤ once per
fingerprint per day, hard `budget_kb` cap). Ingress is free; retention is bounded. In practice
a 50k-device fleet fits inside a single Supabase Pro plan.

**Why tiered, not naive log shipping.** A naive "ship every device's rotating buffer" design at
50k devices, ~5 MB/device/day, is ~250 GB/day (~7.5 TB/month) of ingest and storage — orders of
magnitude past a hobby-tier bill, and 99.9% of it is healthy-path noise no one reads. The tiered
protocol inverts that: **logs stay on the device; only signals ship.**

| | Naive (ship everything) | Tiered (this design) |
|---|---|---|
| Steady-state upload / device / day | ~5 MB | ~1 KB heartbeat (+ incidents only on failure) |
| 50k-device fleet / month | ~7.5 TB | ~1.5 GB + deduped incidents |
| One bug hitting 10k devices | 10k log streams | 1 `issues` row + counters, ≤ 10k small payloads (once/device/day), most days 0 |
| Full detail for a specific device | always paid for | pulled on demand (operator flags the install) |

The full buffer is never gone — it is one `pull_buffer` flag away for any device that needs a
deep look. That is the whole trade: pay bytes for **signal** continuously, pay bytes for **detail**
only when a human asks.

**Upgrade path (if full-fleet log shipping is ever wanted).** The wire contract is backend-agnostic
— two JSON shapes over HTTP. To move off Supabase without touching a single client:
- **Cloudflare Workers + R2** — cheapest at scale; the Worker validates the token, writes gzipped
  JSONL to R2, keeps the metadata tables in D1/Postgres. Same request/response shapes.
- **Loki / ClickHouse** — if you want full-text log *search* across the fleet rather than
  pull-on-demand; point the ingest function at the store and keep the dedup/heartbeat tables for
  the dashboard. Higher cost, much richer query.

## Tester onboarding

For community testers running a telemetry-enabled build:

- **Enabling.** In the app's telemetry settings, enter the **endpoint** (the Supabase project URL),
  the **fleet apiKey** (from whoever runs the backend — treat it like a password; it is stored only
  on your device), and a **device label**. Tap Enable. The switch is off by default and the app
  discloses what it collects before you turn it on.
- **Device label conventions.** Use something that identifies the hardware and the person at a
  glance in the dashboard: `ada-pixel8-a14`, `sam-miui-redmi12`, `qa-oneplus-lab3`. Manufacturer +
  model + OS quirk beats a bare name — the whole point is filtering "didn't ring on a Xiaomi" by
  label. Keep it stable across reinstalls so one device stays one story.
- **"Report issue now" etiquette.** Press it **right after** you reproduce a problem, while the
  relevant events are still in the on-device buffer (it ships roughly the last half hour). One press
  per incident is enough — it flushes immediately; mashing it just re-sends overlapping slices and
  eats your daily budget. Note the rough time and what you did so the operator can line it up with
  the upload. If an operator asks for "the whole buffer," they will flag your device and it uploads
  on the next check-in — you do not need to do anything.
- **Privacy.** At the default level, caller names and numbers are redacted before anything leaves
  the device. Only a per-device `verbose` opt-in (set by the operator, for consenting testers)
  includes them, and the tester build says so. Local capture always runs; disabling telemetry only
  stops uploads. Data is retained for 60 days.

## Appendix — dashboard queries

Saved SQL for the Studio SQL editor (read-only; the service role and dashboard are the only
readers). Substitute the bracketed values.

```sql
-- One device's story in a time window (skew-corrected: server_ts is authoritative).
select i.server_ts, i.kind, i.call_id, s.incident_type, s.codepoint, s.degradations, i.payload_path
from incidents i
  join devices d on d.install_id = i.install_id
  left join issues s on s.fingerprint = i.fingerprint
where d.device_label = '[label]'
  and i.server_ts between '[from]' and '[to]'
order by i.server_ts desc;

-- Full narrative for one call across every device that saw it.
select d.device_label, i.server_ts, i.kind, i.fingerprint, i.payload_path
from incidents i join devices d on d.install_id = i.install_id
where i.call_id = '[call_id]'
order by i.server_ts;

-- Top open issues by how many distinct devices hit them (triage priority).
select fingerprint, incident_type, codepoint, degradations, device_count, occurrence_count, last_seen
from issues
where status = 'open'
order by device_count desc, occurrence_count desc
limit 50;

-- OEM × degradation heat-map from the nightly roll-up (no scan of raw rows).
select manufacturer, model, sdk_int, devices, counters
from fleet_daily
where day = '[day]'
order by devices desc;

-- Silent > 48 h: devices that checked in before but have gone quiet
-- (heartbeat gap = possible force-stop / OEM-killer population).
select device_label, manufacturer, model, sdk_int, last_seen
from devices
where last_seen < now() - interval '48 hours'
order by last_seen asc;
```
