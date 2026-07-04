import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:ringkit/ringkit.dart';
import 'package:ringkit/src/messages.g.dart' as wire;

/// Fake of the generated host API (Pigeon 27 pattern — dartHostTestHandler is
/// deprecated; PLAN D8). Only the surface the facade touches is overridden.
class FakeHostApi extends wire.RingKitHostApi {
  FakeHostApi();

  final List<String> calls = <String>[];
  List<wire.CallEventRecord> pendingEvents = <wire.CallEventRecord>[];
  final List<(int, List<int>)> acks = <(int, List<int>)>[];
  wire.CallLaunchAction? launchAction;
  (int, int)? registeredHandles;

  @override
  Future<void> initialize(wire.RingKitConfig config) async {
    calls.add('initialize');
  }

  @override
  Future<wire.ShowCallResult> showIncomingCall(wire.IncomingCall call) async {
    calls.add('show:${call.id}');
    return wire.ShowCallResult(code: wire.ShowCallResultCode.shown);
  }

  @override
  Future<wire.CallCommandResult> acceptCall(String callId) async {
    calls.add('accept:$callId');
    return wire.CallCommandResult.ok;
  }

  @override
  Future<wire.CallCommandResult> declineCall(String callId) async {
    calls.add('decline:$callId');
    return wire.CallCommandResult.ok;
  }

  @override
  Future<List<wire.CallEventRecord>> takePendingEvents() async {
    calls.add('takePending');
    final out = pendingEvents;
    pendingEvents = <wire.CallEventRecord>[];
    return out;
  }

  @override
  Future<void> ackEvents(int upToSeq, List<int> deliveredSequences) async {
    calls.add('ack:$upToSeq:${deliveredSequences.join(",")}');
    acks.add((upToSeq, List<int>.of(deliveredSequences)));
  }

  @override
  Future<wire.CallLaunchAction?> consumeLaunchAction() async {
    final out = launchAction;
    launchAction = null;
    return out;
  }

  @override
  Future<void> registerBackgroundEventHandler(
    int dispatcherHandle,
    int handlerHandle,
  ) async {
    registeredHandles = (dispatcherHandle, handlerHandle);
  }
}

wire.CallEventRecord record(
  int seq, {
  wire.CallEventType type = wire.CallEventType.declined,
  wire.DeliveryPath path = wire.DeliveryPath.live,
  bool persisted = true,
}) {
  return wire.CallEventRecord(
    type: type,
    call: wire.CallInfo(
      id: 'call-$seq',
      name: 'Alice',
      type: wire.CallType.audio,
      direction: wire.CallDirection.incoming,
      extra: const <String, String>{},
    ),
    sequence: seq,
    occurredAtEpochMs: 1751700000000 + seq,
    deliveryPath: path,
    persisted: persisted,
    cancelReason: type == wire.CallEventType.cancelled
        ? wire.CancelReason.callerCancelled
        : null,
    endReason: type == wire.CallEventType.ended ? wire.EndReason.remote : null,
  );
}

Future<void> pump() => Future<void>.delayed(Duration.zero);

