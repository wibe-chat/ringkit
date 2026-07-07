# Migrating from `flutter_callkit_incoming`

ringkit is a ground-up Android replacement, not a drop-in fork — the API is smaller, typed, and
honest about failure. This guide maps the incumbent's surface onto ringkit and flags the behavior
differences that matter.

> **Android only.** If your app ships iOS with `flutter_callkit_incoming`'s CallKit/PushKit path,
> keep that plugin for iOS and use ringkit for Android (guard with `Platform.isAndroid`). ringkit
> has no iOS by design.

## API map

| `flutter_callkit_incoming` | ringkit | Notes |
|---|---|---|
| `showCallkitIncoming(CallKitParams)` | `RingKit.showIncomingCall(IncomingCall)` | Returns a typed `ShowCallResult` instead of `void` — you learn exactly how it rang. |
| `onEvent` stream (`Event` + string action) | `RingKit.events` (sealed `CallEvent`) | Pattern-match subclasses; every event carries a `deliveryPath` (`live` / `replayed` / `background`). |
| `activeCalls()` polling to recover launch state | `RingKit.consumeLaunchAction()` | A one-shot "why was the app opened?" — no polling hack. |
| `Event.actionCallAccept` / `actionCallDecline` | `CallAccepted` / `CallDeclined` | Sealed types; no string matching. |
| `endCall(id)` | `RingKit.endCall(id, reason: …)` | Typed result; `reason` distinguishes local vs remote hang-up. |
| `setCallConnected(id)` | `RingKit.setCallConnected(id)` | Starts the in-call chronometer + disarms the connection watchdog. |
| `endAllCalls()` | `RingKit.endAllCalls()` | Per-call events emitted, no missed-call surfaces. |
| `showMissCallNotification(...)` | `AndroidCallOptions.missedCall` | Automatic on timeout/cancel; auto-cancels on tap. |
| Accept from your own UI (not well supported) | `RingKit.acceptCall(id)` / `declineCall(id)` | Same native path as a notification tap. |
| — | `RingKit.startOutgoingCall(OutgoingCall)` | An outgoing "Calling…" leg (no ring). |
| — | `RingKit.diagnose()` | A fix-it report — findings, remedies, deep-links. |
| — | `RingKit.permissions` | Onboarding helpers (notifications, FSI, battery, OEM). |
| — | `RingKit.setBackgroundEventHandler(fn)` | Headless decline-while-dead → your server. |

## Field mapping (`CallKitParams` → `IncomingCall`)

```dart
// Before
CallKitParams(id: uuid, nameCaller: 'Alice', handle: '+1555', type: 0, extra: {...});

// After
IncomingCall(
  id: uuid,                         // idempotency key (≤ 128 chars)
  callerName: 'Alice',
  callerHandle: '+1555',
  type: CallType.audio,             // type: 1 → CallType.video
  sentAtEpochMs: sentAt,            // NEW — send it; enables the staleness guard
  extra: {...},                     // ≤ 1 KB; rides every event
  android: AndroidCallOptions(
    vibrate: true,
    lockScreenVisibility: NotificationVisibility.public,
    lockScreenBehavior: LockScreenBehavior.fullScreen,
  ),
);
```

Android presentation (ringtone, colors, avatar background, missed-call copy) moves under
`AndroidCallOptions`. iOS-only `CallKitParams` fields have no equivalent.

## Behavior differences to expect

- **Failures are visible, not silent.** Where the incumbent returned `void` and sometimes no-oped,
  `showIncomingCall` returns `shown` / `degraded` / `failed` / `expired`. Handle the result.
- **Killed-state accept/decline is reliable.** Unconsumed events replay on next launch, and a
  registered background handler runs headless — the class of "accept lost when killed" bugs is
  designed out. In exchange you **must subscribe to `events` before awaiting** in `main()`.
- **Idempotent by `id`.** Duplicate FCM re-deliveries return `alreadyShown` instead of double-ringing.
  Post-terminal re-deliveries never re-ring.
- **`sentAtEpochMs` matters.** A push that arrives after `timeoutSeconds` is recorded as missed and
  returns `expired` — it never rings a stale call. Always send the timestamp.
- **No `AudioManager.mode` tricks.** The "next call silent after one call" class of bugs cannot occur.
- **Permissions are your onboarding's job.** ringkit never prompts mid-ring; use `RingKit.permissions`
  in a settings/onboarding screen.

## What ringkit intentionally does *not* do

- **iOS / CallKit / PushKit** — Android only.
- **Audio/video (RTC)** — bring LiveKit, Agora, or WebRTC; ringkit is signaling UX only.
- **Push transport** — bring FCM (see [FCM.md](FCM.md)); ringkit is transport-agnostic.

## Suggested migration steps

1. Add ringkit, set `minSdk 26`, remove `flutter_callkit_incoming`'s Android setup (keep it for iOS
   if you ship iOS).
2. Replace `showCallkitIncoming` with `showIncomingCall` and **handle the returned result**.
3. Move your `onEvent` switch to a sealed `RingKit.events` `switch`; subscribe **first** in `main()`.
4. Replace launch-state polling with `consumeLaunchAction()`.
5. Add a permissions/onboarding screen using `RingKit.permissions`.
6. (Optional) enable [Mode 2](FCM.md) for sub-300 ms killed-state rings.

Full setup details are in [INTEGRATION.md](INTEGRATION.md).
