# ringkit — Integration Guide

**ringkit** is an Android-only Flutter plugin that renders a CallKit-style incoming-call
experience — ringtone + vibration, full-screen lock-screen UI, CallStyle notification, missed-call
surfaces, and reliable event delivery in **every app state** (foreground, background, killed,
locked, Doze). It is **signaling UX only**: no audio/video, no push transport — you bring FCM (or
any push) and an RTC stack (LiveKit, Agora, WebRTC, …); ringkit owns everything between "push
arrived" and "user answered/declined".

> **Status (v0.2.x):** Mode 1 (Dart-path) is complete and device-verified. See
> [What's implemented vs. coming](#whats-implemented-vs-coming) before you design around
> not-yet-shipped features.

---

## 1. Install

Private repo — consume as a git dependency (ask the repo owner for access):

```yaml
# pubspec.yaml
dependencies:
  ringkit:
    git:
      url: https://github.com/Rishin-zartek/ringkit.git
      ref: main        # or pin a tag/commit for reproducible builds
```

**Your app must set `minSdk 26`** (Android 8.0) in `android/app/build.gradle(.kts)`:

```kotlin
defaultConfig {
    minSdk = 26          // ringkit requirement
}
```

Nothing else to add: permissions, services, receivers and activities all ship in the plugin's
manifest and merge automatically. The plugin declares **no** microphone/camera permissions —
those stay your app's business.

Toolchain: Flutter ≥ 3.44 stable, compileSdk 36, AGP 8.x or 9.x (both CI-tested). No iOS —
guard calls with `Platform.isAndroid` if your app is multi-platform.

---

## 2. Ten-minute quickstart

```dart
import 'package:ringkit/ringkit.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 1. Subscribe FIRST (events buffer until the first listener; unconsumed
  //    events from a killed session replay here in order, marked `replayed`).
  RingKit.events.listen(_onCallEvent);

  // 2. Initialize — idempotent; persists config natively so calls arriving
  //    before your next launch still honor it.
  await RingKit.initialize(RingKitConfig(
    incomingChannelName: 'Incoming calls',
    missedChannelName: 'Missed calls',
    ongoingChannelName: 'Ongoing call',
    audibleChannelName: 'Incoming calls (fallback)',
    defaultTimeoutSeconds: 45,
    connectionTimeoutSeconds: 120,
    maxConcurrentRinging: 3,
    telecomIntegration: false,       // Phase-4 feature — keep false
    manageOngoingCall: true,
    foregroundBehavior: ForegroundBehavior.headsUp,
    notificationIdBase: 424200,
    logLevel: RingKitLogLevel.error,
    channelSuffix: '',
  ));

  // 3. THEN consume the one-shot launch cause (why was the app opened?).
  final launch = await RingKit.consumeLaunchAction();
  if (launch != null && !launch.alreadyEnded) {
    // e.g. accepted from the lock screen while the app was dead:
    // navigate straight to your call screen for launch.call.id.
  }

  runApp(const MyApp());
}

void _onCallEvent(CallEvent event) {
  switch (event) {
    case CallAccepted():   // connect your RTC session, then:
      // await RingKit.setCallConnected(event.call.id);  // starts the chronometer
    case CallDeclined():   // tell your server
    case CallTimedOut():   // missed — your server marks it unanswered
    case CallCancelled(:final reason):  // caller hung up / answeredElsewhere / …
    case CallEnded(:final reason):      // live call ended (local/remote/failed)
    case MissedCallTapped():            // user tapped the missed-call notification
    case CallRinging():    // ringing started (live-only; never replayed)
    case AudioRouteChanged():           // telecom module only (Phase 4)
  }
}
```

Show a call (typically from your FCM handler, next section):

```dart
final result = await RingKit.showIncomingCall(IncomingCall(
  id: payload['rk.callId']!,           // YOUR call UUID — the idempotency key
  callerName: payload['rk.callerName'] ?? 'Unknown',
  callerHandle: payload['rk.callerHandle'],
  avatarUrl: payload['rk.avatarUrl'],  // https:// or flutterAsset:
  type: payload['rk.callType'] == 'video' ? CallType.video : CallType.audio,
  sentAtEpochMs: int.tryParse(payload['rk.sentAt'] ?? ''),  // staleness guard — send it!
  extra: {'roomId': payload['roomId'] ?? ''},               // ≤ 1 KB; rides every event
  android: AndroidCallOptions(
    vibrate: true,
    lockScreenVisibility: NotificationVisibility.public,
    lockScreenBehavior: LockScreenBehavior.fullScreen,
  ),
));

// ALWAYS check the result — ringkit never fails silently:
switch (result.code) {
  case ShowCallResultCode.shown:        break;                 // ringing
  case ShowCallResultCode.degraded:     /* ringing with caveats: result.degradations
                                           e.g. {fsiDenied} → lock screen shows a pinned
                                           heads-up instead of the full-screen UI */
  case ShowCallResultCode.failed:       /* NOT ringing: result.failReason
                                           (notificationsBlocked / channelBlocked / invalidParams)
                                           → fix via the onboarding helpers (section 6) */
  case ShowCallResultCode.expired:      /* arrived after timeoutSeconds — missed-call posted,
                                           never rang (network-delayed push) */
  case ShowCallResultCode.alreadyShown: // duplicate push — idempotent, ignore
  case ShowCallResultCode.alreadyActive:
  case ShowCallResultCode.alreadyTerminal:
}
```

---

## 3. FCM wiring (Mode 1 — the current baseline)

ringkit is transport-agnostic; with `firebase_messaging` you call it from both handlers.
**Send data-only, high-priority pushes** (an FCM `notification` block would render its own UI).

```dart
@pragma('vm:entry-point')
Future<void> firebaseBackgroundHandler(RemoteMessage message) async {
  // Runs in a background isolate — all ringkit COMMANDS are isolate-safe.
  final data = message.data;
  switch (data['rk.type']) {
    case 'incoming':
      await RingKit.showIncomingCall(/* map fields as in the quickstart */);
    case 'cancel':
      await RingKit.cancelCall(
        data['rk.callId']!,
        reason: switch (data['rk.reason']) {
          'answeredElsewhere' => CancelReason.answeredElsewhere,
          'declinedElsewhere' => CancelReason.declinedElsewhere,
          _ => CancelReason.callerCancelled,
        },
      );
  }
}

void main() {
  // register in main() AND handle foreground messages the same way via onMessage
  FirebaseMessaging.onBackgroundMessage(firebaseBackgroundHandler);
  ...
}
```

Recommended payload keys: use the `rk.*` names above (`rk.type`, `rk.callId`, `rk.callerName`,
`rk.callerHandle`, `rk.avatarUrl`, `rk.callType`, `rk.timeoutSeconds`, `rk.sentAt`, `rk.reason`,
`rk.extra` as a JSON string). They match ringkit's native wire contract, so when the Mode 2
native fast path ships (Phase 3), enabling it is a one-line manifest flip — no server change.

**Server rules that matter:**
- Always send `rk.sentAt` (epoch ms). A push delayed past the call timeout is recorded as a
  missed call and **never rings** (`expired`) instead of ringing a caller who gave up long ago.
- Send the **cancel push** when the caller hangs up / the call is answered on another device —
  that's what tears down the ringing UI everywhere and picks the right missed-call copy.
- Run your own unanswered-call timeout server-side. A force-stopped app receives **no FCM at
  all** (Android design); only your server can mark that call missed.

**Killed-state expectation (Mode 1):** FCM cold-starts your Dart isolate, so budget < 4 s from
push to ring on a healthy device. The FGS-start exemption lasts only ~10 s from FCM receipt —
do **not** `await` your own network calls before `showIncomingCall`.

---

## 4. The accept flow & your call screen

Accept from the notification/lock screen runs entirely natively (works with your app killed),
then launches your app and delivers `CallAccepted` + a `CallLaunchAction`.

- **Never read Android intent extras** for call data — use `consumeLaunchAction()` (survives
  activity recreation; one-shot; `sequence` matches the event on `events` so you can correlate).
- `launch.alreadyEnded == true` means the user hit Accept just as the call died (cancel race) —
  show a "call ended" toast, not a call screen. No extra event arrives; the terminal event was
  already delivered.
- **Lock-screen accept (R36):** ringkit dismisses the keyguard before launching you, but on a
  secure keyguard your call screen must render over the lock or the user bounces through
  authentication. In your `MainActivity` (Kotlin):

```kotlin
class MainActivity : FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Call-centric apps: allow the call screen over the keyguard.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }
}
```

  (If your app is not call-centric, gate these on your call route instead — trade-off: without
  them, accept-from-secure-lock prompts for PIN before your call screen appears.)

- After your RTC session actually connects, call **`setCallConnected(callId)`** — this flips the
  "Connecting…" notification to a live chronometer and disarms the connection watchdog. If you
  never call it, the call auto-ends as `failed` after `connectionTimeoutSeconds` (no immortal
  "Connecting…" ghosts).
- Building an **in-app answer UI** (foreground calls)? `acceptCall(id)` / `declineCall(id)` run
  the identical native path.
- Default `foregroundBehavior: headsUp` means a call arriving while your app is open shows a
  heads-up + rings, and `CallRinging` fires immediately so you may also render your own UI.

Hang-ups: **you hang up** → `endCall(id)` (reason `local`); **far end hangs up a live call** →
`endCall(id, reason: EndReason.remote)`; **caller cancels pre-answer** → `cancelCall(id, reason:…)`.

---

## 5. Multi-call, missed calls, outgoing

- Up to `maxConcurrentRinging` (default 3) simultaneous ringers: one audible **ringer-owner**,
  the rest get a notification + short vibration cue. Above the cap, the oldest waiting call is
  evicted with full missed-call semantics. Accepting any call silences the others (their timers
  keep running).
- Missed-call notifications post automatically on timeout/cancel with reason-appropriate copy
  ("Missed call" / "Answered on another device" / "Declined on another device" — overridable per
  call via `MissedCallOptions`). Tapping one (either target) auto-cancels it, launches the app,
  emits `MissedCallTapped`, and sets the launch action.
- `startOutgoingCall(...)` gives you a "Calling…" surface for the outgoing leg; same
  `setCallConnected` → chronometer → `endCall` lifecycle. No ring, obviously.
- `updateCallInfo(id, CallInfoUpdate(...))` silently re-renders name/handle/avatar on every
  surface (late contact resolution).
- `manageOngoingCall: true` (default) hosts an in-call foreground service so Android 14+ doesn't
  kill your mic in the background, and swiping the app away ends the call honestly
  (`CallEnded(failed)`). If your RTC stack already runs its own in-call FGS, set `false` — then
  task-removal handling is **your** responsibility. Apps holding `RECORD_AUDIO` can extend the
  service's FGS type via manifest merge:

```xml
<service android:name="com.wibechat.ringkit.services.OngoingCallService"
    android:foregroundServiceType="phoneCall|microphone"
    tools:node="merge" />
```

---

## 6. Permissions & onboarding (do this, or calls won't ring)

ringkit **never prompts mid-ring**; it degrades visibly and tells you how (see the quickstart result handling).
Put this in your onboarding/settings screen:

```dart
final p = RingKit.permissions;
await p.requestNotifications();               // POST_NOTIFICATIONS on 13+ — REQUIRED to ring
if (!await p.canUseFullScreenIntent()) {      // Android 14+: FSI is special access
  await p.openFullScreenIntentSettings();     //   without it: pinned heads-up instead of full-screen
}
final snapshot = await p.snapshot();          // everything in one call
// OEM kill-switches (Xiaomi/Oppo/Vivo/…): snapshot.oem.knownRestrictions +
await p.openOemSettings(OemScreen.autostart); // best-effort deep links (false = not on this device)
await p.openBatteryOptimizationSettings();    // exemption list (no extra permission needed)
```

And wire a **diagnostics screen** (or at least attach this to support tickets):

```dart
final report = await RingKit.diagnose();
// report.findings: [{id, severity, finding, remedy, deepLink}] — render as a fix-it checklist.
// Also: event-log health, last show result, last delivery path, last 50 ring traces.
```

Every "didn't ring" support case starts with `diagnose()` output.

---

## 7. Customization

| What | How |
|---|---|
| Ringtone | `AndroidCallOptions.ringtone` / `RingKitConfig.defaultRingtone`: `'default'` (device tone), `'raw:my_tone'` (app `res/raw`), `'content://…'`, or config-level `'flutterAsset:assets/tone.mp3'` |
| Avatar | `avatarUrl` (`https://` or `flutterAsset:`) + optional `avatarHeaders` (auth) — notification posts instantly, avatar pops in when fetched |
| Colors / background | `AccentColors(acceptArgb: …)`, `fullScreenBackground: '#RRGGBB'` (or https/flutterAsset image) |
| Notification icon | `RingKitConfig.notificationIcon: 'drawable_name'` (monochrome; plugin glyph default) |
| Channel names | All four names in `RingKitConfig` (shown in system settings) |
| Lock-screen style | `lockScreenBehavior: fullScreen` (default) or `notificationOnly` (heads-up style CallStyle, no takeover) |
| Missed-call copy | `MissedCallOptions(title:, subtitle:, callbackText:, showCallbackAction:, show:)` |
| Strings | English defaults; override any `ringkit_*` string via standard Android resource overrides |

Using `raw:` ringtones or a custom `notificationIcon` in a **release build**? Keep them from R8 —
`android/app/src/main/res/raw/keep.xml`:

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@raw/my_tone,@drawable/my_notification_icon" />
```

(`diagnose()` flags unresolvable icon/ringtone names, so a broken keep shows up immediately.)

---

## 8. Testing your integration

- **Foreground/background:** trigger real pushes; watch `events` and the returned
  `ShowCallResult`s.
- **Killed state:** ring → **swipe the app away** → accept/decline from the notification →
  relaunch → the event arrives `replayed` + launch action set. (`adb shell am kill <pkg>` works
  only while the app is background with no foreground service.)
- **The trap:** *Settings → Force stop* (or `adb shell am force-stop`) puts the app in **stopped
  state** — Android delivers **no FCM at all** until manual relaunch. A stopped app that doesn't
  ring is OS design, not a bug. Your server timeout is the only recourse.
- **Doze:** `adb shell dumpsys deviceidle force-idle`, then send a high-priority push.
- The plugin repo's `example/` app is a complete test harness (per-scenario buttons, event
  console with sequence/path badges, diagnostics + permissions screens, latency probe) — use it
  as the reference implementation.

**Play Console note (release):** declare the `phoneCall` foreground-service type and
full-screen-intent usage in the Play policy forms (standard for calling apps — have a short
screen-recording of an incoming call ready).

---

## 9. What's implemented vs. coming

**Shipped and device-verified (v0.2.x = plan Phases 0–2):** everything above — full Mode 1
lifecycle in all app states, multi-call, missed calls, outgoing leg, in-call service, killed-state
replay + launch action, degradation reporting, permissions helpers, diagnostics, OEM
deep-link table.

**Not yet — don't design around these until their phase lands:**

| Feature | Status |
|---|---|
| `setBackgroundEventHandler` headless execution | **Phase 3.** Registration API exists, but events for a dead app currently arrive via **replay on next launch** — a decline-while-dead does NOT invoke your Dart handler yet. If your server must learn about declines immediately, poll/timeout server-side for now. |
| Mode 2 native FCM fast path (< 300 ms killed-state ring, Dart never on the critical path) | Phase 3. One-line manifest enable when it ships; use `rk.*` payload keys now to be ready. |
| Telecom integration (`telecomIntegration: true`, `setAudioRoute`, Bluetooth/watch answer buttons) | Phase 4. `setAudioRoute` returns `wrongState` today. Keep `telecomIntegration: false`. |
| Remote telemetry upload | Planned (final pre-release scope). The `telemetry` config field is accepted but inert — nothing leaves the device. |
| iOS | Never (by design). |

---

## 10. Support

1. Reproduce, then grab `await RingKit.diagnose()` output (serializable) — attach it to the issue.
2. `adb logcat -s RingKit:V` around the incident gives the per-call ring trace (why it did what
   it did, including deliberate silence like DND).
3. The state machine is exhaustively defined: every command in every call state has a specified,
   generated-test-backed outcome — if behavior looks surprising, the ring trace will say why.