void main() {
  late FakeHostApi fake;
  late StreamController<wire.CallEventRecord> channel;

  setUp(() async {
    await RingKit.debugReset();
    fake = FakeHostApi();
    channel = StreamController<wire.CallEventRecord>.broadcast();
    RingKit.debugConfigure(
      hostApi: fake,
      eventChannel: channel.stream,
      isBackgroundIsolate: false,
    );
  });

  tearDown(() async {
    await channel.close();
    await RingKit.debugReset();
  });

  group('misuse rules (§6.1/§6.4)', () {
    test(
      'blank callId on show → failed(invalidParams) without host call',
      () async {
        final result = await RingKit.showIncomingCall(
          wire.IncomingCall(
            id: '   ',
            callerName: 'Alice',
            type: wire.CallType.audio,
            extra: const <String, String>{},
            android: wire.AndroidCallOptions(
              vibrate: true,
              lockScreenVisibility: wire.NotificationVisibility.public,
              lockScreenBehavior: wire.LockScreenBehavior.fullScreen,
            ),
          ),
        );
        expect(result.code, wire.ShowCallResultCode.failed);
        expect(result.failReason, wire.FailReason.invalidParams);
        expect(fake.calls, isEmpty);
      },
    );

    test(
      'over-128-char callId on commands → invalidParams without host call',
      () async {
        final result = await RingKit.acceptCall('x' * 129);
        expect(result, wire.CallCommandResult.invalidParams);
        expect(fake.calls, isEmpty);
      },
    );

    test('extra over 1 KB serialized → failed(invalidParams)', () async {
      final result = await RingKit.showIncomingCall(
        wire.IncomingCall(
          id: 'big',
          callerName: 'Alice',
          type: wire.CallType.audio,
          extra: <String, String>{'payload': 'x' * 2000},
          android: wire.AndroidCallOptions(
            vibrate: true,
            lockScreenVisibility: wire.NotificationVisibility.public,
            lockScreenBehavior: wire.LockScreenBehavior.fullScreen,
          ),
        ),
      );
      expect(result.code, wire.ShowCallResultCode.failed);
      expect(fake.calls, isEmpty);
    });

    test('valid command forwards and maps', () async {
      final result = await RingKit.declineCall('ok-1');
      expect(result, wire.CallCommandResult.ok);
      expect(fake.calls, contains('decline:ok-1'));
    });
  });

  group('background-isolate guard (§10.3 item 1)', () {
    test('events throws StateError in background isolates', () async {
      RingKit.debugConfigure(
        hostApi: fake,
        eventChannel: channel.stream,
        isBackgroundIsolate: true,
      );
      expect(() => RingKit.events, throwsStateError);
      expect(() => RingKit.consumeLaunchAction(), throwsStateError);
    });
  });

  group('unified stream (§10.3 items 2–5)', () {
    test(
      'first listener replays pending + buffered live, seq-deduped, one ack',
      () async {
        fake.pendingEvents = <wire.CallEventRecord>[
          record(1, path: wire.DeliveryPath.replayed),
          record(2, path: wire.DeliveryPath.replayed),
        ];
        // Facade init subscribes the channel; records buffer pre-listener (item 2).
        final events = RingKit.events;
        channel.add(record(2)); // duplicate of a replayed seq
        channel.add(record(3));
        await pump();

        final received = <CallEvent>[];
        final sub = events.listen(received.add);
        await pump();
        await pump();

        expect(received.map((e) => e.sequence), <int>[1, 2, 3]);
        expect(received[0].deliveryPath, wire.DeliveryPath.replayed);
        expect(fake.acks, hasLength(1));
        expect(fake.acks.single.$1, 3);
        expect(fake.acks.single.$2, <int>[1, 2, 3]);
        await sub.cancel();
      },
    );

    test('live events dispatch immediately and ack per batch', () async {
      final received = <CallEvent>[];
      final sub = RingKit.events.listen(received.add);
      await pump();

      channel.add(record(10));
      await pump();

      expect(received.single.sequence, 10);
      expect(received.single, isA<CallDeclined>());
      expect(fake.acks.last.$2, <int>[10]);
      await sub.cancel();
    });

    test('live-only records are dispatched but never acked (§6.2)', () async {
      final received = <CallEvent>[];
      final sub = RingKit.events.listen(received.add);
      await pump();

      channel.add(
        record(11, type: wire.CallEventType.ringing, persisted: false),
      );
      await pump();

      expect(received.single, isA<CallRinging>());
      expect(fake.acks, isEmpty);
      await sub.cancel();
    });

    test(
      'zero-listener gap buffers un-acked; next listener receives (item 5)',
      () async {
        final first = RingKit.events.listen((_) {});
        await pump();
        await first.cancel();

        channel.add(record(20));
        await pump();
        expect(fake.acks, isEmpty, reason: 'no listener — nothing acked');

        final received = <CallEvent>[];
        final second = RingKit.events.listen(received.add);
        await pump();
        await pump();

        expect(received.single.sequence, 20);
        expect(fake.acks.single.$2, <int>[20]);
        await second.cancel();
      },
    );

    test('seq dedup: the same sequence never dispatches twice', () async {
      final received = <CallEvent>[];
      final sub = RingKit.events.listen(received.add);
      await pump();

      channel.add(record(30));
      channel.add(record(30));
      await pump();

      expect(received, hasLength(1));
      await sub.cancel();
    });

    test('a throwing listener does not block the ack (item 4)', () async {
      final errors = <Object>[];
      await runZonedGuarded(() async {
        final sub = RingKit.events.listen(
          (_) => throw Exception('listener bug'),
        );
        await pump();
        channel.add(record(40));
        await pump();
        await sub.cancel();
      }, (error, _) => errors.add(error));
      await pump();

      expect(errors, isNotEmpty, reason: 'listener error surfaced to the zone');
      expect(fake.acks.single.$2, <int>[40], reason: 'ack still sent');
    });
  });

  group('launch action (§6.1, R44)', () {
    test('forwards one-shot from native', () async {
      fake.launchAction = wire.CallLaunchAction(
        type: wire.CallEventType.accepted,
        call: wire.CallInfo(
          id: 'launch-1',
          name: 'Alice',
          type: wire.CallType.audio,
          direction: wire.CallDirection.incoming,
          extra: const <String, String>{},
        ),
        sequence: 7,
        alreadyEnded: false,
      );
      final first = await RingKit.consumeLaunchAction();
      expect(first!.sequence, 7);
      final second = await RingKit.consumeLaunchAction();
      expect(second, isNull);
    });
  });

  group('background handler registration (§10.4)', () {
    test('local function → ArgumentError (no callback handle)', () {
      Future<void> localHandler(CallEvent event) async {}
      expect(
        () => RingKit.setBackgroundEventHandler(localHandler),
        throwsArgumentError,
      );
      expect(fake.registeredHandles, isNull);
    });

    test('top-level function registers both 64-bit raw handles', () async {
      await RingKit.setBackgroundEventHandler(topLevelHandler);
      expect(fake.registeredHandles, isNotNull);
      expect(fake.registeredHandles!.$1, isNot(0));
      expect(fake.registeredHandles!.$2, isNot(0));
      expect(fake.registeredHandles!.$1, isNot(fake.registeredHandles!.$2));
    });
  });

  group('event model mapping (§6.2)', () {
    test('reasons and paths survive the mapping', () async {
      final received = <CallEvent>[];
      final sub = RingKit.events.listen(received.add);
      await pump();

      channel.add(
        record(
          50,
          type: wire.CallEventType.ended,
          path: wire.DeliveryPath.live,
        ),
      );
      channel.add(record(51, type: wire.CallEventType.cancelled));
      await pump();

      final ended = received[0] as CallEnded;
      expect(ended.reason, wire.EndReason.remote);
      expect(ended.occurredAt.millisecondsSinceEpoch, 1751700000050);
      final cancelled = received[1] as CallCancelled;
      expect(cancelled.reason, wire.CancelReason.callerCancelled);
      await sub.cancel();
    });
  });
}

@pragma('vm:entry-point')
Future<void> topLevelHandler(CallEvent event) async {}
