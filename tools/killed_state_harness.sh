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
#        RINGKIT_TELECOM=1 tools/killed_state_harness.sh [serial]   # §14.1(8)
#        telecom-ON parity: every SIMULATE_CALL carries useTelecom=true, so
#        each ring is a real Telecom addCall — same assertions must hold.
set -u
SERIAL="${1:-}"
ADB="adb ${SERIAL:+-s $SERIAL}"
PKG=com.wibechat.ringkit_example
RECEIVER="$PKG/.SimulateCallReceiver"
FAILURES=0
API=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
# Unique per-run callId prefix (R34/R41): terminal tombstones live ~10 min and
# gate showIncomingCall as FCM re-delivery protection — a rerun (or the telecom
# parity leg right after the OFF leg) reusing fixed ids would be silently and
# CORRECTLY refused by the plugin. Diagnosed 2026-07-17 (13-assert wipeout that
# healed after 10 min); fixed ids ARE the bug, not the plugin.
RUN="ks$(date +%s)"
TELECOM_ARGS=""
[ "${RINGKIT_TELECOM:-0}" = "1" ] && TELECOM_ARGS="--es useTelecom true"

# §10.2 background-delivery capture: the example's headless handler POSTs every
# event it receives to localhost:8099 (adb-reversed to this host). A killed-state
# ACTIONABLE event (TIMED_OUT/DECLINED/CANCELLED) is normally consumed by that
# handler (lease + completion-ack) and then never replays — replay covers it only
# when the background host lost the race (lease expired → sweep reap). Both are
# correct §10 outcomes; assertions below accept EITHER, requiring exactly-once
# delivery somewhere.
BG_CAPTURE=$(mktemp -t ringkit-bg-posts)
BG_SERVER_PID=""
if command -v python3 >/dev/null; then
  python3 - "$BG_CAPTURE" <<'PYEOF' &
import http.server, sys
capture = sys.argv[1]
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        with open(capture, "ab") as f:
            f.write(self.rfile.read(n) + b"\n")
        self.send_response(200); self.end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", 8099), H).serve_forever()
PYEOF
  BG_SERVER_PID=$!
  $ADB reverse tcp:8099 tcp:8099 >/dev/null 2>&1 || echo "  [warn] adb reverse failed — background POSTs won't be captured"
else
  echo "  [warn] python3 missing — background POSTs won't be captured"
fi
cleanup() {
  if [ -n "$BG_SERVER_PID" ]; then
    kill "$BG_SERVER_PID" 2>/dev/null
    wait "$BG_SERVER_PID" 2>/dev/null
  fi
  $ADB reverse --remove tcp:8099 >/dev/null 2>&1
}
trap cleanup EXIT

bg_posts() { cat "$BG_CAPTURE" 2>/dev/null; }
# delivered <EventType> <callId>: replayed to the relaunched Dart engine OR
# consumed by the headless background handler (POST observed).
delivered() {
  flutter_log | grep -qE "#[0-9]+ $1" && return 0
  bg_posts | grep -q "\"type\":\"$1\",\"callId\":\"$2\"" && return 0
  return 1
}
# wait_until <timeout> <predicate...>: poll a shell function until it succeeds.
wait_until() {
  local timeout=$1 t=0; shift
  while [ $t -lt "$timeout" ]; do
    if "$@"; then return 0; fi
    sleep 1; t=$((t + 1))
  done
  return 1
}

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
  out=$($ADB shell "am broadcast -n $RECEIVER -a $PKG.SIMULATE_CALL --es callId $id $TELECOM_ARGS $*" 2>&1)
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

incoming_count() { $ADB shell dumpsys notification 2>/dev/null | grep -E "NotificationRecord.*$PKG.*ringkit_incoming" | grep -cv ranker_group; }
missed_count()   { $ADB shell dumpsys notification 2>/dev/null | grep -E "NotificationRecord.*$PKG.*ringkit_missed" | grep -cv ranker_group; }
ringkit_log()    { $ADB logcat -d -s RingKit:V 2>/dev/null; }
flutter_log()    { $ADB logcat -d -s flutter:V 2>/dev/null; }

# Failure diagnostics INLINE (post-step logcat races the emulator teardown).
dump_state() {
  echo "  --- diag: NotificationRecords ---"
  $ADB shell dumpsys notification 2>/dev/null | grep -E "NotificationRecord.*$PKG" | sed 's/^/  | /'
  echo "  --- diag: ringkit services ---"
  $ADB shell dumpsys activity services $PKG 2>/dev/null | grep -E "ServiceRecord|isForeground" | sed 's/^/  | /'
  echo "  --- diag: last RingKit traces ---"
  ringkit_log | tail -25 | sed 's/^/  | /'
}
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
if wait_for 90 "flutter_log" "\[harness\] _start"; then
  pass "app primed (initialize persisted logLevel=info)"
else
  fail "app never booted — aborting"
  exit 1
fi

