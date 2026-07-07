# Changelog

## Unreleased

Killed-state fast paths (device-verified, on `main`):

- **Mode 2 — native FCM fast path:** the plugin's own FCM receiver parses `rk.*` pushes natively and
  rings in **< 300 ms from killed**, with Dart never on the critical path. Enabled by one manifest
  line; your existing `firebase_messaging` handlers keep firing for every non-call message. Detects
  FCM priority downgrades (`fcmDowngraded`). See [docs/FCM.md](docs/FCM.md).
- **Headless background event handler:** `setBackgroundEventHandler` runs a top-level Dart function
  in a headless isolate for actionable events while the app is dead — a decline-while-dead reaches
  your server immediately instead of waiting for the next launch.
- `firebase_messaging` example wiring, `docs/FCM.md`, and the `AudioRouteChanged` event surface.

_In progress (not yet device-verified — do not enable): the telecom module (system audio routing,
Bluetooth/watch answer)._

## 0.2.0 — 2026-07-05

First integration-ready release: plan Phases 0–2 (Mode 1) complete and device-verified.

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

Not yet in this release (see README status table): headless background-handler execution and the
Mode 2 native FCM path (Phase 3), telecom (Phase 4), telemetry upload (config accepted
but inert).

## 0.0.1

Scaffold.
