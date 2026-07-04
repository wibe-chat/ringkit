// ringkit Pigeon schema — the single source for all platform channels (PLAN §6, D8).
//
// Regenerate with:  dart run pigeon --input pigeons/messages.dart
// Pigeon is pinned EXACT (27.1.0, PLAN §13.1) — regenerate all outputs on any bump;
// never hand-edit generated files.
//
// Boundary mapping rules (PLAN §6.3): Color ↔ ARGB int · DateTime ↔ epoch-ms int ·
// Uri ↔ String. Wire events are flat CallEventRecord; the public sealed CallEvent
// hierarchy (§6.2) is mapped in the Dart facade (lib/src/events.dart, M1).

import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/src/messages.g.dart',
    kotlinOut: 'android/src/main/kotlin/com/wibechat/ringkit/adapter/Messages.g.kt',
    kotlinOptions: KotlinOptions(package: 'com.wibechat.ringkit.adapter'),
    dartPackageName: 'ringkit',
  ),
)

// ─────────────────────────────────────────────────────────────────── enums ──

enum CallType { audio, video }

enum CallDirection { incoming, outgoing }

enum CallState { ringing, accepted, active, outgoing }

enum CallEventType {
  ringing,
  accepted,
  declined,
  timedOut,
  ended,
  cancelled,
  missedCallTapped,
  audioRouteChanged,
}

/// live = delivered while the designated engine was attached · replayed = from
/// the persisted log on a later launch · background = headless handler (§6.2).
enum DeliveryPath { live, replayed, background }

enum EndReason { local, remote, failed }

enum CancelReason {
  callerCancelled,
  answeredElsewhere,
  declinedElsewhere,
  localDismiss,
}

enum SuppressReason {
  notificationsBlocked,
  channelBlocked,
  foregroundSilentMode,
}

/// Mapped from core-telecom endpoints (§6.3); telecom module only.
enum AudioRoute { earpiece, speaker, bluetooth, wiredHeadset, unknown }

enum NotificationImportance { none, min, low, defaultImportance, high }

enum NotificationVisibility { public, private, secret }

/// fullScreen = FSI always attached (§8.2) · notificationOnly = heads-up-style
/// CallStyle on the lock screen, validity via FGS attachment.
enum LockScreenBehavior { fullScreen, notificationOnly }

enum ForegroundBehavior { headsUp, fullScreen, silent }

enum RingKitLogLevel { none, error, info, verbose }

/// Channel selector for openChannelSettings (§11.2); ids are versioned (§8.2).
enum RingKitChannel { incoming, incomingAudible, ongoing, missed }

/// Best-effort OEM settings screens (§11.2, §12).
enum OemScreen { autostart, lockScreenDisplay, battery }

/// ShowCallResult outcome (§6.4). degraded carries degradations; failed carries
/// failReason (precedence invalidParams > storeCorrupt > notificationsBlocked >
/// channelBlocked, §8.11).
enum ShowCallResultCode {
  shown,
  alreadyShown,
  alreadyActive,
  alreadyTerminal,
  expired,
  degraded,
  failed,
}

enum DegradeReason {
  fsiDenied,
  fgsDenied,
  fcmDowngraded,
  channelDemoted,
  callStyleRejected,
}

enum FailReason {
  invalidParams,
  storeCorrupt,
  notificationsBlocked,
  channelBlocked,
}

enum CallCommandResult { ok, notFound, wrongState, alreadyTerminal, invalidParams }

enum DiagnosticsSeverity { info, warning, error }

// ──────────────────────────────────────────────────────────── data classes ──

/// Typed show outcome (§6.4). `degradations` is set-semantics carried as a list.
class ShowCallResult {
  ShowCallResult({required this.code, this.degradations, this.failReason});
  ShowCallResultCode code;
  List<DegradeReason>? degradations;
  FailReason? failReason;
}

class AccentColors {
  AccentColors({this.acceptArgb, this.declineArgb, this.accentArgb});

  /// ARGB ints (Color ↔ int boundary rule).
  int? acceptArgb;
  int? declineArgb;
  int? accentArgb;
}

