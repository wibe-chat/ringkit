# ringkit — FCM guide (payload contract + Mode 1 / Mode 2)

ringkit is push-transport-agnostic — it owns everything between "a call push arrived" and
"the user answered or declined", but you bring the push. This guide covers using it with
**Firebase Cloud Messaging (`firebase_messaging`)**: the payload your server sends, and the two
ways ringkit can consume it.

- **Mode 1 (Dart path)** — your existing `firebase_messaging` handler calls ringkit. Works with
  any transport, no manifest changes, rings in ~1–3 s (the isolate cold-starts on a killed app).
- **Mode 2 (native fast path)** — one manifest line lets ringkit's own receiver handle call
  pushes **natively**: it rings in **< 300 ms even when the app is killed**, with Dart never on
  the critical path. Everything else about `firebase_messaging` keeps working untouched.

Both read the **same `rk.*` payload**, so you can ship Mode 1 today and flip on Mode 2 later with
**no server change**.

---

## 1. The `rk.*` payload

Send a **data-only**, **high-priority** message. (An FCM `notification` block would make the
system draw its own tray notification in the background and never reach your handler — don't use
one for calls.) All values are strings (FCM data payloads are string-only).

| Key | Required | Meaning / default |
|---|---|---|
| `rk.type` | ✅ | `incoming` or `cancel` |
| `rk.callId` | ✅ | your call's unique id — the idempotency key (≤ 128 chars; a longer id is rejected, never truncated) |
| `rk.callerName` | ✅ for `incoming` | blank → "Unknown caller" |
| `rk.callerHandle` | – | subtitle (phone/handle) |
| `rk.avatarUrl` | – | `https://` only (non-https is ignored) |
| `rk.avatarHeaders` | – | JSON object string — per-fetch auth headers for the avatar |
| `rk.callType` | – | `audio` (default) or `video` |
| `rk.timeoutSeconds` | – | ring duration; clamped to 10–120; default from your config |
| `rk.sentAt` | – (send it) | epoch ms — the staleness guard (see server rules) |
| `rk.extra` | – | JSON object string ≤ 1 KB — rides along on every event you receive |
| `rk.ringtone` | – | `default`, `raw:<name>` (an `res/raw` file), or `content://…` |
| `rk.vibrate` | – | `true` (default) / `false` |
| `rk.lockScreenVisibility` | – | `public` (default) / `private` / `secret` |
| `rk.lockScreenBehavior` | – | `fullScreen` (default) / `notificationOnly` |
| `rk.colors.accept` / `rk.colors.decline` / `rk.colors.accent` | – | `#RRGGBB` accent hints |
| `rk.background` | – | `#RRGGBB` or an `https://` image for the full-screen scrim |
| `rk.missedCall.show` / `.title` / `.subtitle` / `.callbackText` / `.showCallback` | – | missed-call surface copy/toggles |
| `rk.useTelecom` | – | opt this call into the telecom integration (when enabled) |
| `rk.reason` *(cancel only)* | – | `callerCancelled` (default) / `answeredElsewhere` / `declinedElsewhere` |

Unknown `rk.*` keys are ignored (forward-compatible). An invalid value for an optional key falls
back to its default; an invalid **required** key rejects the message (and is traced).

**Cancel** is a second push — `{ "rk.type": "cancel", "rk.callId": "<same id>", "rk.reason": "…" }` —
sent when the caller hangs up or the call is answered/declined on another device. It tears down
the ringing UI everywhere the call is ringing, even if the app is dead, and drives the right
missed-call copy.

### Copy-paste (FCM HTTP v1)

```json
{
  "message": {
    "token": "<device-token>",
    "data": {
      "rk.type": "incoming",
      "rk.callId": "b3f1c8e2-...",
      "rk.callerName": "Alice Alvarez",
      "rk.callerHandle": "+15551234567",
      "rk.callType": "audio",
      "rk.timeoutSeconds": "45",
      "rk.sentAt": "1717000000000",
      "rk.extra": "{\"roomId\":\"room-42\"}"
    },
    "android": { "priority": "high" }
  }
}
```

---

## 2. Server rules that matter

- **Data-only, `"priority": "high"`.** High priority is what wakes a dozing device and grants the
  brief background foreground-service start window that lets a killed app ring. A `notification`
  block breaks call delivery.
- **Send `rk.sentAt`.** A push delayed past the call's timeout is recorded as a missed call and
  **never rings** — better than ringing a caller who already gave up. Without it, ringkit can't
  tell a fresh call from a stale one.
