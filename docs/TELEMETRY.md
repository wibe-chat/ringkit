# ringkit fleet telemetry — wire contract

Telemetry is **off unless the app configures it** (`RingKitConfig.telemetry`). When enabled, a
device sends registration/recovery polls, a daily heartbeat, deduplicated incident reports,
user-requested reports, and — only when an operator requests it — a full log-buffer pull.
An explicitly enrolled installation can also receive bounded diagnostic-session requests. Logs otherwise stay on the device.
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

## Version 1 request

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
  "kill": false }                 // true = stop data uploads; daily recovery poll remains
```

Incident responses add `"duplicate": true|false` (idempotent-retry detection).

## Failure semantics

| Status | Meaning | Client behavior |
|---|---|---|
| 401 | missing/bad bearer token or registration belonging to another app | re-register once, then back off |
| 413 | daily budget exceeded (body still returns the knobs) | keep files on device; retry after midnight |
| 413 | decompressed request exceeds 3 MiB (`error: "bad body"`) | reduce/chunk payload; retrying the same body will not help |
| 429 / 5xx | transient | exponential backoff; files stay on device subject to local retention limits |

Incident uploads are idempotent per (install_id, fingerprint, device_ts): a retried upload is
acknowledged (`duplicate: true`) without a second stored payload. Pulls are exempt from the
budget (they are operator-initiated) and clear the `pull_buffer` flag when the last part lands. This legacy flag does not prove all parts arrived; use v2 for correlated receipts.

## Client behavior (what the plugin's uploader actually does)

- **Registration/metadata refresh** runs on native activation and foreground entry (coalesced over 30 seconds), or before an upload that lacks a token; the token persists on
  the device (never backed up, install-fenced — a cloned image re-registers cleanly).
- **Incidents** ship once per fingerprint per device per UTC day, via an expedited WorkManager
  one-shot. Repeat occurrences stay local and reach the server as heartbeat fingerprint deltas.
  A snapshot that could not ship (offline, budget) is retried by the daily heartbeat's
  reconciliation sweep, subject to bounded local retention; prolonged outages can lose older data.
  `RING_AUDIO_FAILED` reports an unrecoverable ringtone playback failure after the eligible
  engine ladder is exhausted. It includes bounded source-kind, failure, focus and ring-volume
  context, without the ringtone URI or raw playback exception message. Recovered fallbacks,
  intentional/policy silence and stopped or superseded calls do not create this incident.
  A player reporting that it started is not proof that sound was heard on the phone.
- **Heartbeats** run in one daily periodic WorkManager slot. Missed days ship as separate
  catch-up day-buckets (up to 7 kept) oldest-first; the newest beat carries the fingerprint
  deltas and `dropped_logs`. Same-day re-beats replace that day's counters server-side.
- **Pulls** upload the entire on-device buffer, chunked into parts; Wi-Fi is preferred for a
  couple of backoff rounds, then the pull ships on metered anyway. `user_report` ("Report
  issue now") ships the past ~30 minutes under the fixed fingerprint `user-report`; flag a
  pull when you need the whole buffer.
- **Knobs apply atomically on every response**: `log_level` switches the logcat/privacy
  level immediately (no reinstall), `budget_kb` overrides the configured budget, `kill: true`
  cancels all telemetry work and stops every data upload while local capture keeps running.
  A killed device still sends a small `register` knob poll per day — that is the only way
  `kill: false` can ever reach it.
- **Privacy**: caller names/handles/avatar URLs are redacted in uploads unless the effective
  level is `verbose` (server override, else the configured upload level); the apiKey and
  device token never appear in any log or payload records at any level.

## Version 2 diagnostics (SDK 0.2.1)

Deploy the additive `remote_diagnostics` migration and updated ingest before the dashboard.
New installations have `diagnostics_enabled=false`. Enroll selected test installations explicitly.
The SDK sends v2 kinds only after the server returns `telemetry_protocol: 2` and
`diagnostics_enabled: true`. A v2 SDK continues v1 uploads with an older backend; an older SDK
has no session capability. None of these repository changes deploy themselves.

The native device descriptor adds `sdk_build_id` (SHA-256 of packaged native sources/resources,
Gradle configuration, consumer rules and package manifest), `telemetry_protocol`, `capabilities`,
`host_version_code` and `host_debuggable`. It requires no Git checkout on the device. Capabilities
are `audio_failure`, `build_identity`, `diagnostic_session` and `correlated_pull`. Unknown identity
stays unknown. The debuggable flag does not establish Flutter build mode or a distinct real user.
Incident snapshots and frozen reports retain their original capture identity after app updates.

Enrolled apps check for commands on foreground entry, every five minutes while visible,
and every minute during an acknowledged session. Lifecycle timers enqueue ordinary constrained
work; they are not five-minute periodic WorkManager jobs. A session also schedules end-of-window
work and uploads after call completion. Native capture works without Dart. An offline,
force-stopped or restricted app can report late; a server request cannot guarantee waking it.

```jsonc
// Device-token authenticated; control has a separate 64 KiB/day allowance.
{ "v": 2, "kind": "control", "install_id": "<uuid>",
  "device": { /* native descriptor */ },
  "request_id": "<optional uuid>", "action": "acknowledged|collecting|failed",
  "diagnostic_status": [ /* up to 6 bounded upload attempt/status summaries */ ] }