class MissedCallOptions {
  MissedCallOptions({
    required this.show,
    this.title,
    this.subtitle,
    required this.showCallbackAction,
    this.callbackText,
  });
  bool show;
  String? title;
  String? subtitle;
  bool showCallbackAction;
  String? callbackText;
}

class AndroidCallOptions {
  AndroidCallOptions({
    this.ringtone,
    required this.vibrate,
    this.missedCall,
    this.colors,
    this.fullScreenBackground,
    required this.lockScreenVisibility,
    required this.lockScreenBehavior,
    this.useTelecom,
  });

  /// `'default' | 'raw:<name>' | 'content://…'` (§8.4 grammar).
  String? ringtone;
  bool vibrate;
  MissedCallOptions? missedCall;
  AccentColors? colors;

  /// `'#RRGGBB' | https url | flutterAsset:<path>` (§6.3).
  String? fullScreenBackground;
  NotificationVisibility lockScreenVisibility;
  LockScreenBehavior lockScreenBehavior;

  /// null → RingKitConfig.telecomIntegration (per-call override).
  bool? useTelecom;
}

class IncomingCall {
  IncomingCall({
    required this.id,
    required this.callerName,
    this.callerHandle,
    this.avatarUrl,
    this.avatarHeaders,
    required this.type,
    this.timeoutSeconds,
    this.sentAtEpochMs,
    required this.extra,
    required this.android,
  });

  /// App call UUID — the idempotency key (≤ 128 chars, non-blank; §6.1).
  String id;
  String callerName;
  String? callerHandle;

  /// `https://… | flutterAsset:<path>` (§6.1 scheme rule).
  String? avatarUrl;

  /// Per-fetch auth headers; never cached across calls (§6.3, R43).
  Map<String, String>? avatarHeaders;
  CallType type;

  /// null → config default; clamp [10, 120] (§5.2).
  int? timeoutSeconds;

  /// Staleness guard input (§5.2) — strongly recommended.
  int? sentAtEpochMs;

  /// ≤ 1 KB serialized; round-trips into every event (§6.4).
  Map<String, String> extra;
  AndroidCallOptions android;
}

class OutgoingCall {
  OutgoingCall({
    required this.id,
    required this.calleeName,
    this.calleeHandle,
    this.avatarUrl,
    this.avatarHeaders,
    required this.type,
    required this.extra,
    required this.android,
  });
  String id;
  String calleeName;
  String? calleeHandle;
  String? avatarUrl;
  Map<String, String>? avatarHeaders;
  CallType type;
  Map<String, String> extra;
  AndroidCallOptions android;
}

class CallInfo {
  CallInfo({
    required this.id,
    required this.name,
    this.handle,
    this.avatarUrl,
    required this.type,
    required this.direction,
    required this.extra,
  });
  String id;

  /// Caller or callee display name.
  String name;
  String? handle;
  String? avatarUrl;
  CallType type;
  CallDirection direction;
  Map<String, String> extra;
}

class ActiveCall {
  ActiveCall({
    required this.id,
    required this.state,
    required this.shownAtEpochMs,
    required this.info,
  });
  String id;
  CallState state;
  int shownAtEpochMs;
  CallInfo info;
}

class CallInfoUpdate {
  CallInfoUpdate({
    this.callerName,
    this.callerHandle,
    this.avatarUrl,
    this.avatarHeaders,
  });
  String? callerName;
  String? callerHandle;
  String? avatarUrl;
  Map<String, String>? avatarHeaders;
}

/// One-shot launch cause (§6.1). A view of a logged event (same sequence);
/// alreadyEnded references the winning terminal event.
class CallLaunchAction {
  CallLaunchAction({
    required this.type,
    required this.call,
    required this.sequence,
    required this.alreadyEnded,
  });
  CallEventType type;
  CallInfo call;
  int sequence;
  bool alreadyEnded;
}

/// Flat wire shape for every event (§6.2). The facade maps records into the
/// public sealed CallEvent hierarchy. `persisted` mirrors the §6.2 persistence
/// classes (ringing/audioRouteChanged are live-only → ack ignores them).
class CallEventRecord {
  CallEventRecord({
    required this.type,
    required this.call,
    required this.sequence,
    required this.occurredAtEpochMs,
    required this.deliveryPath,
    required this.persisted,
    this.endReason,
    this.cancelReason,
    this.suppressed,
    this.suppressReason,
    this.audioRoute,
  });
  CallEventType type;
  CallInfo call;
  int sequence;
  int occurredAtEpochMs;
  DeliveryPath deliveryPath;
  bool persisted;

