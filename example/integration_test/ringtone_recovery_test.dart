import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:ringkit/ringkit.dart';

/// Run with notifications allowed, normal ringer mode, and DND disabled.
/// Confirms native playback state and lifecycle, not physical audibility.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  late StreamSubscription<CallEvent> subscription;
  final events = <CallEvent>[];

  setUpAll(() async {
    subscription = RingKit.events.listen(events.add);
    await RingKit.initialize(
      RingKitConfig(
        incomingChannelName: 'Incoming calls',
        missedChannelName: 'Missed calls',
        ongoingChannelName: 'Ongoing call',
        audibleChannelName: 'Incoming calls (fallback)',
        defaultTimeoutSeconds: 15,
        connectionTimeoutSeconds: 3,
        maxConcurrentRinging: 3,
        telecomIntegration: false,
        manageOngoingCall: true,
        foregroundBehavior: ForegroundBehavior.headsUp,
        notificationIdBase: 424200,
        logLevel: RingKitLogLevel.info,
        channelSuffix: '',
      ),
    );
    await RingKit.endAllCalls();
  });

  tearDownAll(() async {
    await RingKit.endAllCalls();
    debugPrint('RECOVERY_FINAL_DIAGNOSE ${await RingKit.diagnose()}');
    await subscription.cancel();
  });

  test(
    'unreadable ringtone recovers and connected call outlives watchdog',
    () async {
      final id = 'recovery-${DateTime.now().millisecondsSinceEpoch}';
      final result = await RingKit.showIncomingCall(
        IncomingCall(
          id: id,
          callerName: 'Recovery test',
          type: CallType.audio,
          sentAtEpochMs: DateTime.now().millisecondsSinceEpoch,
          extra: const <String, String>{},
          android: AndroidCallOptions(
            ringtone: 'file:///ringkit-intentionally-missing.ogg',
            vibrate: true,
            lockScreenVisibility: NotificationVisibility.public,
            lockScreenBehavior: LockScreenBehavior.fullScreen,
          ),
        ),
      );
      expect(
        result.code,
        anyOf(ShowCallResultCode.shown, ShowCallResultCode.degraded),
      );

      final deadline = DateTime.now().add(const Duration(seconds: 10));
      var report = await RingKit.diagnose();
      bool recovered() => report.ringTraces.any(
        (trace) =>
            trace.contains(id) &&
            trace.contains('audio state: Playing(engine=BundledDefault)'),
      );
      while (!recovered() && DateTime.now().isBefore(deadline)) {
        await Future<void>.delayed(const Duration(milliseconds: 100));
        report = await RingKit.diagnose();
      }
      debugPrint('RECOVERY_RINGING_DIAGNOSE $report');
      expect(
        recovered(),
        isTrue,
        reason:
            'Native fallback must start; inspect ringer/DND and diagnostics.',
      );

      // Keep ringing through the native post-start verification window.
      await Future<void>.delayed(const Duration(milliseconds: 600));
      report = await RingKit.diagnose();
      expect(
        report.ringTraces.where(
          (trace) =>
              trace.contains(id) && trace.contains('audio state: Failed'),
        ),
        isEmpty,
      );

      expect(await RingKit.acceptCall(id), CallCommandResult.ok);
      expect(await RingKit.setCallConnected(id), CallCommandResult.ok);
      // Cross the configured real native connection watchdog deadline.
      await Future<void>.delayed(const Duration(seconds: 4));
      expect(
        (await RingKit.activeCalls())
            .singleWhere((call) => call.id == id)
            .state,
        CallState.active,
      );
      expect(
        events.whereType<CallEnded>().where((event) => event.call.id == id),
        isEmpty,
      );
      expect(await RingKit.endCall(id), CallCommandResult.ok);
      expect(
        (await RingKit.activeCalls()).where((call) => call.id == id),
        isEmpty,
      );
    },
  );
}
