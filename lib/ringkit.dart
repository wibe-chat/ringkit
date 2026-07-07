/// ringkit — Android-only CallKit-style incoming-call UX (PLAN §6).
///
/// Signaling UX only: ring + full-screen + notification + killed-state events.
/// No iOS, no RTC/media, no push transport (§2.2).
library;

import 'dart:ui'
    show
        CallbackHandle,
        DartPluginRegistrant,
        PluginUtilities,
        RootIsolateToken;

import 'package:flutter/widgets.dart'
    show WidgetsFlutterBinding, visibleForTesting;

import 'src/event_hub.dart';
import 'src/events.dart';
import 'src/messages.g.dart';
import 'src/permissions.dart';

export 'src/events.dart';
export 'src/messages.g.dart'
    hide
        BackgroundDelivery,
        RingKitBackgroundApi,
        RingKitHostApi,
        RingKitPermissionsApi,
        streamCallEvents;
export 'src/permissions.dart';

/// A registered background handler receives actionable events (decline,
/// timeout, cancel) in a headless isolate when no live engine exists (§10.4).
typedef BackgroundEventHandler = Future<void> Function(CallEvent event);

/// Headless-engine entrypoint (§10.2). The [BackgroundEngineRunner] boots the
/// engine here; it pulls the leased actionable batch + the app's handler handle,
/// invokes the handler once per event, and acks each ONLY after the handler
/// Future completes — a throwing or never-completing handler leaves the event
/// un-acked, so its lease expires and it replays on next launch (§10.2, §10.4).
@pragma('vm:entry-point')
Future<void> ringkitBackgroundDispatcher() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Attach plugins in this background isolate so the private channel resolves.
  DartPluginRegistrant.ensureInitialized();
  await runBackgroundDispatch(RingKitBackgroundApi());
}

/// The dispatcher's logic, isolated from the engine bootstrap for unit testing.
/// [resolveHandler] is seamed so tests need not exercise the VM callback
/// registry; production resolves the 64-bit handle via [PluginUtilities].
@visibleForTesting
Future<void> runBackgroundDispatch(
  RingKitBackgroundApi api, {
  BackgroundEventHandler? Function(int rawHandle) resolveHandler =
      resolveBackgroundHandler,
}) async {
  final delivery = await api.fetchBackgroundDelivery();
  final handler = resolveHandler(delivery.handlerHandle);
  // Dart-side staleness guard (native already screens the handle, §10.4): an
  // unresolvable handle leaves the events un-acked for replay.
  if (handler != null) {
    for (final record in delivery.events) {
      try {
        await handler(mapEventRecord(record));
        await api.ackBackgroundEvent(record.sequence);
      } catch (_) {
        // Handler threw → do NOT ack → lease expiry → sweep reap → replay.
      }
    }
  }
  await api.finishBackgroundDelivery();
}

/// Resolves a persisted 64-bit handler handle to the registered top-level
/// function, or null if it no longer resolves (app updated — handles are not
/// stable across updates, §10.4).
@visibleForTesting
BackgroundEventHandler? resolveBackgroundHandler(int rawHandle) {
  final resolved = PluginUtilities.getCallbackFromHandle(
    CallbackHandle.fromRawHandle(rawHandle),
  );
  return resolved is BackgroundEventHandler ? resolved : null;
}

/// The facade (PLAN §6.1). All commands are isolate-safe and work
/// pre-initialize using persisted-or-built-in config (§8.10); [events] and
/// [consumeLaunchAction] are main-engine-only (§10.3).
abstract final class RingKit {
  static RingKitHostApi? _hostApiOverride;
  static RingKitPermissionsApi? _permissionsApiOverride;
  static Stream<CallEventRecord>? _channelOverride;
  static bool? _isBackgroundIsolateOverride;

  static RingKitHostApi? _hostApi;
  static EventHub? _hub;
  static RingKitPermissions? _permissions;

  static RingKitHostApi get _api =>
      _hostApi ??= _hostApiOverride ?? RingKitHostApi();

  static EventHub get _events =>
      _hub ??= EventHub(_api, channel: _channelOverride ?? streamCallEvents());

