import 'dart:async';

import 'events.dart';
import 'messages.g.dart';

/// The unified-stream mechanics (PLAN §10.3, items 1–5), facade-side:
///
///  2. On init: subscribe to the native event channel; records buffer here.
///  3. On the FIRST app listener: `takePendingEvents()` (replayed) merges with
///     buffered live records, seq-deduped, dispatched in seq order, then
///     `ackEvents(upToSeq, delivered)`.
///  4. Ack covers only events observed by ≥ 1 still-subscribed listener at
///     dispatch completion; a listener cancelling mid-dispatch leaves them
///     un-acked (native replay covers). A throwing listener never blocks the
///     ack (broadcast streams contain listener errors).
///  5. Zero-listener gaps: records keep buffering un-acked; the next listener
///     receives them. Only PERSISTED records are ever acked — live-only ones
///     (ringing/audioRouteChanged) have nothing to consume (§6.2).
///
/// Designation (item 1) is enforced natively (foreign acks ignored); the
/// facade-side guard is the background-isolate StateError in the facade.
class EventHub {
  EventHub(this._api, {required Stream<CallEventRecord> channel}) {
    _controller = StreamController<CallEvent>.broadcast(
      onListen: _onFirstListener,
    );
    _channelSub = channel.listen(
      _onRecord,
      onError: (Object _) {
        // Channel errors are non-fatal: the log replays on next flush.
      },
    );
  }

  final RingKitHostApi _api;
  late final StreamController<CallEvent> _controller;
  StreamSubscription<CallEventRecord>? _channelSub;

  final List<CallEventRecord> _buffer = <CallEventRecord>[];
  final Set<int> _dispatchedSeqs = <int>{};
  bool _flushing = false;

  Stream<CallEvent> get events => _controller.stream;

  /// Live record from the native channel.
  void _onRecord(CallEventRecord record) {
    if (_controller.hasListener && !_flushing) {
      _dispatch(<CallEventRecord>[record]);
    } else {
      _buffer.add(record);
    }
  }

  /// First listener after a zero-listener period (§10.3 step 3).
  void _onFirstListener() {
    unawaited(_flush());
  }

  Future<void> _flush() async {
    if (_flushing) return;
    _flushing = true;
    try {
      List<CallEventRecord> pending;
      try {
        pending = await _api.takePendingEvents();
      } catch (_) {
        pending = const <CallEventRecord>[];
      }
      // Merge replayed + buffered live, seq-order, dedup (§10.3 step 3).
      while (true) {
        final batch = <int, CallEventRecord>{};
        for (final record in pending) {
          batch[record.sequence] = record;
        }
        for (final record in _buffer) {
          batch.putIfAbsent(record.sequence, () => record);
        }
        _buffer.clear();
        pending = const <CallEventRecord>[];

        final ordered = batch.values.toList()
          ..sort((a, b) => a.sequence.compareTo(b.sequence));
        _dispatch(ordered);

        // Records may have arrived while dispatching; drain until quiet.
        if (_buffer.isEmpty) break;
      }
    } finally {
      _flushing = false;
    }
  }

  void _dispatch(List<CallEventRecord> records) {
    if (records.isEmpty) return;
    final delivered = <int>[];
    for (final record in records) {
      if (_dispatchedSeqs.contains(record.sequence)) continue;
      if (!_controller.hasListener) {
        // Lost the listener mid-batch: the rest stays buffered un-acked (item 4/5).
        _buffer.add(record);
        continue;
      }
      _controller.add(mapEventRecord(record));
      _dispatchedSeqs.add(record.sequence);
      if (record.persisted) delivered.add(record.sequence);
    }
    _pruneDedup();
    // Ack after dispatch completion, only if someone is still subscribed (item 4).
    if (delivered.isNotEmpty && _controller.hasListener) {
      delivered.sort();
      unawaited(
        _api.ackEvents(delivered.last, delivered).catchError((Object _) {
          // Ack loss is safe: at-least-once native + seq idempotency (§6.2).
        }),
      );
    }
  }

  void _pruneDedup() {
    if (_dispatchedSeqs.length <= 5000) return;
    final sorted = _dispatchedSeqs.toList()..sort();
    _dispatchedSeqs.removeAll(sorted.take(sorted.length - 2500));
  }

  Future<void> dispose() async {
    await _channelSub?.cancel();
    _channelSub = null;
    await _controller.close();
  }
}