  /// type == ended.
  EndReason? endReason;

  /// type == cancelled.
  CancelReason? cancelReason;

  /// type == ringing (§8.11 failed/silent-mode rows only).
  bool? suppressed;
  SuppressReason? suppressReason;

  /// type == audioRouteChanged.
  AudioRoute? audioRoute;
}

/// §11.5 — telemetry module config; null config ⇒ module fully inert.
class RingKitTelemetryConfig {
  RingKitTelemetryConfig({
    required this.endpoint,
    required this.apiKey,
    required this.deviceLabel,
    required this.uploadLevel,
    required this.dailyUploadBudgetKb,
  });

  /// Ingest URL (Uri ↔ String boundary rule).
  String endpoint;

  /// Bootstrap bearer; per-install token thereafter; never logged (§11.4).
  String apiKey;
  String deviceLabel;
  RingKitLogLevel uploadLevel;
  int dailyUploadBudgetKb;
}

/// Persisted natively on every initialize() — versioned, last-writer-wins,
/// §8.10 built-in defaults apply when a push arrives before any initialize().
class RingKitConfig {
  RingKitConfig({
    required this.incomingChannelName,
    required this.missedChannelName,
    required this.ongoingChannelName,
    required this.audibleChannelName,
    required this.defaultTimeoutSeconds,
    required this.connectionTimeoutSeconds,
    required this.maxConcurrentRinging,
    required this.telecomIntegration,
    required this.manageOngoingCall,
    required this.foregroundBehavior,
    this.notificationIcon,
    this.defaultRingtone,
    required this.notificationIdBase,
    required this.logLevel,
    required this.channelSuffix,
    this.telemetry,
  });
  String incomingChannelName;
  String missedChannelName;
  String ongoingChannelName;
  String audibleChannelName;
  int defaultTimeoutSeconds;
  int connectionTimeoutSeconds;
  int maxConcurrentRinging;
  bool telecomIntegration;
  bool manageOngoingCall;
  ForegroundBehavior foregroundBehavior;

  /// App drawable name; plugin glyph default (§8.2).
  String? notificationIcon;

  /// §8.4 grammar + `'flutterAsset:<path>'` (resolved & persisted at initialize).
  String? defaultRingtone;
  int notificationIdBase;
  RingKitLogLevel logLevel;

  /// Channel-id versioning escape hatch (channels are immutable, §6.4).
  String channelSuffix;
  RingKitTelemetryConfig? telemetry;
}

class OemInfo {
  OemInfo({
    required this.manufacturer,
    required this.brand,
    required this.knownRestrictions,
  });
  String manufacturer;
  String brand;

  /// Known-restriction flags from the §12 detection table (data-driven).
  List<String> knownRestrictions;
}

class PermissionSnapshot {
  PermissionSnapshot({
    required this.notifications,
    required this.fullScreenIntent,
    required this.incomingChannelImportance,
    required this.ignoringBatteryOptimizations,
    required this.oem,
    required this.manageOwnCallsMerged,
    required this.fgsMerged,
    required this.fgsPhoneCallMerged,
    required this.fsiPermissionMerged,
  });
  bool notifications;
  bool fullScreenIntent;
  NotificationImportance incomingChannelImportance;
  bool ignoringBatteryOptimizations;
  OemInfo oem;

  /// Merged-manifest survival checks (§11.3).
  bool manageOwnCallsMerged;
  bool fgsMerged;
  bool fgsPhoneCallMerged;
  bool fsiPermissionMerged;
}

class DiagnosticsFinding {
  DiagnosticsFinding({
    required this.id,
    required this.severity,
    required this.finding,
    required this.remedy,
    this.deepLink,
  });
  String id;
  DiagnosticsSeverity severity;
  String finding;
  String remedy;

  /// Settings intent identifier the facade can open (§11.3).
  String? deepLink;
}

