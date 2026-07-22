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
Authorization: Bearer <apiKey>            for kind=register
Authorization: Bearer <device_token>      for every other kind
```

## Request

```jsonc
{ "v": 1, "kind": "register|heartbeat|incident|user_report|pull",
  "install_id": "<uuid>", "device_wall_ms": 0, "boot_count": 3,
  "device": { "label": "…", "manufacturer": "…", "model": "…", "sdk_int": 34,
              "app_version": "…", "plugin_version": "…" },          // register (+ heartbeat refresh)
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

- Postgres tables: `devices` (identity + knobs + budget), `issues` (one row per unique failure
  fingerprint), `issue_devices`, `incidents` (payload index), `heartbeats`, `fleet_daily`
  (nightly roll-up for heat-maps). Payloads live in the private `ringkit-logs` storage bucket
  (`incidents/{install_id}/…`, `pulls/{install_id}/…`), gzipped JSONL.
- Retention: 60 days for incidents/heartbeats/payload objects (nightly purge); heat-map
  roll-ups are kept.
- All tables are deny-by-default (no anonymous access); only the edge functions (service role)
  and the dashboard can touch data.

## Cost model

Per device per day: one ~1 KB heartbeat + incidents only on failure (deduped, ≤ once per
fingerprint per day, hard `budget_kb` cap). Ingress is free; retention is bounded. In practice
a 50k-device fleet fits inside a single Supabase Pro plan.
