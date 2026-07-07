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
3. **Did it return `degraded`?** → it *did* ring, with caveats in `result.degradations` (e.g. a
   pinned heads-up instead of full-screen). See [Degradations](#degradations-rang-but-not-fully).
4. **Did it return `shown`, but you heard nothing?** → the device is in silent/vibrate/DND, or the
   ringtone channel is muted. The ring trace says which. See [Silent but "shown"](#silent-but-shown).
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

`result.code == degraded` — it rang; `result.degradations` lists sanctioned fallbacks. None of these
stop the ring.

| Degradation | What the user sees | Remedy |
|---|---|---|
| `fsiDenied` | Full-screen intent access is off (Android 14+): a **60-second pinned heads-up** instead of the full-screen takeover. | `if (!await permissions.canUseFullScreenIntent()) await permissions.openFullScreenIntentSettings();` |
| `channelDemoted` | The incoming channel is below *High*: no OS heads-up, but the FGS + ringtone still ring. | `openChannelSettings(RingKitChannel.incoming)`. |
| `fgsDenied` | The OS refused a foreground-service start (rare, background-start limits): a best-effort in-process ring. | Usually transient; exclude the app from battery optimization (`openBatteryOptimizationSettings()`). |
| `callStyleRejected` | An OEM rejected the CallStyle template: a high-priority non-CallStyle notification with actions. | Cosmetic; no action needed. |
| `fcmDowngraded` | (Mode 2) FCM delivered your high-priority push at normal priority. | Server-side — send call pushes as `priority: high` and keep them rare (see [FCM.md](FCM.md)). |

## Silent but "shown"

The call rang visually but you heard nothing. This is **usually correct behavior**, and the ring
trace (`adb logcat -s RingKit:V`) states the reason:

- Device in **silent** or **vibrate** mode → ringkit honors it (vibrates in vibrate mode).
- **Do Not Disturb** → silenced unless the caller is an allowed exception.
- The **ringtone channel** was muted by the user → surfaces as `channelDemoted`.

ringkit never forces sound over the user's ringer policy. If you expected sound in normal mode and
didn't get it, check `diagnose()` for a `channelDemoted` finding.

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
