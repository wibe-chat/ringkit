#!/usr/bin/env bash
# Send a ringkit rk.* push over real FCM (HTTP v1) using a service account.
#
#   tools/send_call.sh <device-fcm-token> [options]
#
# The device token is printed by the example app on launch ("📱 FCM token: …").
# Options (all optional):
#   --type incoming|cancel   default incoming
#   --callId <id>            default call-<epoch>
#   --name <caller>          default Alice
#   --handle <e164>          default +15550100
#   --callType audio|video   default audio
#   --timeout <seconds>      default 45
#   --reason <r>             cancel only: callerCancelled|answeredElsewhere|declinedElsewhere
#
# Auth: mints an OAuth2 access token from tools/service-account.json (override with
# RINGKIT_SA=/path). No gcloud needed — JWT is signed with openssl. Legacy server
# keys are dead; HTTP v1 + service account is the only path (§7.1).
set -euo pipefail

for bin in jq openssl curl; do
  command -v "$bin" >/dev/null || { echo "missing dependency: $bin" >&2; exit 1; }
done

SA="${RINGKIT_SA:-$(cd "$(dirname "$0")" && pwd)/service-account.json}"
[ -f "$SA" ] || { echo "service account not found: $SA (see SCOPE-2 prereqs)" >&2; exit 1; }

[ $# -ge 1 ] || { grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -20; exit 1; }
DEVICE_TOKEN="$1"; shift

TYPE=incoming
CALL_ID="call-$(date +%s)"
NAME="Alice"
HANDLE="+15550100"
CALL_TYPE=audio
TIMEOUT=45
REASON=callerCancelled
while [ $# -gt 0 ]; do
  case "$1" in
    --type) TYPE="$2"; shift 2;;
    --callId) CALL_ID="$2"; shift 2;;
    --name) NAME="$2"; shift 2;;
    --handle) HANDLE="$2"; shift 2;;
    --callType) CALL_TYPE="$2"; shift 2;;
    --timeout) TIMEOUT="$2"; shift 2;;
    --reason) REASON="$2"; shift 2;;
    *) echo "unknown option: $1" >&2; exit 1;;
  esac
done

PROJECT_ID=$(jq -r '.project_id' "$SA")
CLIENT_EMAIL=$(jq -r '.client_email' "$SA")

# --- mint an OAuth2 access token (signed JWT bearer grant) ---
b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s); exp=$((now + 3600))
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
claim=$(printf '{"iss":"%s","scope":"https://www.googleapis.com/auth/firebase.messaging","aud":"https://oauth2.googleapis.com/token","iat":%s,"exp":%s}' \
  "$CLIENT_EMAIL" "$now" "$exp" | b64url)
signature=$(printf '%s' "$header.$claim" \
  | openssl dgst -sha256 -sign <(jq -r '.private_key' "$SA") | b64url)
jwt="$header.$claim.$signature"
access_token=$(curl -s -X POST https://oauth2.googleapis.com/token \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$jwt" | jq -r '.access_token // empty')
[ -n "$access_token" ] || { echo "failed to mint access token (check the service account)" >&2; exit 1; }

# --- build the rk.* data payload (§7.5; all values are strings per FCM v1) ---
if [ "$TYPE" = "cancel" ]; then
  DATA=$(jq -n --arg id "$CALL_ID" --arg r "$REASON" \
    '{"rk.type":"cancel","rk.callId":$id,"rk.reason":$r}')
else
  DATA=$(jq -n --arg id "$CALL_ID" --arg n "$NAME" --arg h "$HANDLE" \
    --arg ct "$CALL_TYPE" --arg to "$TIMEOUT" --arg sent "$((now * 1000))" \
    '{"rk.type":"incoming","rk.callId":$id,"rk.callerName":$n,"rk.callerHandle":$h,
      "rk.callType":$ct,"rk.timeoutSeconds":$to,"rk.sentAt":$sent}')
fi
# android.priority=high → the killed-state FGS-exemption window (§4.2/§7.1).
MSG=$(jq -n --arg t "$DEVICE_TOKEN" --argjson d "$DATA" \
  '{message:{token:$t, data:$d, android:{priority:"high"}}}')

echo "→ rk.$TYPE callId=$CALL_ID project=$PROJECT_ID"
curl -s -X POST "https://fcm.googleapis.com/v1/projects/$PROJECT_ID/messages:send" \
  -H "Authorization: Bearer $access_token" \
  -H 'Content-Type: application/json' \
  -d "$MSG" | jq .