- **Never send high-priority *non-call* messages from the same app.** FCM watches whether your
  high-priority messages produce user-visible results; if you spend that budget on silent
  background syncs, FCM starts **de-prioritizing** your app (a rolling, per-device penalty) — and
  a de-prioritized "high" push is delivered as *normal*, silently losing the exemption that lets a
  killed app ring. Keep high priority for calls only. (Mode 2 detects and reports this downgrade —
  see "Observing it" below.)
- **Always send the cancel push** when the call ends before it's answered. It's the only thing
  that clears the ringing UI on every device and picks the right missed-call reason.
- **Run your own unanswered-call timeout, server-side.** A **force-stopped** app (Settings →
  Force stop, and several OEM "battery" killers) receives **no FCM at all**, by Android design —
  no plugin can ring it. Your server timeout is the only recourse for that state.
- **4 KB total payload cap** (FCM); keep `rk.extra` ≤ 1 KB.

---

## 3. Mode 1 — the Dart path (baseline)

Keep your normal `firebase_messaging` setup and call ringkit from your handlers. The full
handler is in the [Integration Guide](INTEGRATION.md#3-fcm-wiring-mode-1--the-current-baseline);
in short, a background isolate maps the `rk.*` payload to `showIncomingCall(...)` / `cancelCall(...)`.

**Do not `await` your own network before `showIncomingCall`** — the killed-app ring window is only
a few seconds from FCM receipt, and every awaited millisecond spends it.

---

## 4. Mode 2 — the native fast path

Turn it on with **one line** in your app's `AndroidManifest.xml` (inside `<application>`; add the
`tools` namespace to the `<manifest>` tag if it isn't there already):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
  <application>

    <receiver
        android:name="com.wibechat.ringkit.fcm.RingKitMessagingReceiver"
        android:enabled="true"
        tools:replace="android:enabled" />

  </application>
</manifest>
```

> `tools:replace="android:enabled"` is **required** — the receiver ships disabled, and a plain
> `android:enabled="true"` without `tools:replace` is a manifest-merger build error.

That's it. `rk.*` call pushes now ring **natively in < 300 ms**, even from a cold-killed app, with
Dart never on the critical path.

### Add one guard line to your handler

ringkit's Mode-2 receiver runs **alongside** `firebase_messaging`, so a call push *also* reaches
your Dart handlers (that's unavoidable and harmless). Add one line so you don't handle it twice —
ringkit already rang it natively:

```dart
@pragma('vm:entry-point')
Future<void> firebaseBackgroundHandler(RemoteMessage message) async {
  if (RingKit.isRingMessage(message.data)) return; // Mode 2: ringkit handled it natively
  // ... your app's own (non-call) messages continue here as normal ...
}
```

Do the same in your foreground `onMessage` listener. (In Mode 1 you do the opposite — you dispatch
these to `showIncomingCall`.)

### What stays exactly the same

Mode 2 is purely additive — it **never** touches `firebase_messaging`'s own delivery:

- **Your other (non-`rk`) messages** reach your Dart handlers unchanged, in every app state.
- **Token refresh** (`onTokenRefresh` / `getToken`) is untouched — ringkit is not on that path.
- You still manage the FCM token yourself; ringkit never reads or touches it.

No server changes: Mode 2 reads the same `rk.*` payload as Mode 1.

---

## 5. Observing it

- `await RingKit.diagnose()` reports the last show result, the last ring traces, and — in Mode 2 —
  whether a call push arrived **downgraded** (delivered at normal priority after you asked for
  high). A downgrade means FCM is de-prioritizing your app; fix it by not sending high-priority
  non-call messages (see "Server rules" above).
- `adb logcat -s RingKit:V` shows the per-call ring trace — including deliberate silence (e.g.
  Do-Not-Disturb) so you can tell "muted on purpose" from "didn't ring".

---

## 6. Choosing a mode

| | Mode 1 (Dart) | Mode 2 (native) |
|---|---|---|
| Setup | none (just call ringkit from your handler) | one manifest line + one guard line |
| Killed-state ring latency | ~1–3 s (Dart isolate cold-starts) | **< 300 ms** (no Dart) |
| Requires `firebase_messaging` | your app already has it | your app already has it |
| Server payload | `rk.*` | **same `rk.*`** |

Start on Mode 1; switch to Mode 2 when killed-state latency matters. Because the payload is
identical, flipping is a client-only change.
