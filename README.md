# ringkit

![platform](https://img.shields.io/badge/platform-Android-brightgreen)
![min SDK](https://img.shields.io/badge/minSdk-26-blue)
![license](https://img.shields.io/badge/license-MIT-purple)
![state](https://img.shields.io/badge/status-preview-orange)

**RingKit** is an open-source Flutter plugin from the team behind [Wibe Chat](https://wibechat.com) that handles the hardest part of building a calling app on Android: making the phone actually ring. It delivers a native, CallKit-style incoming-call experience — a full-screen lock-screen UI, heads-up notification, ringtone and vibration — that works reliably whether the app is in the foreground, background, killed, or in Doze. Accept, decline and timeout events survive process death and replay on next launch, and an opt-in native FCM path rings from a cold start in under 300ms.
RingKit handles signaling only — the moment between a call push arriving and the user answering. You bring the push transport (FCM) and the media stack. It pairs naturally with [Wibe Chat](https://wibechat.com) — our unified SDK for in-app [voice and video calling](https://wibechat.com/products/voice) — but stays provider-agnostic and works with LiveKit, Agora or raw WebRTC too. MIT-licensed and built by [Zartek](https://www.zartek.in/).


**A CallKit-style incoming-call plugin for Flutter, Android-only.** Ringtone + vibration, a
full-screen lock-screen call UI, a CallStyle notification, missed-call surfaces, and **reliable
event delivery in every app state** — foreground, background, killed, locked, and Doze.

ringkit is **signaling UX only**: it owns everything between *"a call push arrived"* and *"the user
answered or declined."* You bring the push transport (FCM) and the media stack (LiveKit, Agora,
WebRTC). Built from scratch on 2026 SDKs as a ground-up replacement for `flutter_callkit_incoming`,
engineered against that plugin's entire issue history (474 Android issues audited into the design).

> **Preview.** MIT-licensed and Android-only.
> Fleet telemetry is **off by default**. Opting in sends diagnostic data to the backend you
> configure; no telemetry endpoint or credentials are bundled. See [Telemetry](docs/TELEMETRY.md).

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
- 🩺 **Honest failure reporting** — every `showIncomingCall` returns the initial native show outcome
  (`shown` / `degraded({fsiDenied,…})` / `failed(notificationsBlocked)` / `expired`), plus a
  `diagnose()` fix-it report and OEM deep-links. These report software state, not whether a person heard sound.
- 🔒 **Restricted native components** — no microphone/camera permissions; RingKit-owned components are
  unexported except the opt-in FCM receiver protected by Google's signature permission.
  Persistence stays outside cloud backup (no ghost calls after a restore).
  See [integration guidance](docs/INTEGRATION.md) for merged Jetpack Telecom components.

## Table of contents

- [Install](#install)
- [Quick start](#quick-start)
- [Integrate the diagnostics build](#integrate-the-diagnostics-build)
- [Optional analytics with your own Supabase](#optional-analytics-with-your-own-supabase)
- [How it works](#how-it-works)
- [ringkit vs. flutter_callkit_incoming](#ringkit-vs-flutter_callkit_incoming)
- [Status](#status)
- [Documentation](#documentation)
- [Requirements](#requirements)
- [License](#license)

## Install

```sh
flutter pub add ringkit
```

Or add the dependency to your app:

```yaml
# pubspec.yaml
dependencies:
  ringkit: ^0.2.0
```

To use this branch's **0.2.1 diagnostics preview**, install from Git instead. The
`^0.2.0` example above does not pin this implementation:

```yaml
dependencies:
  ringkit:
    git:
      url: https://github.com/wibe-chat/ringkit.git
      ref: advanced-analtycs # development; pin a reviewed full commit SHA for release
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

// Inspect the result: shown / degraded / failed / expired.
// A shown result is not proof of physical sound or later playback health.
```

Accept & decline also work programmatically (`RingKit.acceptCall(id)` / `declineCall(id)`) for
in-app answer UIs — the identical native path as a notification tap.

## Integrate the diagnostics build

Follow these steps in the **Android launcher app that produces the APK**, including each brand
or flavor. Updating a shared Dart package alone does not establish what native code is installed.

### 1. Confirm the dependency used by the launcher

Use the Git dependency above. For a reproducible release, replace `advanced-analtycs` with a
reviewed full commit SHA and retain the launcher's `pubspec.lock`. To see the branch tip:

```sh
git ls-remote https://github.com/wibe-chat/ringkit.git refs/heads/advanced-analtycs
```

Check the launcher's `pubspec.yaml`, any `pubspec_overrides.yaml`, and its resolved lockfile for
a stale `path:` override or another Git revision. If a shared package brings RingKit transitively,
an intentional override belongs in the launcher or its Pub workspace resolution:

```yaml
dependency_overrides:
  ringkit:
    git:
      url: https://github.com/wibe-chat/ringkit.git
      ref: advanced-analtycs # replace with the reviewed full SHA for release
```

An override inside a depended-on shared package does not control the consuming app. See
[Dart's dependency rules](https://dart.dev/tools/pub/dependencies#dependency-overrides).
After changing the ref, run `flutter pub get`; when intentionally tracking the moving branch,
run `flutter pub upgrade ringkit` to refresh its locked revision. Check the RingKit entry in
`pubspec.lock`: `source: git`, the intended `resolved-ref`, and version `0.2.1` for this preview.
Also inspect RingKit's `rootUri` in `.dart_tool/package_config.json` if a local path is suspected.
Review the lockfile diff; a version string alone does not identify the installed native build.

### 2. Share one complete configuration across entry points

`RingKit.initialize()` persists the entire configuration using last-writer-wins semantics.
Calling it later with `telemetry: null` **disables native telemetry**, including from a background
isolate or a shared wrapper. A foreground singleton, Provider or widget tree is not available in
the Android FCM background isolate. Load brand settings locally in that isolate, or use the same
compile-time configuration in both entry points.

For example, put this factory in the launcher's `lib/ringkit_config.dart`, import it from every
entry point, and adapt the call settings to your app:

```dart
import 'package:ringkit/ringkit.dart';

RingKitConfig buildRingKitConfig() {
  const enabled = bool.fromEnvironment('RINGKIT_TELEMETRY_ENABLED');
  const endpoint = String.fromEnvironment('RINGKIT_TELEMETRY_ENDPOINT');
  const appKey = String.fromEnvironment('RINGKIT_APP_KEY');
  const label = String.fromEnvironment(
    'RINGKIT_DEVICE_LABEL',
    defaultValue: 'app-install',
  );
  if (enabled && (endpoint.isEmpty || appKey.isEmpty)) {
    throw StateError('RingKit telemetry was enabled without its configuration');
  }

  return RingKitConfig(
    incomingChannelName: 'Incoming calls',
    missedChannelName: 'Missed calls',
    ongoingChannelName: 'Ongoing call',
    audibleChannelName: 'Incoming calls (fallback)',
    defaultTimeoutSeconds: 45,
    connectionTimeoutSeconds: 120,
    maxConcurrentRinging: 3,
    telecomIntegration: false,
    manageOngoingCall: true,
    foregroundBehavior: ForegroundBehavior.headsUp,
    notificationIdBase: 424200,
    logLevel: RingKitLogLevel.info,
    channelSuffix: '',
    telemetry: enabled
        ? RingKitTelemetryConfig(
            endpoint: endpoint,
            apiKey: appKey,
            deviceLabel: label,
            uploadLevel: RingKitLogLevel.info,
            dailyUploadBudgetKb: 200,
          )
        : null,
  );
}
```

Use your HTTPS backend URL and its **RingKit fleet registration key for this app**. This key is
client configuration, not a Supabase service-role key or dashboard password. Keep configuration
files out of Git and build logs; compilation embeds these values in the app, not a secret vault.
Telemetry remains off when `RINGKIT_TELEMETRY_ENABLED` is absent/false. The explicit error above
prevents an opted-in build from silently falling back to disabled telemetry when values are missing.

### 3. Wire the selected push mode and call lifecycle

| Entry point | Required integration |
|---|---|
| Main Android app | Initialize Flutter/Firebase, subscribe to `RingKit.events` once, then `await RingKit.initialize(buildRingKitConfig())`. Register the FCM background handler, handle foreground messages, and consume the launch action. |
| Mode 1 foreground and background messages | Use the same configuration before dispatching call pushes. Map incoming payloads to `showIncomingCall()` and cancel payloads to `cancelCall()`. Keep the background handler top-level with `@pragma('vm:entry-point')`; initialize Firebase there before using Firebase services. |
| Mode 2 native FCM | Initialize and open the app once so native configuration is persisted. Enable the receiver with the manifest override in [FCM setup](docs/FCM.md#4-mode-2--the-native-fast-path), including `tools:replace="android:enabled"`. In **both** Dart message handlers, return for `RingKit.isRingMessage(message.data)`; native already handles those pushes. |
| Shared wrappers and headless event callbacks | Audit every `RingKit.initialize()` call. Reuse the same complete configuration whenever initialization is needed; do not substitute an empty/default configuration. Headless callbacks must not navigate UI or consume the foreground event stream. |

Use one mode consistently. The Mode 2 early-return guard would discard calls if its native
receiver were still disabled. See the [Mode 1 mapping](docs/INTEGRATION.md#3-fcm-wiring-mode-1--the-current-baseline)
and [reference app](example/lib/main.dart). Firebase documents the separate Android isolate and
release entry-point requirements in its [Flutter message guide](https://firebase.google.com/docs/cloud-messaging/flutter/receive-messages).

Send data-only call pushes with Android high priority, a unique `rk.callId` and fresh `rk.sentAt`.
Send a cancel for the same ID when ringing should end. Do not wait for an API lookup, remote config
or RTC/media setup before showing the call. Connect media after acceptance, call `setCallConnected()`
once media is connected, and use `endCall()` when the live call ends. Handle replayed events and
launch actions idempotently so one answer does not join the media session twice.

Complete notification/full-screen/OEM onboarding before test calls using `RingKit.permissions`
and `diagnose()` ([permission setup](docs/INTEGRATION.md#6-permissions--onboarding-do-this-or-calls-wont-ring)).
Start with the default ringtone. Add custom tones only after the baseline works; follow the
[resource/shrinking instructions](docs/INTEGRATION.md#7-customization) for `raw:` tones.
Do not change `AudioManager.mode` or request DND-policy access as a ringtone workaround.

When Telecom is enabled, the SDK delegates ringtone focus only after the specific call registers
successfully; the trace then shows `focus_delegated`. Keep the same SDK in every app entry path
and rebuild the host APK to receive this native behavior. A ringtone that starts and immediately
logs `focus_loss_stop` needs the [focus troubleshooting checks](docs/TROUBLESHOOTING.md#silent-but-shown).
Standalone and registration-fallback calls continue to manage their own focus.

### 4. Rebuild and install the actual host app

Create a local/CI-only JSON file such as `config/ringkit-telemetry.local.json` (add it to the
host app's `.gitignore`). Replace these placeholders with that app's configuration:

```json
{
  "RINGKIT_TELEMETRY_ENABLED": true,
  "RINGKIT_TELEMETRY_ENDPOINT": "https://YOUR_PROJECT.supabase.co",
  "RINGKIT_APP_KEY": "YOUR_APP_FLEET_REGISTRATION_KEY",
  "RINGKIT_DEVICE_LABEL": "qa-redmi-note-10-pro"
}
```

From the launcher directory, using its existing flavor, entry point and signing setup:

```sh
flutter clean
flutter pub get
flutter build apk --release --dart-define-from-file=config/ringkit-telemetry.local.json
```

Record the APK hash and RingKit's generated `RINGKIT_BUILD_ID` with the build artifact. In a
standard Flutter build, the latter is in
`build/ringkit/generated/source/buildConfig/release/com/wibechat/ringkit/BuildConfig.java`.
Use that value when comparing dashboard identity; debug builds use the corresponding `debug` directory.

Pass the same configuration file to debug/test builds that should upload diagnostics. A hot reload
or Dart-only update cannot replace the native SDK. Install a signed-compatible update with the
same application ID and an increased host version code; keep the existing installation and logs.
Open the updated app with network access. Check `await RingKit.telemetryStatus()` is non-null,
and attach `await RingKit.diagnose()` to your test notes. These are local checks, not upload receipts.

Add a visible **Report issue** action that calls `await RingKit.reportIssue()` immediately after
a failed test. A `true` result means the recent-buffer upload was **scheduled**, not received;
`false` means telemetry is disabled. Show that distinction in the app and verify receipt in the dashboard.

### 5. Prove collection before asking a remote tester to reproduce

**Deploy the matching additive database migration and updated `checkin` function before the
new dashboard.** Follow [backend setup](docs/SUPABASE_SETUP.md) and the
[v2 telemetry contract](docs/TELEMETRY.md#version-2-diagnostics-sdk-021); use the migration tree
for your existing deployment or fresh project, never both. A Git push does not deploy these services.
Older servers retain v1 behavior and cannot offer v2 sessions. New installations are not enrolled automatically.

1. Open the updated app and select its exact **app + installation** in the dashboard. Confirm a
   fresh metadata/control contact, `plugin_version=0.2.1`, nonempty `sdk_build_id`, protocol 2,
   and `audio_failure`, `build_identity`, `diagnostic_session`, `correlated_pull` capabilities.
   Match the SDK hash to the tested build artifact and check the host version code. `host_debuggable`
   is packaging evidence; it does not establish a real user or every Flutter build-mode detail.
2. Choose **Enable diagnostics canary**, then **Start 30-minute session**. **Background and reopen
   the app after enrollment** so it discovers the new server setting; an unenrolled app does not
   repeatedly poll during the same foreground stay. Keep it visible until the request is
   **acknowledged** before starting calls. Foreground discovery is coalesced over 30 seconds and
   requires eligible network work. `queued` or `offered` is not acknowledgement.
3. Test foreground, background, locked-screen and process-death delivery with unique call IDs.
   Record exact times/timezone, ring volume, ringer/DND state, and what the tester actually heard/felt.
   Exercise accept/connect/end, decline, remote cancel and timeout; check for leftover notifications or services.
4. Inspect **Call evidence** for those IDs. A session is bounded by 30 minutes, 20 calls and 512 KiB;
   `collecting` means partial evidence. Check per-call receipts as tests finish and the final
   `received` receipt after the session ends. `received_empty`, missing IDs or truncation requires
   investigation. **Stop diagnostics cancels capture**; it is not a successful finalization shortcut.
5. Save the tester's observation separately. `Playing`, legacy `audible=true`, a registration or
   an HTTP 200 cannot prove audible sound. `RING_AUDIO_FAILED` detects software ladder exhaustion;
   an OEM reporting playback while remaining silent still needs the session trace and human observation.

For ADB tests, background the app before `adb shell am kill <applicationId>` and confirm its process
has exited. `am force-stop` tests Android's stopped state; reopen the app before expecting FCM again.
For remote-only testing, a pending request cannot wake a force-stopped/offline phone. If collection
stops after a background call, first check for a wrapper reinitializing with `telemetry: null`.
See [troubleshooting](docs/TROUBLESHOOTING.md) for logs and device settings to capture.

## Optional analytics with your own Supabase

Calling features work without a backend. To collect RingKit diagnostics in **your own**
Supabase account, follow the [Supabase setup guide](docs/SUPABASE_SETUP.md):

1. Create a dedicated project and apply the supplied fresh-project database migration.
2. Deploy `checkin` and `purge`, configure the server-only maintenance secret and scheduled jobs.
3. Generate a RingKit app key, then add your **project URL + app key** to `RingKitConfig.telemetry`.

No Supabase Flutter SDK or end-user Supabase login is required. The example app accepts those
values in its telemetry settings. Telemetry stays **off by default**; the guide includes a
copyable Flutter configuration and checks to run on your own deployment.
Never put a Supabase service-role key, database password or purge secret in an app.
The optional dashboard is for trusted all-app administrators, not public visitors.

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
| Fleet telemetry upload | ✅ opt-in incidents, heartbeat and operator-requested pulls; off by default. Fresh-project Supabase setup supplied; owners must verify their hosted deployment. |

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
| **[docs/TELEMETRY.md](docs/TELEMETRY.md)** | Opt-in collection, privacy, wire contract and backend deployment prerequisites. |
| **[Supabase setup](docs/SUPABASE_SETUP.md)** | Create your own analytics backend, configure app keys, deploy functions and schedule retention. |
| **[Publishing to pub.dev](docs/PUBLISHING.md)** | Maintainer release checks, archive inspection and the deliberate publication command. |
| **`example/`** | A complete manual test harness + reference integration (per-scenario buttons, an event console, a diagnostics screen, and backend-free adb triggers). |

## Requirements

- Flutter ≥ 3.44 (stable) · Dart ≥ 3.12.2
- Android **minSdk 26** (Android 8.0), compileSdk 36 · AGP 8.x or 9.x (both CI-tested)
- A push transport (FCM) and, for connected calls, a media stack (LiveKit / Agora / WebRTC)
- **Android only** — no iOS, by design

## License

MIT — see [LICENSE](LICENSE).
