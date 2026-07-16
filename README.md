# ringkit

![platform](https://img.shields.io/badge/platform-Android-brightgreen)
![min SDK](https://img.shields.io/badge/minSdk-26-blue)
![license](https://img.shields.io/badge/license-MIT-purple)
![state](https://img.shields.io/badge/status-internal%20preview-orange)

**A CallKit-style incoming-call plugin for Flutter, Android-only.** Ringtone + vibration, a
full-screen lock-screen call UI, a CallStyle notification, missed-call surfaces, and **reliable
event delivery in every app state** — foreground, background, killed, locked, and Doze.

ringkit is **signaling UX only**: it owns everything between *"a call push arrived"* and *"the user
answered or declined."* You bring the push transport (FCM) and the media stack (LiveKit, Agora,
WebRTC). Built from scratch on 2026 SDKs as a ground-up replacement for `flutter_callkit_incoming`,
engineered against that plugin's entire issue history (474 Android issues audited into the design).

> **Internal preview.** Private package, not on pub.dev; MIT-licensed, access by invitation.
> Fleet telemetry ships **inert** (nothing leaves the device).

<!-- TODO: drop a ~6s screen recording of an incoming-call ring (lock screen → answer) here.
     A GIF is the single highest-impact addition to this README — capture it on a physical device. -->

## Features

- 📞 **Full incoming-call UX** — CallStyle heads-up + full-screen lock-screen activity, device
  ringtone with a `Ringtone` fallback, per-API vibration, avatars (with auth headers).
- 🔔 **Rings in every app state** — foreground, background, **killed**, locked, and **Doze**
  (high-priority FCM), backed by a foreground service that owns the audio.
- 🧟 **Killed-state event delivery** — accept/decline/timeout survive process death: unconsumed
  events **replay** on next launch, *and* a registered background handler runs in a **headless
  isolate** so a decline-while-dead reaches your server immediately.
- ⚡ **Mode 2 native FCM fast path** — the plugin's own receiver parses call pushes and rings in
  **< 300 ms from killed**, with Dart never on the critical path. One manifest line to enable.
- 🎛️ **Multi-call etiquette** — waiting calls, promotion, eviction, missed-call notifications
  (auto-cancel on tap), an outgoing leg, and an in-call foreground service.
- 🩺 **Honest failure reporting** — every `showIncomingCall` returns *exactly how it rang*
  (`shown` / `degraded({fsiDenied,…})` / `failed(notificationsBlocked)` / `expired`), plus a
  `diagnose()` fix-it report and OEM deep-links. **It never fails silently.**
- 🔒 **Safe by construction** — no microphone/camera permissions, nothing exported to other apps,
  all persistence outside cloud backup (no ghost calls after a restore).

## Table of contents

- [Install](#install)
- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [ringkit vs. flutter_callkit_incoming](#ringkit-vs-flutter_callkit_incoming)
- [Status](#status)
- [Documentation](#documentation)
- [Requirements](#requirements)
- [License](#license)

## Install

```yaml
# pubspec.yaml
dependencies:
  ringkit:
    git:
      url: https://github.com/Rishin-zartek/ringkit.git
      ref: main   # pin a tag/commit for reproducible builds
```

Set **`minSdk 26`** in `android/app/build.gradle(.kts)`. Everything else — permissions, services,
the full-screen activity — ships in the plugin manifest and merges automatically. No iOS; guard
calls with `Platform.isAndroid` in a cross-platform app.

**→ Full walkthrough: [docs/INTEGRATION.md](docs/INTEGRATION.md).**

## Quick start

```dart
import 'package:ringkit/ringkit.dart';

// 1. Subscribe FIRST — events buffer until the first listener, and a killed
//    session's unconsumed events replay here in order.
RingKit.events.listen((event) {
  switch (event) {
    case CallAccepted():  /* connect your RTC session, then setCallConnected() */
    case CallDeclined():  /* tell your server */
    case CallTimedOut():  /* missed */
    default: break;
  }
});

// 2. Initialize (idempotent; persisted natively so cold pushes honor it).
await RingKit.initialize(RingKitConfig(/* channels, timeouts, behavior … */));

// 3. Why was the app opened? (accept-from-lock-screen while dead, missed-call tap, …)
final launch = await RingKit.consumeLaunchAction();

// From your FCM handler — any isolate, any app state:
final result = await RingKit.showIncomingCall(IncomingCall(
  id: callUuid,                     // your UUID — the idempotency key
  callerName: 'Alice',
  type: CallType.audio,
  sentAtEpochMs: sentAt,            // staleness guard — send it
  extra: {'roomId': roomId},        // ≤ 1 KB; rides every event
  android: AndroidCallOptions(
    vibrate: true,
    lockScreenVisibility: NotificationVisibility.public,
    lockScreenBehavior: LockScreenBehavior.fullScreen,
  ),
));

// result never lies: shown / degraded({fsiDenied,…}) / failed(notificationsBlocked) / expired
```

Accept & decline also work programmatically (`RingKit.acceptCall(id)` / `declineCall(id)`) for
in-app answer UIs — the identical native path as a notification tap.

## How it works

- **Mode 1 (default)** — your Dart FCM handler calls `showIncomingCall(...)`. Works in every app
  state, including a cold-booted background isolate. Zero native config.
- **Mode 2 (opt-in, one manifest line)** — the plugin's own FCM receiver parses `rk.*` pushes
  **natively** and rings from killed in < 300 ms, with Dart never blocking the ring. Your existing
  `firebase_messaging` handlers keep firing for every non-call message. See
  [docs/FCM.md](docs/FCM.md).
- **Killed-state events** — the state machine + event log persist outside cloud backup; unconsumed
  events replay on next launch, and a registered background handler runs headless for actionable
  events (decline-while-dead → your server, no UI).
- **You always know what happened** — a typed `ShowCallResult`, a `diagnose()` report, and a
  per-call ring trace (`adb logcat -s RingKit:V`) explain every outcome, including deliberate
  silence (DND, silent mode).

## ringkit vs. flutter_callkit_incoming

|  | ringkit | flutter_callkit_incoming |
|---|---|---|
| Scope | Android only, done deeply | Android + iOS |
| Killed-state accept/decline delivery | ✅ replay + headless handler | ⚠️ frequently lost (many issues) |
| Native FCM fast path (Dart off the ring path) | ✅ Mode 2 | ❌ |
| Failure reporting | ✅ typed `shown/degraded/failed/expired` | ❌ `void` |
| Ring survives process death mid-call | ✅ FGS owns audio | ⚠️ dies with the isolate |
| Hardware (Bluetooth/watch) answer | ✅ telecom module routes it into the standard accept flow | ❌ dropped silently |
| Silent no-op when process is alive | ✅ no singleton in the action path | ⚠️ known failure |
| API | typed Pigeon models, sealed events | 40-field maps, `handle` often null |

**Migrating from `flutter_callkit_incoming`?** → [docs/MIGRATION.md](docs/MIGRATION.md).

## Status

| Area | State |
|---|---|
| Mode 1 lifecycle (show / accept / decline / timeout / cancel / end) in fg·bg·killed·locked·Doze | ✅ device-verified |
| Multi-call (waiting / promotion / eviction), missed calls, outgoing leg, in-call FGS | ✅ |
| Killed-state event replay + one-shot launch action | ✅ device-verified |
| Honest degradation reporting (`ShowCallResult`) | ✅ |
| Permissions / onboarding helpers + `diagnose()` + OEM deep links | ✅ |
| Headless background handler (decline-while-dead → your Dart fn) | ✅ device-verified |
| Mode 2 native FCM fast path (< 300 ms killed ring) | ✅ device-verified |
| Telecom opt-in (`telecomIntegration` / per-call `useTelecom`): system call registration, audio routing + `AudioRouteChanged`, rings even with notifications blocked | ✅ device-verified (API 26–36 incl. cold-process starts; automatic fallback to non-telecom on any failure) |
| Bluetooth/watch/wired-headset answer buttons | ✅ wired through the telecom module into the standard accept flow (unit-tested; physical-Bluetooth device pass pending) |
| Android 16 promoted (“Live Updates”) ongoing notification | ✅ promotion requested + status-bar chip on API 36 (OS treatment ships with A16 production builds) |
| Fleet telemetry upload | 🔜 final pre-release scope (inert today) |

**Quality bar:** 1000+ generated Robolectric tests (every state-machine transition is its own test
case) + Dart unit tests + an on-emulator integration suite + a two-phase killed-state harness — all
also verified on physical hardware.

## Documentation

| Guide | What's in it |
|---|---|
| **[docs/INTEGRATION.md](docs/INTEGRATION.md)** | The complete walkthrough — quickstart, FCM wiring, accept flow, lock-screen requirements, permissions/onboarding, customization, testing. Start here. |
| **[docs/FCM.md](docs/FCM.md)** | The `rk.*` push payload contract, server rules, and Mode 1 vs Mode 2. |
| **[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)** | "My call didn't ring" decision tree, the common-issue table, and how to read `diagnose()`. |
| **[docs/MIGRATION.md](docs/MIGRATION.md)** | Moving from `flutter_callkit_incoming` — API map and behavior differences. |
| **`example/`** | A complete manual test harness + reference integration (per-scenario buttons, an event console, a diagnostics screen, and backend-free adb triggers). |

## Requirements

- Flutter ≥ 3.44 (stable) · Dart 3
- Android **minSdk 26** (Android 8.0), compileSdk 36 · AGP 8.x or 9.x (both CI-tested)
- A push transport (FCM) and, for connected calls, a media stack (LiveKit / Agora / WebRTC)
- **Android only** — no iOS, by design

## License

MIT — see [LICENSE](LICENSE).
