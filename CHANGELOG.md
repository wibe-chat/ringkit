# Changelog

## 0.2.0 — 2026-07-05

First integration-ready release: plan Phases 0–2 (Mode 1) complete and device-verified.

- **Core lifecycle** in every app state (foreground/background/killed/locked/Doze):
  `showIncomingCall` → ring + full-screen + CallStyle notification → accept / decline /
  timeout / cancel-push / `endCall` — full §5.1 state machine, every cell generated-test-backed.
- **Killed-state delivery:** persisted event log with replay on next launch (`deliveryPath`
  badges), ack-gated effectively-once consumption, one-shot `consumeLaunchAction()`.
- **Multi-call** (§5.2): waiting calls with FSI + attention cue, promotion, eviction at the
  `maxConcurrentRinging` cap, in-call etiquette.
- **Missed calls:** auto-posting with reason-appropriate copy, both tap targets auto-cancel and
  launch the app.
- **In-call:** `OngoingCallService` (CallStyle Connecting…/chronometer/Calling… surfaces,
  task-removal honesty, `manageOngoingCall:false` opt-out), `setCallConnected`,
  `startOutgoingCall`, `updateCallInfo`, connection watchdogs.
- **Honest results:** `ShowCallResult` degradation/failure reporting per the §8.11 matrix
  (fsiDenied, fgsDenied, channelDemoted, callStyleRejected, notificationsBlocked, …).
- **Permissions & diagnostics:** onboarding helpers (§11.2), OEM restriction table + deep links
  (§12), `diagnose()` fix-it report with ring traces (§11.3).
- Robustness: reconciliation sweep, tombstones/pre-tombstones (out-of-order cancel), staleness
  guard (`expired`), `noBackupFilesDir` persistence with install-ID fencing, R8-safe resources.

Not yet in this release (see README status table): headless background-handler execution and the
Mode 2 native FCM path (Phase 3), telecom (Phase 4), telemetry upload (SCOPE-2 — config accepted
but inert).

## 0.0.1

Scaffold.