  static bool get _isBackgroundIsolate =>
      _isBackgroundIsolateOverride ?? RootIsolateToken.instance == null;

  /// Idempotent; safe in main() and background isolates. Persists config
  /// natively (versioned, last-writer-wins) so cold-native paths have it.
  /// Invalid values are clamped and surfaced in diagnostics, never thrown.
  static Future<void> initialize(RingKitConfig config) =>
      _api.initialize(config);

  /// True iff [data] is a RingKit call push (carries any `rk.*` key, §7.5) —
  /// i.e. it should be handled by RingKit, not the app's own message logic.
  ///
  /// **Mode 2 guard.** With the native FCM receiver enabled (§7.3), RingKit
  /// rings `rk.*` pushes natively; the same push is ALSO co-delivered to
  /// `firebase_messaging`'s Dart handlers (FlutterFire's receiver is
  /// independent and cannot be suppressed). Return early on it in your
  /// `onBackgroundMessage`/`onMessage` handler so Mode 2 never double-rings:
  ///
  /// ```dart
  /// if (RingKit.isRingMessage(message.data)) return; // native handled it
  /// ```
  ///
  /// (In Mode 1 you do the opposite — dispatch these to [showIncomingCall].)
  /// Pure and isolate-safe: no platform channel, usable in a headless isolate.
  static bool isRingMessage(Map<String, Object?> data) =>
      data.keys.any((key) => key.startsWith('rk.'));

  // ── Core commands (§6.1 — all isolate-safe, all typed results) ────────

  static Future<ShowCallResult> showIncomingCall(IncomingCall call) {
    final misuse = _validateId(call.id) ?? _validateExtra(call.extra);
    if (misuse != null) {
      return Future<ShowCallResult>.value(
        ShowCallResult(
          code: ShowCallResultCode.failed,
          failReason: FailReason.invalidParams,
        ),
      );
    }
    return _api.showIncomingCall(call);
  }

  /// In-app answer UIs, automation, tests (§6.1) — the identical native
  /// accept path; never sets a launch action (§4.3).
  static Future<CallCommandResult> acceptCall(String callId) =>
      _guardId(callId) ?? _api.acceptCall(callId);

  static Future<CallCommandResult> declineCall(String callId) =>
      _guardId(callId) ?? _api.declineCall(callId);

  static Future<CallCommandResult> endCall(
    String callId, {
    EndReason reason = EndReason.local,
  }) => _guardId(callId) ?? _api.endCall(callId, reason);

  /// Remote-party cancellation with a reason (pre-answer). Post-accept
  /// cancels are ignored (local accept wins — §5.1); far-end hangup of a
  /// LIVE call is [endCall] with [EndReason.remote].
  static Future<CallCommandResult> cancelCall(
    String callId, {
    CancelReason reason = CancelReason.callerCancelled,
  }) => _guardId(callId) ?? _api.cancelCall(callId, reason);

  static Future<void> endAllCalls() => _api.endAllCalls();

  static Future<CallCommandResult> setCallConnected(String callId) =>
      _guardId(callId) ?? _api.setCallConnected(callId);

  static Future<ShowCallResult> startOutgoingCall(OutgoingCall call) {
    final misuse = _validateId(call.id) ?? _validateExtra(call.extra);
    if (misuse != null) {
      return Future<ShowCallResult>.value(
        ShowCallResult(
          code: ShowCallResultCode.failed,
          failReason: FailReason.invalidParams,
        ),
      );
    }
    return _api.startOutgoingCall(call);
  }

  static Future<CallCommandResult> updateCallInfo(
    String callId,
    CallInfoUpdate update,
  ) => _guardId(callId) ?? _api.updateCallInfo(callId, update);

  /// Telecom module only (telecomIntegration: true + an active call);
  /// otherwise returns wrongState.
  static Future<CallCommandResult> setAudioRoute(AudioRoute route) =>
      _api.setAudioRoute(route);

  static Future<List<ActiveCall>> activeCalls() => _api.activeCalls();