class EventLogHealth {
  EventLogHealth({
    required this.sizeBytes,
    required this.unconsumedCount,
    required this.droppedEvents,
    required this.quarantinedEntries,
    required this.activeLeases,
  });
  int sizeBytes;
  int unconsumedCount;
  int droppedEvents;
  int quarantinedEntries;
  int activeLeases;
}

class DiagnosticsReport {
  DiagnosticsReport({
    required this.findings,
    required this.eventLogHealth,
    this.lastShowResult,
    this.lastDeliveryPath,
    required this.ringTraces,
    required this.generatedAtEpochMs,
  });
  List<DiagnosticsFinding> findings;
  EventLogHealth eventLogHealth;
  ShowCallResult? lastShowResult;
  DeliveryPath? lastDeliveryPath;

  /// Last 50 ring traces, one serialized JSON breadcrumb trail each (§11.4).
  List<String> ringTraces;
  int generatedAtEpochMs;
}

// ─────────────────────────────────────────────────────────────── host APIs ──

/// Core commands (§6.1). Implementations dispatch to the single writer
/// executor and reply async — never blocking the platform thread (§8.9).
@HostApi()
abstract class RingKitHostApi {
  /// Idempotent; persists config natively (versioned, last-writer-wins).
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  void initialize(RingKitConfig config);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  ShowCallResult showIncomingCall(IncomingCall call);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  CallCommandResult acceptCall(String callId);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  CallCommandResult declineCall(String callId);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  CallCommandResult endCall(String callId, EndReason reason);

  /// Remote-party cancellation with reason (pre-answer); §5.1 rules apply.
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  CallCommandResult cancelCall(String callId, CancelReason reason);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  void endAllCalls();

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  CallCommandResult setCallConnected(String callId);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  ShowCallResult startOutgoingCall(OutgoingCall call);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  CallCommandResult updateCallInfo(String callId, CallInfoUpdate update);

  /// Telecom module only (§6.1); otherwise wrongState.
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  CallCommandResult setAudioRoute(AudioRoute route);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  List<ActiveCall> activeCalls();

  /// Unconsumed, un-leased events in seq order (§10.3 step 3).
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  List<CallEventRecord> takePendingEvents();

  /// Marks consumed exactly the delivered sequences ≤ upToSeq (§10.3 step 3 —
  /// native tracks the delivered set; a late lower-seq arrival after a higher
  /// ack is never silently consumed). Acks from non-designated engines are
  /// ignored (§10.3 step 1).
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  void ackEvents(int upToSeq, List<int> deliveredSequences);

  /// One-shot; persisted natively, never raw intent extras (§6.1, R44).
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  CallLaunchAction? consumeLaunchAction();

  /// Both handles stored natively as 64-bit longs (§10.4 — the incumbent's
  /// Int-cast bug is structurally impossible through Pigeon).
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  void registerBackgroundEventHandler(int dispatcherHandle, int handlerHandle);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  DiagnosticsReport diagnose();
}

/// §11.2 — permission state + request/deep-link helpers. Never called mid-ring
/// by the plugin itself (§11.1).
@HostApi()
abstract class RingKitPermissionsApi {
  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  PermissionSnapshot snapshot();

  @async
  bool requestNotifications();

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  bool canUseFullScreenIntent();

  @async
  void openFullScreenIntentSettings();

  @async
  void openChannelSettings(RingKitChannel channel);

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  bool isIgnoringBatteryOptimizations();

  @async
  void openBatteryOptimizationSettings();

  /// Requires the APP to declare REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (§11.2).
  @async
  bool requestIgnoreBatteryOptimizations();

  @TaskQueue(type: TaskQueueType.serialBackgroundThread)
  @async
  OemInfo oemInfo();

  /// Best-effort intent table (§12); false if no intent resolved.
  @async
  bool openOemSettings(OemScreen screen);
}

// ──────────────────────────────────────────────────────────── event stream ──

/// Typed native→Dart event stream (D8). Designated-engine rules and ack
/// gating live in the facade + native EventLog (§10.3).
@EventChannelApi()
abstract class RingKitEventApi {
  CallEventRecord streamCallEvents();
}
