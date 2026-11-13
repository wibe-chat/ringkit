# ringkit — Troubleshooting

ringkit is built so you rarely have to guess: **every `showIncomingCall` returns a typed result**,
`RingKit.diagnose()` gives a fix-it report, and a per-call ring trace explains every decision. Start
with those two, then use the tables below.

```dart
final result = await RingKit.showIncomingCall(call);
// result.code → shown | degraded | failed | expired
// result.degradations / result.failReason → why

final report = await RingKit.diagnose();
// report.findings: [{id, severity, finding, remedy, deepLink}] — render as a checklist
```

```bash
adb logcat -s RingKit:V     # the per-call ring trace: what it did and WHY (incl. deliberate silence)
```

## "My call didn't ring" — decision tree

1. **Did `showIncomingCall` return `failed`?** → the call was never allowed to ring. Read
   `result.failReason` and jump to [Failures](#failures-not-ringing-at-all). Most common:
   `notificationsBlocked`.
2. **Did it return `expired`?** → the push arrived *after* `timeoutSeconds` (network delay). ringkit
   recorded a missed call and did not ring — by design. Send `sentAtEpochMs` and check your push
   latency.
3. **Did it return `degraded`?** → a fallback was used, described in `result.degradations` (e.g. a
   pinned heads-up instead of full-screen). This does not prove audible playback. See [Degradations](#degradations-rang-but-not-fully).
4. **Did it return `shown`, but you heard nothing?** → inspect ringer/DND policy, ring volume,
   audio focus, and playback failures in the ring trace and diagnostics. See [Silent but "shown"](#silent-but-shown).
5. **Did `showIncomingCall` never run at all?** → the push didn't reach your app. See
   [The push never arrived](#the-push-never-arrived) — usually **force-stop** or FCM priority.

## Failures (not ringing at all)

`result.code == failed` — ringkit persisted the call with a deadline but posted **no surface and no
sound** (invisible ringing is banned). Fix the precondition, then future calls ring.

| `failReason` | Cause | Fix |
|---|---|---|
| `notificationsBlocked` | The user denied/disabled notifications (runtime permission on Android 13+). | `await RingKit.permissions.requestNotifications();` in onboarding. Until granted, **nothing renders**. |
| `channelBlocked` | The "Incoming calls" channel is set to *None* importance. | `openChannelSettings(RingKitChannel.incoming)` to send the user to re-enable it. |
| `invalidParams` | Blank/>128-char `id`, `extra` > 1 KB, or similar. | Validate before calling; check the ring trace for the specific field. |

## Degradations (rang, but not fully)

`result.code == degraded` — `result.degradations` lists the fallback paths used. Sound still depends
on device policy, successful playback, and the lifetime of the ringing process.

| Degradation | What the user sees | Remedy |
|---|---|---|
| `fsiDenied` | Full-screen intent access is off (Android 14+): a **60-second pinned heads-up** instead of the full-screen takeover. | `if (!await permissions.canUseFullScreenIntent()) await permissions.openFullScreenIntentSettings();` |
| `channelDemoted` | The incoming channel is below *High*: no OS heads-up, but the FGS + ringtone still ring. | `openChannelSettings(RingKitChannel.incoming)`. |
| `fgsDenied` | The OS refused a foreground-service start (rare, background-start limits): a best-effort in-process ring. | Usually transient; exclude the app from battery optimization (`openBatteryOptimizationSettings()`). |
| `callStyleRejected` | An OEM rejected the CallStyle template: a high-priority non-CallStyle notification with actions. | Cosmetic; no action needed. |
| `fcmDowngraded` | (Mode 2) FCM delivered your high-priority push at normal priority. | Server-side — send call pushes as `priority: high` and keep them rare (see [FCM.md](FCM.md)). |

## Silent but "shown"

A visible incoming-call notification does not prove audible playback. Capture `diagnose()` and
`adb logcat -s RingKit:V` immediately after reproducing:

- **Silent/vibrate/DND:** ringkit honors device policy. Vibration also requires the call's
  `vibrate` option. On Android 13 and below, DND suppresses calls conservatively; Android 14+
  supports checking caller exceptions.
- **Ring volume:** `ringVolumeZero` warns when normal ringer mode still has a muted or zero-volume
  ring stream. Restore the volume through system sound settings.
- **Playback:** inspect the selected engine, focus result, startup errors and fallback transitions.
  The ladder tries MediaPlayer, the system Ringtone player for supported content URIs, then the
  bundled tone. A playing-state check cannot establish that sound was physically audible.
- **Stops immediately after starting:** compare `focus_loss_stop` with the accept/terminal time.
  Older builds can request ringtone focus in competition with a successful Telecom call. The
  current implementation delegates focus only to that call's live Telecom registration and logs
  `focus_delegated`; standalone or registration-fallback calls log `focus_requested`. Update the
  resolved SDK in the host app, rebuild the native APK, and confirm its build identity before
  retesting. Disabling `useTelecom` for one tester call can isolate the integration, but also removes
  Telecom features for that call. Do not ignore every focus-loss callback or force another stream.
  Telecom inactivity/scope closure intentionally stops delegated audio; inspect
  `telecom_focus_stop` and its reason. Retest actual sound in foreground, background and cold state.
- **Vibration:** `vibratorUnavailable` identifies absent hardware and `manifestVibrateRemoved`
  identifies a missing merged `VIBRATE` permission. A successful vibration API request does not
  prove physical vibration; device settings can still suppress it.

The normal incoming-call channel is deliberately silent: the ringing foreground service owns sound
and vibration. `channelDemoted` explains missing heads-up presentation, not a muted ringtone stream.
The separate audible fallback channel matters when the foreground service is denied; inspect
`audibleFallbackDegraded` in that case. Do not enable channel sound to work around a player failure,
as that can produce duplicate ringing.

ringkit never forces sound or vibration over the user's ringer/DND policy.

## The push never arrived

`showIncomingCall` never ran — the problem is upstream of ringkit.

- **Force-stopped app** (*Settings → Force stop*, or a task-killer): Android delivers **no FCM at
  all** until the user manually relaunches. This is OS design, not a bug — your **server-side call
  timeout** is the only recourse. (Swiping the app away is *not* the same as force-stop; killed apps
  still receive FCM.)
- **FCM priority**: only send **`priority: high`** for calls, and don't over-send — Android
  de-prioritizes senders that abuse high priority. Mode 2 detects a downgrade and reports
  `fcmDowngraded`.
- **Doze**: high-priority FCM punches through Doze. Verify with
  `adb shell dumpsys deviceidle force-idle` then send a push.

## OEM kill walls (Xiaomi, Oppo, Vivo, Realme, …)

Aggressive Chinese-OEM skins kill background apps and block auto-start, which no plugin can fully
override. ringkit **detects** the device and offers best-effort deep-links:

```dart
final snapshot = await RingKit.permissions.snapshot();
// snapshot.oem.knownRestrictions → what this device is known to do
await RingKit.permissions.openOemSettings(OemScreen.autostart);   // false if not resolvable here
await RingKit.permissions.openBatteryOptimizationSettings();
```

Guide users through **Autostart = on** and **Battery = unrestricted** during onboarding on these
devices. `diagnose()` flags them.

## Killed-state accept/decline seems lost

- Confirm you **subscribe to `RingKit.events` before `await`-ing anything** in `main()` — events
  buffer until the first listener and replay from a killed session there.
- For delivery **while the app stays dead** (e.g. tell your server about a decline immediately),
  register a top-level background handler: `RingKit.setBackgroundEventHandler(myTopLevelFn)`. It
  must be a top-level/static function, re-registered on every launch.
- Call `consumeLaunchAction()` **after** subscribing, to learn if the app was opened *by* an accept.

## Still stuck?

1. `await RingKit.diagnose()` — attach the serialized output to your report.
2. `adb logcat -s RingKit:V` around the incident — the ring trace explains every decision.
3. The state machine is exhaustive: every command in every call state has a defined, tested
   outcome. If behavior surprises you, the ring trace will say why.