  // ── Events (THE single integration point, §10.3) ───────────────────────

  /// Unified stream — MAIN (designated) engine only. Buffers until the first
  /// listener; replays unconsumed events in seq order; ack-gated;
  /// effectively-once with [CallEvent.sequence] for app-side idempotency in
  /// the documented duplicate windows (§6.2).
  static Stream<CallEvent> get events {
    if (_isBackgroundIsolate) {
      throw StateError(
        'RingKit.events is main-engine-only (§10.3). In background isolates, '
        'commands remain available; events are delivered via the registered '
        'background handler or replayed on next launch.',
      );
    }
    return _events.events;
  }

  /// One-shot launch cause (§6.1): accept via notification/FSI, missed-call
  /// tap, or accept-raced-cancel (alreadyEnded). Clears on read; persisted
  /// natively — never raw intent extras (R44). Startup recipe: subscribe to
  /// [events] FIRST, then call this.
  static Future<CallLaunchAction?> consumeLaunchAction() {
    if (_isBackgroundIsolate) {
      throw StateError(
        'RingKit.consumeLaunchAction is main-engine-only (§10.3).',
      );
    }
    return _api.consumeLaunchAction();
  }

  /// Top-level/static function only — throws [ArgumentError] at registration
  /// if a callback handle cannot be extracted. Re-register on every launch
  /// (handles are NOT stable across app updates; stale handles degrade to
  /// replay natively, never crash) (§10.4).
  static Future<void> setBackgroundEventHandler(
    BackgroundEventHandler handler,
  ) {
    final handlerHandle = PluginUtilities.getCallbackHandle(handler);
    if (handlerHandle == null) {
      throw ArgumentError(
        'setBackgroundEventHandler requires a top-level or static function '
        '(closures and instance methods have no callback handle).',
      );
    }
    final dispatcherHandle = PluginUtilities.getCallbackHandle(
      ringkitBackgroundDispatcher,
    )!;
    return _api.registerBackgroundEventHandler(
      dispatcherHandle.toRawHandle(),
      handlerHandle.toRawHandle(),
    );
  }

  static RingKitPermissions get permissions => _permissions ??=
      RingKitPermissions(_permissionsApiOverride ?? RingKitPermissionsApi());

  /// §11.3 — the support-load killer. Serializable; attach it to bug reports.
  static Future<DiagnosticsReport> diagnose() => _api.diagnose();

  // ── misuse rules (§6.1/§6.4) — native re-enforces; this keeps results
  //    typed and instant without crossing the bridge ─────────────────────

  static String? _validateId(String id) {
    if (id.trim().isEmpty) return 'blank callId';
    if (id.length > 128) return 'callId over 128 chars';
    return null;
  }

  static Future<CallCommandResult>? _guardId(String callId) =>
      _validateId(callId) == null
      ? null
      : Future<CallCommandResult>.value(CallCommandResult.invalidParams);

  static String? _validateExtra(Map<String, String> extra) {
    var bytes = 2;
    extra.forEach((k, v) {
      bytes += k.codeUnits.length + v.codeUnits.length + 6;
    });
    return bytes > 1024 ? 'extra over 1 KB serialized' : null;
  }

  // ── test seams ─────────────────────────────────────────────────────────

  /// Test-only: inject fakes for the generated APIs and the event channel.
  static void debugConfigure({
    RingKitHostApi? hostApi,
    RingKitPermissionsApi? permissionsApi,
    Stream<CallEventRecord>? eventChannel,
    bool? isBackgroundIsolate,
  }) {
    _hostApiOverride = hostApi;
    _permissionsApiOverride = permissionsApi;
    _channelOverride = eventChannel;
    _isBackgroundIsolateOverride = isBackgroundIsolate;
  }

  /// Test-only: drop all lazies (paired with [debugConfigure]).
  static Future<void> debugReset() async {
    await _hub?.dispose();
    _hub = null;
    _hostApi = null;
    _permissions = null;
    _hostApiOverride = null;
    _permissionsApiOverride = null;
    _channelOverride = null;
    _isBackgroundIsolateOverride = null;
  }
}
