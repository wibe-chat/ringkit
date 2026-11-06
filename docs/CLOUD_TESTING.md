# Cloud Android devices

RingKit can run its existing integration tests on physical Android devices in
[Google Developer Device Platform](https://docs.cloud.google.com/developer-device-platform/quickstart).
Device Streaming exposes a remote device over ADB; Device Run accepts packaged
automated tests. Billing must be enabled, and paid runs should be started manually.
Check [current pricing](https://cloud.google.com/products/developer-device-platform)
before reserving devices.

## One-time setup

Install the current Google Cloud CLI, then sign in with your own Google account:

```sh
gcloud auth login --update-adc
gcloud components install beta
```

Use a dedicated testing project with billing enabled. Replace `YOUR_PROJECT` below:

```sh
gcloud services enable devicerun.googleapis.com devicestreaming.googleapis.com \
  testing.googleapis.com storage.googleapis.com --project=YOUR_PROJECT
gcloud auth application-default set-quota-project YOUR_PROJECT
```

Google's preview requires explicit Device Run Admin and Device Streaming Admin
roles; project Owner alone may not grant the new preview permissions. Follow the
[required roles](https://docs.cloud.google.com/developer-device-platform/quickstart#required_roles)
and allow time for IAM changes to propagate. No service-account JSON key is needed
for interactive local testing. Keep OAuth credentials outside this repository.

## Build before reserving a device

```sh
cd example
flutter pub get
flutter build apk --debug
```

The example builds without `google-services.json`. These integration tests use
local native triggers and do not need real FCM credentials or fleet telemetry keys.

## Connect and test

List the live catalog rather than relying on an old model list:

```sh
gcloud beta device-run devices list --project=YOUR_PROJECT
```

Follow Google's [Device Streaming workflow](https://docs.cloud.google.com/developer-device-platform/agent-skill)
to reserve a device and start its official Python ADB forwarder. Select a device
that supports `deviceStreaming`, set a short `ttl` (for example `600s`), and wait
for the session to become `ACTIVE` before connecting. Install the forwarder's
requirements in a separate Python virtual environment. Keep the returned session
name for cancellation.

After connecting, substitute the actual ADB address for `localhost:PORT`:

```sh
adb -s localhost:PORT shell getprop ro.product.model
adb -s localhost:PORT shell getprop ro.build.version.sdk
adb -s localhost:PORT install -r build/app/outputs/flutter-apk/app-debug.apk
# Android 13+ only:
adb -s localhost:PORT shell pm grant com.wibechat.ringkit_example android.permission.POST_NOTIFICATIONS
# Android 14+; required for the full-screen test scenario:
adb -s localhost:PORT shell appops set com.wibechat.ringkit_example USE_FULL_SCREEN_INTENT allow
flutter test integration_test/ring_flow_test.dart -d localhost:PORT
# Flutter removes the test app afterward. Reinstall and restore permissions:
adb -s localhost:PORT install -r build/app/outputs/flutter-apk/app-debug.apk
adb -s localhost:PORT shell pm grant com.wibechat.ringkit_example android.permission.POST_NOTIFICATIONS
adb -s localhost:PORT shell appops set com.wibechat.ringkit_example USE_FULL_SCREEN_INTENT allow
flutter test integration_test/ringtone_recovery_test.dart -d localhost:PORT
```

Use normal ringer mode, nonzero ring volume and DND disabled for the recovery test.
Record audio settings, RingKit logcat, `dumpsys notification`, `dumpsys activity
services`, and the test's `diagnose()` output. The recovery test checks reported
native playback state; it cannot establish physical audibility.

The official streaming forwarder currently rejects `adb reverse`. The existing
`tools/killed_state_harness.sh` uses reverse forwarding for its host-side HTTP
capture, so do not treat that harness as supported by this tunnel without
adapting its capture transport. Native cold-process show/cancel probes can still
run over ADB. Keep local physical-device coverage for audible ringing and the
full background-delivery scenario.

Cancel the cloud session through the Device Streaming API when finished, then
stop the forwarder and confirm the session is `FINISHED` (or `EXPIRED`). Stopping
only the local forwarder does not release the cloud reservation. The short TTL
is an additional bound, not a substitute for explicit cancellation.
