import 'messages.g.dart';

/// The single integration point's event model (PLAN §6.2): a sealed hierarchy
/// mapped from the flat wire records. `deliveryPath` supersedes any
/// "fromKilledState" boolean: `replayed` ⇒ the app wasn't alive to see it
/// live; `background` ⇒ delivered to the headless handler.
sealed class CallEvent {
  CallEvent._(this.call, this.sequence, this.occurredAt, this.deliveryPath);

  final CallInfo call;

  /// Monotonic across ALL events — the app-side idempotency key for the
  /// documented duplicate windows (§6.2).
  final int sequence;

  /// Wall time — display only (§8.9).
  final DateTime occurredAt;

  final DeliveryPath deliveryPath;
}

/// Live-only (§6.2): never replayed — the terminal event tells the story.
final class CallRinging extends CallEvent {
  CallRinging._(
    super.call,
    super.sequence,
    super.occurredAt,
    super.deliveryPath, {
    required this.suppressed,
    this.suppressReason,
  }) : super._();

  /// §8.11 failed/silent-mode rows only — deliberate ringer-policy silence is
  /// NOT reported as suppression.
  final bool suppressed;
  final SuppressReason? suppressReason;
}

final class CallAccepted extends CallEvent {
  CallAccepted._(
    super.call,
    super.sequence,
    super.occurredAt,
    super.deliveryPath,
  ) : super._();
}

final class CallDeclined extends CallEvent {
  CallDeclined._(
    super.call,
    super.sequence,
    super.occurredAt,
    super.deliveryPath,
  ) : super._();
}

final class CallTimedOut extends CallEvent {
  CallTimedOut._(
    super.call,
    super.sequence,
    super.occurredAt,
    super.deliveryPath,
  ) : super._();
}

final class CallEnded extends CallEvent {
  CallEnded._(
    super.call,
    super.sequence,
    super.occurredAt,
    super.deliveryPath,
    this.reason,
  ) : super._();

  final EndReason reason;
}

final class CallCancelled extends CallEvent {
  CallCancelled._(
    super.call,
    super.sequence,
    super.occurredAt,
    super.deliveryPath,
    this.reason,
  ) : super._();

  final CancelReason reason;
}

/// Also sets the launch action (§4.3).
final class MissedCallTapped extends CallEvent {
  MissedCallTapped._(
    super.call,
    super.sequence,
    super.occurredAt,
    super.deliveryPath,
  ) : super._();
}

/// Telecom module, live-only (§6.2).
final class AudioRouteChanged extends CallEvent {
  AudioRouteChanged._(
    super.call,
    super.sequence,
    super.occurredAt,
    super.deliveryPath,
    this.route,
  ) : super._();

  final AudioRoute route;
}

/// Wire record → sealed event (the facade's single mapping point).
CallEvent mapEventRecord(CallEventRecord record) {
  final call = record.call;
  final occurredAt = DateTime.fromMillisecondsSinceEpoch(
    record.occurredAtEpochMs,
  );
  final seq = record.sequence;
  final path = record.deliveryPath;
  return switch (record.type) {
    CallEventType.ringing => CallRinging._(
      call,
      seq,
      occurredAt,
      path,
      suppressed: record.suppressed ?? false,
      suppressReason: record.suppressReason,
    ),
    CallEventType.accepted => CallAccepted._(call, seq, occurredAt, path),
    CallEventType.declined => CallDeclined._(call, seq, occurredAt, path),
    CallEventType.timedOut => CallTimedOut._(call, seq, occurredAt, path),
    CallEventType.ended => CallEnded._(
      call,
      seq,
      occurredAt,
      path,
      record.endReason ?? EndReason.local,
    ),
    CallEventType.cancelled => CallCancelled._(
      call,
      seq,
      occurredAt,
      path,
      record.cancelReason ?? CancelReason.callerCancelled,
    ),
    CallEventType.missedCallTapped => MissedCallTapped._(
      call,
      seq,
      occurredAt,
      path,
    ),
    CallEventType.audioRouteChanged => AudioRouteChanged._(
      call,
      seq,
      occurredAt,
      path,
      record.audioRoute ?? AudioRoute.unknown,
    ),
  };
}
