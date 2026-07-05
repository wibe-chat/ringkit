#!/usr/bin/env bash
# Two-phase killed-state harness (PLAN §14.1(4); SCOPE-1 M2 exit).
#
# Phase A arms state via the example's debug SIMULATE_* triggers (the Mode-2
# parser path) against a COLD/killed process; adb kills, acts, and relaunches;
# phase B asserts replay, tombstones, and sweep results from PERSISTED state by
# grepping the app's RingKit ring traces + the harness's Dart console prints.
# No UI taps — deterministic on headless CI emulators (API 26/31/33/34/36).
#
# Usage: tools/killed_state_harness.sh [serial]
set -u
SERIAL="${1:-}"
ADB="adb ${SERIAL:+-s $SERIAL}"
PKG=com.wibechat.ringkit_example
RECEIVER="$PKG/.SimulateCallReceiver"
FAILURES=0
API=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')

say()  { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

# Wait until `eval $2` output contains $3 (timeout $1 s).
wait_for() {
  local timeout=$1 cmd=$2 want=$3 t=0
  while [ $t -lt "$timeout" ]; do
    if eval "$cmd" 2>/dev/null | grep -qE "$want"; then return 0; fi
    sleep 1; t=$((t + 1))
  done
  return 1
}

simulate() { # simulate <callId> <extra am args...>
  local id=$1; shift
  $ADB shell cmd deviceidle tempwhitelist -d 20000 $PKG >/dev/null 2>&1
  local out
  out=$($ADB shell "am broadcast -n $RECEIVER -a $PKG.SIMULATE_CALL --es callId $id $*" 2>&1)
  case "$out" in
    *"Broadcast completed"*) : ;;
    *) echo "  [simulate $id] broadcast anomaly: $out" ;;
  esac
}
sim_cancel() { $ADB shell "am broadcast -n $RECEIVER -a $PKG.SIMULATE_CANCEL --es callId $1 ${2:+--es reason $2}" >/dev/null; }
sim_action() {
  $ADB shell cmd deviceidle tempwhitelist -d 20000 $PKG >/dev/null 2>&1
  $ADB shell "am broadcast -n $RECEIVER -a $PKG.SIMULATE_ACTION --es callId $1 --es action $2" >/dev/null
}
# Arm a ring and wait for its notification; retry ONCE on silence — OEM
# background-launch throttles (One UI) can eat a cold spawn; the retry is
# loud so a green run tells the truth. AOSP CI emulators never need it.
arm_ring() { # arm_ring <callId> <extra am args...>
  local id=$1; shift
  simulate "$id" "$*"
  if wait_for 10 "incoming_count" "^[1-9]"; then return 0; fi
  echo "  [arm_ring $id] no notification in 10 s — retrying once (OEM throttle?)"
  simulate "$id" "$*"
  wait_for 10 "incoming_count" "^[1-9]"
}

incoming_count() { $ADB shell dumpsys notification 2>/dev/null | grep -cE "NotificationRecord.*$PKG.*ringkit_incoming"; }
missed_count()   { $ADB shell dumpsys notification 2>/dev/null | grep -cE "NotificationRecord.*$PKG.*ringkit_missed"; }
ringkit_log()    { $ADB logcat -d -s RingKit:V 2>/dev/null; }
flutter_log()    { $ADB logcat -d -s flutter:V 2>/dev/null; }
relaunch() { $ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 6; }
# 3 s settle after force-stop: a broadcast fired while the teardown is still
# in flight is silently dropped on some OEMs (observed: One UI, API 31).
# Wake the screen each scenario — OEMs defer cold spawns harder when dark.
fresh() {
  $ADB shell am force-stop $PKG
  sleep 3
  $ADB shell input keyevent 224 >/dev/null 2>&1 || true
  $ADB logcat -c 2>/dev/null || true
}

say "harness start (API $API)"
$ADB shell input keyevent 224 >/dev/null 2>&1 || true # wake
if [ "$API" -ge 33 ]; then
  $ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
fi

# Prime: one FRESH app launch so initialize() persists logLevel=info — a
# fresh install's cold-install default is ERROR (§8.10), which would hide the
# INFO ring traces every logcat assertion below greps for.
$ADB shell am force-stop $PKG
sleep 1
$ADB logcat -c 2>/dev/null || true
relaunch
if wait_for 20 "flutter_log" "\[harness\] _start"; then
  pass "app primed (initialize persisted logLevel=info)"
else
  fail "app never booted — aborting"
  exit 1
fi

# ── 1. killed-state ring + native timeout + replay ──────────────────────
say "1: killed ring → timeout → missed → replay"
fresh
if arm_ring ks-timeout "--es name KsTimeout --es timeoutSeconds 12"; then
  pass "incoming notification posted"
