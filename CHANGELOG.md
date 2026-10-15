# Changelog

## 0.2.0 — 2026-10-15

First pub.dev release. Includes the features from the earlier Git-only preview
and the additions below. The historical `v0.2.0` Git tag identifies the earlier
Git preview, not this package archive.

Public-preview preparation and telemetry hardening:

- Promote the opt-in telemetry implementation and Android compatibility repair together;
  telemetry remains disabled unless explicitly configured.
- Bind registration atomically to its existing app; add a service-role-only migration.
- Require a separate server-only `RINGKIT_PURGE_SECRET` for maintenance. Existing operators
  must update function configuration and scheduler credentials before deploying this change.
- Bound decompressed ingest requests and prevent dashboard failures from logging fleet keys.
- Declare the normal network-state permission used by telemetry's metered-network policy.
- Add a separately versioned fresh-project Supabase baseline and owner setup guide;
  keep legacy deployment upgrades separate and telemetry off by default.
- Add pub.dev publishing guidance and runtime-only archive exclusions; declare the
  generated Pigeon code's existing `meta` dependency directly.
- Free unused hosted-runner toolchains before the full Robolectric API sweep and
  collect test reports from their actual build output directory.

- Fix incoming-call screen crashes on Android 8–10 with compatible window and
  system-bar inset handling. Preserve lock-screen display and screen wake-up on
  Android 8.0 for both the incoming screen and the answer flow.

Killed-state fast paths (device-verified, on `main`):

- **Mode 2 — native FCM fast path:** the plugin's own FCM receiver parses `rk.*` pushes natively and
  rings in **< 300 ms from killed**, with Dart never on the critical path. Enabled by one manifest
  line; your existing `firebase_messaging` handlers keep firing for every non-call message. Detects
  FCM priority downgrades (`fcmDowngraded`). See [docs/FCM.md](https://github.com/wibe-chat/ringkit/blob/main/docs/FCM.md).
- **Headless background event handler:** `setBackgroundEventHandler` runs a top-level Dart function
  in a headless isolate for actionable events while the app is dead — a decline-while-dead reaches
  your server immediately instead of waiting for the next launch.
- `firebase_messaging` example wiring, `docs/FCM.md`, and the `AudioRouteChanged` event surface.

Telecom module (device-verified on API 26–36, including cold-process starts):

- **System Telecom integration**, opt-in via `telecomIntegration: true` or per-call
  `IncomingCall.useTelecom`: calls register with Telecom before any UI posts, so the CallStyle
  notification rings **even when the user has blocked notifications**.
- **Real audio routing:** `setAudioRoute(...)` drives the system endpoint change (honest
  `wrongState` when no telecom call is active) and `AudioRouteChanged` events fire on every
  route move (earpiece/speaker/Bluetooth/wired).
- **Hardware answer:** Bluetooth/watch/wired-headset answer buttons feed the exact same accept
  flow as a notification tap — one code path, one `CallAccepted` event.
- **Automatic fallback:** any Telecom registration/`addCall` failure silently falls back to
  normal non-telecom ringing (ringing is never blocked) and is reported via `diagnose()`.
- **Android 16 promoted ("Live Updates") ongoing notification:** the in-call notification
  requests promotion + shows a status-bar chip on API 36; below 36 nothing changes.

### Earlier Git-only preview — 2026-07-05

First integration-ready Git preview: Mode 1 complete and device-verified.

- **Core lifecycle** in every app state (foreground/background/killed/locked/Doze):
  `showIncomingCall` → ring + full-screen + CallStyle notification → accept / decline /
  timeout / cancel-push / `endCall` — a fully specified state machine, every cell generated-test-backed.
- **Killed-state delivery:** persisted event log with replay on next launch (`deliveryPath`
  badges), ack-gated effectively-once consumption, one-shot `consumeLaunchAction()`.
- **Multi-call:** waiting calls with FSI + attention cue, promotion, eviction at the
  `maxConcurrentRinging` cap, in-call etiquette.
- **Missed calls:** auto-posting with reason-appropriate copy, both tap targets auto-cancel and
  launch the app.
- **In-call:** `OngoingCallService` (CallStyle Connecting…/chronometer/Calling… surfaces,
  task-removal honesty, `manageOngoingCall:false` opt-out), `setCallConnected`,
  `startOutgoingCall`, `updateCallInfo`, connection watchdogs.
- **Honest results:** `ShowCallResult` degradation/failure reporting per the precondition matrix
  (fsiDenied, fgsDenied, channelDemoted, callStyleRejected, notificationsBlocked, …).
- **Permissions & diagnostics:** onboarding helpers, OEM restriction table + deep links,
  `diagnose()` fix-it report with ring traces.
- Robustness: reconciliation sweep, tombstones/pre-tombstones (out-of-order cancel), staleness
  guard (`expired`), backup-safe persistence (no ghost calls after a cloud restore), R8-safe resources.

That earlier preview did not yet include headless background-handler execution,
the Mode 2 native FCM path, telecom or telemetry uploads. These are included in
the first pub.dev release described above; telemetry remains opt-in.

## 0.0.1

Scaffold.
