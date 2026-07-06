import 'package:flutter_test/flutter_test.dart';
import 'package:ringkit/ringkit.dart';
import 'package:ringkit/src/messages.g.dart' as wire;

/// Fake of the private background channel (§10.2): records fetch/ack/finish and
/// the interleaving of handler vs ack calls.
class _FakeBackgroundApi extends wire.RingKitBackgroundApi {
  _FakeBackgroundApi(this.delivery);

  final wire.BackgroundDelivery delivery;
  final List<int> acked = <int>[];
  final List<String> order = <String>[];
  int fetches = 0;
  int finishes = 0;

  @override
  Future<wire.BackgroundDelivery> fetchBackgroundDelivery() async {
    fetches++;
    return delivery;
  }

  @override
  Future<void> ackBackgroundEvent(int sequence) async {
    acked.add(sequence);
    order.add('ack:$sequence');
  }

  @override
  Future<void> finishBackgroundDelivery() async {
    finishes++;
    order.add('finish');
  }
}

wire.CallEventRecord _declined(int seq) => wire.CallEventRecord(
  type: wire.CallEventType.declined,
  call: wire.CallInfo(
    id: 'c-$seq',
    name: 'Alice',
    type: wire.CallType.audio,
    direction: wire.CallDirection.incoming,
    extra: const <String, String>{},
  ),
  sequence: seq,
  occurredAtEpochMs: 1000,
  deliveryPath: wire.DeliveryPath.background,
  persisted: true,
);

wire.BackgroundDelivery _delivery(List<int> seqs, {int handle = 42}) =>
    wire.BackgroundDelivery(
      handlerHandle: handle,
      events: seqs.map(_declined).toList(),
    );

void main() {
  test('acks each event ONLY after its handler Future completes', () async {
    final api = _FakeBackgroundApi(_delivery([1, 2]));

    await runBackgroundDispatch(
      api,
      resolveHandler: (_) => (event) async {
        api.order.add('handle:${event.sequence}');
      },
    );

    // §10.2: consumption gated on completion — handle-then-ack per event, in
    // order, finish last.
    expect(api.order, ['handle:1', 'ack:1', 'handle:2', 'ack:2', 'finish']);
    expect(api.acked, [1, 2]);
    expect(api.finishes, 1);
  });

  test('a throwing handler leaves its event un-acked (→ replay) but continues', () async {
    final api = _FakeBackgroundApi(_delivery([1, 2]));

    await runBackgroundDispatch(
      api,
      resolveHandler: (_) => (event) async {
        if (event.sequence == 1) throw StateError('handler blew up');
      },
    );

    expect(api.acked, [2], reason: 'seq 1 threw → un-acked; seq 2 still delivered');
    expect(api.finishes, 1);
  });

  test('an unresolvable (stale) handle acks nothing and still finishes', () async {
    final api = _FakeBackgroundApi(_delivery([1, 2]));

    await runBackgroundDispatch(api, resolveHandler: (_) => null);

    expect(api.acked, isEmpty, reason: 'stale handle → events kept for replay');
    expect(api.finishes, 1);
  });

  test('the resolved handler receives background-path events', () async {
    final api = _FakeBackgroundApi(_delivery([7]));
    final received = <CallEvent>[];

    await runBackgroundDispatch(
      api,
      resolveHandler: (_) => (event) async => received.add(event),
    );

    expect(received.single, isA<CallDeclined>());
    expect(received.single.deliveryPath, DeliveryPath.background);
    expect(received.single.sequence, 7);
    expect(api.acked, [7]);
  });
}
