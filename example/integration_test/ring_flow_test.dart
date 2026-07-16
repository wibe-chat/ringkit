import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:ringkit/ringkit.dart';

/// On-emulator live-path integration (PLAN §14.1(3)): show → event round
/// trips per command against the REAL native pipeline (repository +
/// orchestrator + notifications + FGS). Killed-state legs live in
/// tools/killed_state_harness.sh (§14.1(4) — a single in-process test cannot
/// survive `am kill`). CI grants POST_NOTIFICATIONS before running (33+).
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  final List<CallEvent> events = <CallEvent>[];
  late StreamSubscription<CallEvent> subscription;

  Future<T> expectEvent<T extends CallEvent>(String callId, {Duration timeout = const Duration(seconds: 10)}) async {
    final Completer<T> completer = Completer<T>();
    Timer? timer;
    void scan() {
      for (final CallEvent event in events) {
        if (event is T && event.call.id == callId && !completer.isCompleted) {
          completer.complete(event);
          timer?.cancel();
          return;
        }
      }
    }

    timer = Timer.periodic(const Duration(milliseconds: 100), (_) => scan());
    scan();
    return completer.future.timeout(timeout);
  }

  setUpAll(() async {
    subscription = RingKit.events.listen(events.add);
    await RingKit.initialize(
      RingKitConfig(
        incomingChannelName: 'Incoming calls',
        missedChannelName: 'Missed calls',
        ongoingChannelName: 'Ongoing call',
        audibleChannelName: 'Incoming calls (fallback)',
        defaultTimeoutSeconds: 45,
        connectionTimeoutSeconds: 120,
        maxConcurrentRinging: 3,
        // Telecom on/off parity: the SAME suite runs both ways —
        // `flutter test integration_test --dart-define=RINGKIT_TELECOM=true`
        // turns every show into a real Telecom addCall (addCall-before-post).
        telecomIntegration: const bool.fromEnvironment('RINGKIT_TELECOM'),
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
    await subscription.cancel();
  });

  IncomingCall call(String id, {int? sentAt}) => IncomingCall(
        id: id,
        callerName: 'Integration $id',
        callerHandle: '+15550100',
        type: CallType.audio,
        sentAtEpochMs: sentAt ?? DateTime.now().millisecondsSinceEpoch,
        extra: <String, String>{'suite': 'ring_flow'},
        android: AndroidCallOptions(
          vibrate: true,
          lockScreenVisibility: NotificationVisibility.public,
          lockScreenBehavior: LockScreenBehavior.fullScreen,
        ),
      );

  test('show → live CallRinging → accept → connected → end round trip', () async {
    final ShowCallResult shown = await RingKit.showIncomingCall(call('it-accept'));
    expect(shown.code, anyOf(ShowCallResultCode.shown, ShowCallResultCode.degraded),
        reason: 'failed(${shown.failReason}) — is POST_NOTIFICATIONS granted?');

    final CallRinging ringing = await expectEvent<CallRinging>('it-accept');
    expect(ringing.deliveryPath, DeliveryPath.live);

    final List<ActiveCall> active = await RingKit.activeCalls();
    expect(active.map((ActiveCall c) => c.id), contains('it-accept'));

    expect(await RingKit.acceptCall('it-accept'), CallCommandResult.ok);
    await expectEvent<CallAccepted>('it-accept');

    expect(await RingKit.setCallConnected('it-accept'), CallCommandResult.ok);

    expect(await RingKit.endCall('it-accept'), CallCommandResult.ok);
    final CallEnded ended = await expectEvent<CallEnded>('it-accept');
    expect(ended.reason, EndReason.local);

    expect((await RingKit.activeCalls()).map((ActiveCall c) => c.id), isNot(contains('it-accept')));
  });

  test('decline round trip', () async {
    await RingKit.showIncomingCall(call('it-decline'));
    await expectEvent<CallRinging>('it-decline');
    expect(await RingKit.declineCall('it-decline'), CallCommandResult.ok);
    await expectEvent<CallDeclined>('it-decline');
  });

  test('remote cancel carries the reason', () async {
    await RingKit.showIncomingCall(call('it-cancel'));
    await expectEvent<CallRinging>('it-cancel');
    expect(
      await RingKit.cancelCall('it-cancel', reason: CancelReason.answeredElsewhere),
      CallCommandResult.ok,
    );
    final CallCancelled cancelled = await expectEvent<CallCancelled>('it-cancel');
    expect(cancelled.reason, CancelReason.answeredElsewhere);
  });

  test('out-of-order cancel writes a pre-tombstone: the show never rings', () async {
    expect(
      await RingKit.cancelCall('it-ooo', reason: CancelReason.callerCancelled),
      CallCommandResult.notFound,
    );
    final ShowCallResult result = await RingKit.showIncomingCall(call('it-ooo'));
    expect(result.code, ShowCallResultCode.alreadyTerminal);
    expect((await RingKit.activeCalls()).map((ActiveCall c) => c.id), isNot(contains('it-ooo')));
  });

  test('idempotent re-show reports alreadyShown (FCM re-delivery)', () async {
    await RingKit.showIncomingCall(call('it-dup'));
    final ShowCallResult second = await RingKit.showIncomingCall(call('it-dup'));
    expect(second.code, ShowCallResultCode.alreadyShown);
    await RingKit.endCall('it-dup');
  });

  test('stale sentAt expires without ringing (§5.2 staleness guard)', () async {
    final ShowCallResult result = await RingKit.showIncomingCall(
      call('it-stale', sentAt: DateTime.now().millisecondsSinceEpoch - 120_000),
    );
    expect(result.code, ShowCallResultCode.expired);
    final CallTimedOut timedOut = await expectEvent<CallTimedOut>('it-stale');
    expect(timedOut.deliveryPath, DeliveryPath.live);
  });

  test('invalid params are typed failures, not exceptions', () async {
    final ShowCallResult result = await RingKit.showIncomingCall(call('   '));
    expect(result.code, ShowCallResultCode.failed);
    expect(result.failReason, FailReason.invalidParams);
  });
}