else fail "no incoming notification"; fi
if wait_for 5 "ringkit_log" "\[show\] outcome=SHOWN.*call=ks-timeout"; then
  pass "cold process rang (SHOWN traced)"
else fail "no SHOWN trace for ks-timeout"; fi
if wait_for 40 "missed_count" "^[1-9]"; then pass "missed-call posted after timeout"; else fail "no missed-call after timeout"; fi
if [ "$(incoming_count)" = "0" ]; then pass "incoming torn down"; else fail "incoming lingers post-timeout"; fi
relaunch
if wait_for 15 "flutter_log" "event #[0-9]+ CallTimedOut"; then
  pass "CallTimedOut replayed to Dart on relaunch"
else fail "no CallTimedOut replay"; fi

# ── 2. out-of-order cancel (pre-tombstone) never rings ───────────────────
say "2: out-of-order cancel → show must NOT ring"
fresh
sim_cancel ks-ooo callerCancelled
sleep 2
simulate ks-ooo "--es name Ghost --es timeoutSeconds 20"
if wait_for 10 "ringkit_log" "never re-rings"; then pass "pre-tombstone rejected the show (R34/R41)"; else fail "no never-re-rings trace"; fi
sleep 1
if [ "$(incoming_count)" = "0" ]; then pass "zero incoming notifications"; else fail "ghost call rang"; fi

# ── 3. decline-while-dead: no missed, no app launch, replay ─────────────
say "3: killed ring → decline action → replay"
fresh
arm_ring ks-decline "--es name KsDecline --es timeoutSeconds 45" || fail "ring never posted (decline scenario)"
MISSED_BEFORE=$(missed_count)
sim_action ks-decline decline
if wait_for 10 "ringkit_log" "\[receiver\]|DECLINED|reject" || true; then :; fi
sleep 3
if [ "$(incoming_count)" = "0" ]; then pass "incoming torn down on decline"; else fail "incoming lingers post-decline"; fi
if [ "$(missed_count)" = "$MISSED_BEFORE" ]; then pass "decline posts NO missed-call (§4.3)"; else fail "decline posted a missed-call"; fi
if $ADB shell dumpsys activity activities 2>/dev/null | grep -q "ResumedActivity.*$PKG"; then
  fail "decline launched the app (#768 regression)"
else pass "decline never launches the app"; fi
relaunch
if wait_for 15 "flutter_log" "event #[0-9]+ CallDeclined"; then pass "CallDeclined replayed"; else fail "no CallDeclined replay"; fi

# ── 4. accept-while-dead: launch action + connecting survives ────────────
say "4: killed ring → accept action → launch action on next start"
fresh
arm_ring ks-accept "--es name KsAccept --es timeoutSeconds 45" || fail "ring never posted (accept scenario)"
sim_action ks-accept accept
sleep 3
if $ADB shell dumpsys notification 2>/dev/null | grep -qE "NotificationRecord.*$PKG.*ringkit_ongoing"; then
  pass "Connecting… surface up (remove-vs-detach)"
else fail "no connecting surface after accept"; fi
relaunch
if wait_for 15 "flutter_log" "launch action: accepted"; then pass "launch action consumed on next start (R44)"; else fail "no launch action"; fi
if wait_for 10 "flutter_log" "event #[0-9]+ CallAccepted"; then pass "CallAccepted replayed"; else fail "no CallAccepted replay"; fi
sim_action ks-accept hangup; sleep 2 # cleanup

# ── 5. reconciliation sweep: force-stop mid-ring → swept on relaunch ─────
say "5: force-stop mid-ring → sweep TIMED_OUT, no ghost"
fresh
arm_ring ks-sweep "--es name KsSweep --es timeoutSeconds 40" || fail "ring never posted (sweep scenario)"
$ADB shell am force-stop $PKG
sleep 12 # clear the 10 s post-in-flight grace
$ADB logcat -c 2>/dev/null || true
relaunch
if wait_for 20 "ringkit_log" "\[sweep\] RINGING swept"; then pass "sweep killed the ghost (R32)"; else fail "no sweep trace"; fi
if wait_for 15 "flutter_log" "event #[0-9]+ CallTimedOut"; then pass "swept TIMED_OUT replayed"; else fail "no TIMED_OUT replay from sweep"; fi

say "result"
if [ $FAILURES -eq 0 ]; then
  echo "ALL SCENARIOS GREEN (API $API)"
  exit 0
else
  echo "$FAILURES assertion(s) FAILED (API $API)"
  exit 1
fi