# ── 1. killed-state ring + native timeout + replay ──────────────────────
say "1: killed ring → timeout → missed → replay"
fresh
if arm_ring ${RUN}-timeout "--es name KsTimeout --es timeoutSeconds 12"; then
  pass "incoming notification posted"
else fail "no incoming notification"; fi
if wait_for 5 "ringkit_log" "\[show\] outcome=SHOWN.*call=${RUN}-timeout"; then
  pass "cold process rang (SHOWN traced)"
else fail "no SHOWN trace for ${RUN}-timeout"; fi
if wait_for 40 "missed_count" "^[1-9]"; then pass "missed-call posted after timeout"; else fail "no missed-call after timeout"; fi
if wait_for 10 "incoming_count" "^0$"; then pass "incoming torn down"; else fail "incoming lingers post-timeout"; dump_state; fi
relaunch
if wait_until 45 delivered CallTimedOut ${RUN}-timeout; then
  pass "CallTimedOut delivered (background handler or replay, §10.2)"
else fail "CallTimedOut LOST — neither background POST nor replay"; fi

# ── 2. out-of-order cancel (pre-tombstone) never rings ───────────────────
say "2: out-of-order cancel → show must NOT ring"
fresh
sim_cancel ${RUN}-ooo callerCancelled
sleep 2
simulate ${RUN}-ooo "--es name Ghost --es timeoutSeconds 20"
if wait_for 10 "ringkit_log" "never re-rings"; then pass "pre-tombstone rejected the show (R34/R41)"; else fail "no never-re-rings trace"; fi
sleep 1
if [ "$(incoming_count)" = "0" ]; then pass "zero incoming notifications"; else fail "ghost call rang"; dump_state; fi

# ── 3. decline-while-dead: no missed, no app launch, replay ─────────────
say "3: killed ring → decline action → replay"
fresh
arm_ring ${RUN}-decline "--es name KsDecline --es timeoutSeconds 45" || fail "ring never posted (decline scenario)"
MISSED_BEFORE=$(missed_count)
sim_action ${RUN}-decline decline
if wait_for 10 "ringkit_log" "\[receiver\]|DECLINED|reject" || true; then :; fi
sleep 3
if wait_for 10 "incoming_count" "^0$"; then pass "incoming torn down on decline"; else fail "incoming lingers post-decline"; dump_state; fi
if [ "$(missed_count)" = "$MISSED_BEFORE" ]; then pass "decline posts NO missed-call (§4.3)"; else fail "decline posted a missed-call"; fi
if $ADB shell dumpsys activity activities 2>/dev/null | grep -q "ResumedActivity.*$PKG"; then
  fail "decline launched the app (#768 regression)"
else pass "decline never launches the app"; fi
relaunch
if wait_until 45 delivered CallDeclined ${RUN}-decline; then
  pass "CallDeclined delivered (background handler or replay, §10.2)"
else fail "CallDeclined LOST — neither background POST nor replay"; fi

# ── 4. accept-while-dead: launch action + connecting survives ────────────
say "4: killed ring → accept action → launch action on next start"
fresh
arm_ring ${RUN}-accept "--es name KsAccept --es timeoutSeconds 45" || fail "ring never posted (accept scenario)"
sim_action ${RUN}-accept accept
sleep 3
if $ADB shell dumpsys notification 2>/dev/null | grep -qE "NotificationRecord.*$PKG.*ringkit_ongoing"; then
  pass "Connecting… surface up (remove-vs-detach)"
else fail "no connecting surface after accept"; fi
relaunch
if wait_for 45 "flutter_log" "launch action: accepted"; then pass "launch action consumed on next start (R44)"; else fail "no launch action"; fi
if wait_for 45 "flutter_log" "#[0-9]+ CallAccepted"; then pass "CallAccepted replayed"; else fail "no CallAccepted replay"; fi
sim_action ${RUN}-accept hangup; sleep 2 # cleanup

# ── 5. reconciliation sweep: force-stop mid-ring → swept on relaunch ─────
say "5: force-stop mid-ring → sweep TIMED_OUT, no ghost"
fresh
arm_ring ${RUN}-sweep "--es name KsSweep --es timeoutSeconds 40" || fail "ring never posted (sweep scenario)"
$ADB shell am force-stop $PKG
sleep 12 # clear the 10 s post-in-flight grace
$ADB logcat -c 2>/dev/null || true
relaunch
if wait_for 20 "ringkit_log" "\[sweep\] RINGING swept"; then pass "sweep killed the ghost (R32)"; else fail "no sweep trace"; fi
if wait_until 45 delivered CallTimedOut ${RUN}-sweep; then
  pass "swept TIMED_OUT delivered (live/background/replay, §10.2)"
else fail "swept TIMED_OUT LOST — neither background POST nor replay"; fi

say "result"
if [ $FAILURES -eq 0 ]; then
  echo "ALL SCENARIOS GREEN (API $API)"
  exit 0
else
  echo "$FAILURES assertion(s) FAILED (API $API)"
  exit 1
fi
