# ringkit

**Android-only Flutter plugin for CallKit-style incoming calls** — ringtone + vibration,
full-screen lock-screen UI, CallStyle notification, missed-call surfaces, and reliable event
delivery in **every app state**: foreground, background, killed, locked, Doze.

Signaling UX only: no RTC/media (bring LiveKit/Agora/WebRTC), no push transport (bring FCM).
Built from scratch on 2026 SDKs as a replacement for `flutter_callkit_incoming`, engineered
against that plugin's full issue-tracker history (474 Android issues audited into the design).

> **Private package.** Not on pub.dev; telemetry is inert by default; MIT-licensed source,
> access by invitation.

## Install

```yaml
dependencies:
  ringkit:
    git:
      url: https://github.com/Rishin-zartek/ringkit.git
      ref: main   # pin a tag/commit for reproducible builds
```

Your app must set **`minSdk 26`**. Everything else (permissions, services, full-screen activity)
ships in the plugin manifest. No iOS.

**→ Read [`docs/INTEGRATION.md`](docs/INTEGRATION.md)** — the complete integration walkthrough
(FCM wiring, accept flow, lock-screen requirements, onboarding, customization, testing).

## 60-second taste

```dart
RingKit.events.listen((e) { /* CallAccepted / CallDeclined / CallTimedOut / … */ });
await RingKit.initialize(RingKitConfig(/* … */));
final launch = await RingKit.consumeLaunchAction();   // why was the app opened?

// from your FCM handler (any isolate, any app state):
final result = await RingKit.showIncomingCall(IncomingCall(
  id: callUuid, callerName: 'Alice', type: CallType.audio,
  sentAtEpochMs: sentAt, extra: {'roomId': roomId},
  android: AndroidCallOptions(vibrate: true,
      lockScreenVisibility: NotificationVisibility.public,
      lockScreenBehavior: LockScreenBehavior.fullScreen),
));
// result tells the truth: shown / degraded({fsiDenied,…}) / failed(notificationsBlocked) / expired
```

## Status — v0.2.x (plan Phases 0–2 complete)

| Area | State |
|---|---|
| Mode 1 lifecycle (show/accept/decline/timeout/cancel/end) in fg·bg·killed·locked·Doze | ✅ device-verified |
| Multi-call (waiting/promotion/eviction), missed calls, outgoing leg, in-call FGS | ✅ |
| Killed-state event replay + one-shot launch action | ✅ |
| Honest degradation reporting (`ShowCallResult` tells you exactly how it rang) | ✅ |
| Permissions/onboarding helpers + `diagnose()` fix-it report + OEM deep links | ✅ |
| Headless background handler execution (decline-while-dead → your Dart fn) | 🔜 Phase 3 (replay covers it today) |
| Mode 2 native FCM fast path (< 300 ms killed ring) | 🔜 Phase 3 |
| Telecom (audio routing, Bluetooth/watch answer) | 🔜 Phase 4 |
| Fleet telemetry upload | 🔜 in progress (inert today) |

Quality bar: 993 Robolectric tests (every state-machine cell is a generated test) + 15 Dart
tests + an on-emulator integration suite + a two-phase killed-state harness, all verified on
physical hardware.

## Repo map

- **Integrating the plugin into an app** → [`docs/INTEGRATION.md`](docs/INTEGRATION.md)
- **`example/`** → complete manual test harness + integration reference (see its README for the
  backend-free adb triggers and the killed-state testing recipe).