// Response also includes ordinary knobs.
{ "status": "ok", "telemetry_protocol": 2, "diagnostics_enabled": true,
  "requests": [{ "id": "<uuid>", "kind": "diagnostic_session|full_buffer",
    "state": "offered", "duration_ms": 1800000, "max_calls": 20, "max_bytes": 524288 }],
  "request_states": [{ "id": "<recent terminal uuid>", "state": "received" }],
  "request_state": "acknowledged", "server_wall_ms": 0 }

{ "v": 2, "kind": "diagnostic_report", "install_id": "<uuid>",
  "report": { "request_id": "<uuid>", "report_id": "<uuid>", "part": 1, "of": 1,
    "identity": { /* immutable capture identity */ }, "records_gzip_b64": "...",
    "metadata": { "schema": 1, "final": true, "manifest": ["<report uuid>"],
      "boot": 3, "from_elapsed": 0, "to_elapsed": 0,
      "observed_call_count": 1, "truncated": false, "end_reason": "time_limit" } } }
```

A session admits at most **30 minutes, 20 calls or 512 KiB of decoded UTF-8 request bodies**.
Reports are durably frozen before sending; retries use the same request/report/part identity
and bytes. Deadlines use monotonic time and boot fencing. Cancellation, disablement, expiry or
reboot ends capture. A package update ends old capture as `build_changed`; only already-frozen
reports may ship with the old identity. Transient failures retain pending reports. Safe upload
status, including retry limits, is reported on a later successful control sync and remains separate
from a stored receipt. Activation/daily reconciliation resumes pending work. Session uploads
remain redacted even when local verbosity is higher.

An explicit retained-buffer request has a **5 MiB decoded-log / 8 MiB total-envelope** allowance.
Parts target 1 MiB of raw UTF-8 logs, below the 3 MiB decoded-body ingestion cap after gzip/base64.
Ordinary client budgets also count decoded request-body bytes. Explicit diagnostic allowances
do not raise the ordinary daily incident budget.

| State | What is known |
| --- | --- |
| queued | Waiting for contact; capture has not started. |
| offered | Command delivered, persistence/acknowledgement unconfirmed. |
| acknowledged | Device persisted the command; evidence can still be absent. |
| collecting | Partial evidence; final manifest or parts are pending. |
| received | Every manifest part was parsed and durably stored; inspect call coverage. |
| received_empty | Expected parts arrived but contained zero log records. |
| cancelled / expired / failed | Collection did not finish successfully; retained partial parts remain visible. |

Receipts are install/token bound, idempotent and serialized with cancellation/replacement.
They commit after Storage upload and parsing; counts are derived from records. An old completion
cannot clear a newer UUID request. Recent terminal states reconcile lost final HTTP responses.
Reassigning a device to another fleet app cancels its old active requests while retaining
historical ownership. Operator observations are not sent back to the device.

The dashboard separates registration, last control contact, payload receipt and captured build.
It discloses bounded history, missing parts, capture range and truncation. Call narratives separate
call IDs and boots and label legacy `audible=true` as an engine-start indication. Physical sound
stays unknown until a tester supplies an observation; no microphone capture is added. Access
remains for trusted all-app administrators, not a tenant-isolated developer portal.

## Server side (summary)

- Postgres tables: `devices` (identity + `app_id` + `package_name` + knobs + budget), `fleet_keys`
  (one row per app: `api_key → app_id`), `issues` (one row per unique failure fingerprint),
  `issue_devices`, `incidents` (payload index), `heartbeats`, `fleet_daily` (nightly roll-up for
  heat-maps, keyed by `app_id` + device attributes). Payloads live in the private `ringkit-logs`
  storage bucket (`incidents/{install_id}/…`, `pulls/{install_id}/…`, `diagnostics/{install_id}/…`), gzipped JSONL.
- Retention: 60 days for incidents/heartbeats/payload objects (nightly purge); heat-map
  roll-ups are kept.
- All tables are deny-by-default (no anonymous access); only the edge functions (service role)
  and the dashboard can touch data.
- In the fresh-project backend, automatic issues/device counts appear as soon as an incident
  arrives, but `occurrence_count` is updated by heartbeat fingerprint deltas, which already
  include the uploaded incident. It can be zero before the first heartbeat; counting both
  paths would double-count failures. Manual `user_report` occurrences count on upload.
  Heartbeat deltas have no request ID in the current protocol: a lost response followed by
  a retry can count a delta again. These totals are diagnostic estimates, not billing metrics.

## Backend deployment status

The Flutter plugin works without a telemetry backend. For a **new, dedicated project**,
follow [Use your own Supabase project](SUPABASE_SETUP.md). The isolated migration tree in
`supabase/fresh-project/supabase/` supplies the base tables, bookkeeping RPCs and private
Storage bucket; the guide covers function deployment, app keys, scheduling and verification.
Local tests do not replace validation of your hosted project's configuration and services.

The original `supabase/migrations/` directory contains **existing-deployment upgrades**,
not an empty-project baseline. Do not mix the two migration trees or treat the SQL test
fixtures as production schemas. Never apply the fresh baseline to an existing deployment.

For an existing compatible backend, review and deploy the registration-ownership migration,
updated `checkin` and `purge` functions, and dashboard together. The repository does not
automatically deploy any of them. Before exposing a deployment to external users:

- Verify base-schema/RLS/service-role privileges and storage access on your own project.
- Configure a strong, separate server-only **`RINGKIT_PURGE_SECRET`** for the purge function
  and its scheduler. Purge now rejects requests when this variable is absent, and never
  falls back to a client fleet key. Update existing cron HTTP Authorization headers to use
  the separate secret through your server-side secret store; otherwise retention stops.
  Never put this credential in apps, source, dashboard responses or logs.
- Verify that the deployed purge rejects client keys, and registration cannot move an
  existing install to another app or return that install's token to it.
- Restrict the dashboard to trusted **all-app administrators**. Its app selector is only a
  filter; its password also permits fleet-key management and sensitive log-buffer pulls.
- Review historical deployment logs and rotate credentials if exposure is suspected.

Purge is an operator-only endpoint: `POST /functions/v1/purge` with
`Authorization: Bearer <RINGKIT_PURGE_SECRET>`. Its optional body is
`{"max_age_days":60}`. A value of `0` deletes all eligible telemetry rows/payloads and
must never be used as a routine health check.

Offline regression commands (from the repository root):

```bash
deno test --config supabase/tests/deno.json --allow-env supabase/tests/security_test.ts supabase/tests/diagnostics_test.ts
deno check supabase/functions/checkin/index.ts supabase/functions/purge/index.ts
bash supabase/tests/registration.sh  # isolated PostgreSQL 16 container; requires Docker
bash supabase/tests/diagnostics.sh # isolated PostgreSQL; also supports PG_BIN
node --test tools/telemetry-dashboard/*.test.mjs
```

These tests use only synthetic credentials and never contact a live backend.

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
An existing installation is bound to its app: another app's key cannot reassign it or recover
its token. Same-app re-registration preserves the token and refreshes device metadata.
Fleet keys embedded in apps are registration credentials, not trustworthy proof of user or
device identity; protect the ingest service with appropriate abuse controls for your deployment.

## Cost model

Estimate requests, decoded bodies, storage, compute and egress from the actual fleet. Ordinary
traffic includes metadata refreshes, daily heartbeats and deduplicated incidents. Enrollment adds
foreground control checks; sessions add faster checks and bounded uploads. Explicit pulls can
upload several MiB. Measure at least 20 controlled command/report cycles before wider enrollment:
record latency percentiles, failed/empty reports and bytes per tester. The incident budget is not a
total backend-cost cap. Check [current Supabase pricing](https://supabase.com/pricing).

The rotating local buffer can overwrite old records. A pending pull neither preserves them
indefinitely nor proves collection. Open the app, wait for acknowledgement, test with unique call
IDs and verify a nonempty matching receipt.

## Tester onboarding

For community testers running a telemetry-enabled build:

- **Enabling.** In the app's telemetry settings, enter the **endpoint** (the Supabase project URL),
  the **fleet apiKey** (from whoever runs the backend — treat it like a password; it is stored only
  on your device), and a **device label**. Tap Enable. The switch is off by default and the app
  discloses what it collects before you turn it on.
- **Device label conventions.** Use something that identifies the hardware and the person at a
  glance in the dashboard: `ada-pixel8-a14`, `sam-miui-redmi12`, `qa-oneplus-lab3`. Manufacturer +
  model + OS quirk beats a bare name — the whole point is filtering "didn't ring on a Xiaomi" by
  label. A reinstall creates a new installation identity even if the label is reused.
- **"Report issue now" etiquette.** Press it **right after** you reproduce a problem, while the
  relevant events are still in the on-device buffer (it ships roughly the last half hour). One press
  per incident is enough — it queues a network-constrained upload; mashing it just re-sends overlapping slices and
  eats your daily budget. Note the rough time and what you did so the operator can line it up with
  the upload. If an operator asks for "the whole buffer," they will flag your device and it uploads
  on a subsequent eligible check-in. Open the app to prompt discovery and have the operator confirm receipt.
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
