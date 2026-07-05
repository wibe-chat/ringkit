# ringkit_example

The **manual test harness + integration reference** for ringkit — not a demo.
Three tabs: **Harness** (every call scenario + live event console with `deliveryPath`/`sequence`
badges + latency probe), **Diagnostics** (`diagnose()` as a fix-it checklist with deep links +
ring-trace viewer), **Permissions** (the recommended onboarding flow).

## Backend-free triggers (adb)

```bash
PKG=com.wibechat.ringkit_example
adb shell cmd deviceidle tempwhitelist -d 15000 $PKG    # mirrors FCM's temp-allowlist (bg/killed)
adb shell am broadcast -n $PKG/.SimulateCallReceiver -a $PKG.SIMULATE_CALL --es name Alice
adb shell am broadcast -n $PKG/.SimulateCallReceiver -a $PKG.SIMULATE_CANCEL --es callId <id> [--es reason answeredElsewhere]
adb shell am broadcast -n $PKG/.SimulateCallReceiver -a $PKG.SIMULATE_ACTION --es callId <id> --es action <accept|decline|timeout|hangup|endRemote|connect>
adb shell am broadcast -n $PKG/.SimulateCallReceiver -a $PKG.SIMULATE_OUTGOING --es name Bob
adb shell am broadcast -n $PKG/.SimulateCallReceiver -a $PKG.SIMULATE_UPDATE --es callId <id> --es name NewName
```

Every `SIMULATE_CALL` stamps `probeReceiptMs` into `extra` — the harness console prints the
**receipt→transition latency** per call.

## How to test killed-state in 60 seconds

1. Trigger a call, let it ring, **swipe the app away** (or `adb shell am kill $PKG` — note: only
   works while the app is background and holds no foreground service).
2. Act on the notification (accept/decline) — the native core handles it with **no Flutter engine**.
3. Relaunch the app: the event console replays the outcome (`[replayed]` badge) and, for accept,
   `consumeLaunchAction()` reports the one-shot launch cause with the same sequence number.

**The trap:** *swipe-away / `am kill` = killed* — FCM (and these local triggers) still
arrive. **Settings → Force stop / `adb shell am force-stop` = STOPPED state** — Android delivers
*nothing* (no FCM, no broadcasts) until the user relaunches the app. A stopped app that "doesn't
ring" is OS design, not a plugin bug. `am force-stop` also clears the FCM temp-allowlist mirror,
so use `am kill` for killed-state tests.

## FCM (Mode 1 / Mode 2)

Real-push wiring (`firebase_messaging` + `tools/send_call.sh`, HTTP v1 + service account) lands
with the Mode-2 milestone — this harness is deliberately Firebase-free until then so the
no-Firebase consumer build stays honest.
