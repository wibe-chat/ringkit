package com.wibechat.ringkit.core

/**
 * Core-internal models (PLAN §5, §6.3). These deliberately duplicate the Pigeon
 * shapes: core/ has ZERO Flutter imports (§4.1 dependency rule); the adapter
 * maps Pigeon ↔ core at the boundary.
 */

enum class CoreCallType { AUDIO, VIDEO }

enum class CoreCallDirection { INCOMING, OUTGOING }

/** Live states. Terminal outcomes are represented by [Tombstone]s. */
enum class CoreCallState { RINGING, ACCEPTED, ACTIVE, OUTGOING }

enum class TerminalType { DECLINED, TIMED_OUT, CANCELLED, ENDED }

enum class CoreEndReason { LOCAL, REMOTE, FAILED }

enum class CoreCancelReason { CALLER_CANCELLED, ANSWERED_ELSEWHERE, DECLINED_ELSEWHERE, LOCAL_DISMISS }

enum class CoreEventType { RINGING, ACCEPTED, DECLINED, TIMED_OUT, ENDED, CANCELLED, MISSED_CALL_TAPPED, AUDIO_ROUTE_CHANGED }

/** §6.3 audio endpoints (telecom module only); names mirror the Pigeon `AudioRoute`. */
enum class CoreAudioRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET, UNKNOWN }

enum class CoreDeliveryPath { LIVE, REPLAYED, BACKGROUND }

enum class CoreNotificationVisibility { PUBLIC, PRIVATE, SECRET }

enum class CoreLockScreenBehavior { FULL_SCREEN, NOTIFICATION_ONLY }

enum class CoreForegroundBehavior { HEADS_UP, FULL_SCREEN, SILENT }

enum class CoreLogLevel { NONE, ERROR, INFO, VERBOSE }

/** §8.11 degradation set members (accumulate; each row's mitigation independent). */
enum class CoreDegradeReason { FSI_DENIED, FGS_DENIED, FCM_DOWNGRADED, CHANNEL_DEMOTED, CALL_STYLE_REJECTED }

/** §8.11 failed reasons (short-circuit precedence invalidParams > storeCorrupt > notificationsBlocked > channelBlocked). */
enum class CoreFailReason { INVALID_PARAMS, STORE_CORRUPT, NOTIFICATIONS_BLOCKED, CHANNEL_BLOCKED }

/** §6.2 CallRinging(suppressed:) reasons — §8.11 failed rows + foreground silent mode. */
enum class CoreSuppressReason { NOTIFICATIONS_BLOCKED, CHANNEL_BLOCKED, FOREGROUND_SILENT_MODE }

/** Caller/callee identity snapshot; round-trips into every event (§6.4). */
data class CoreCallInfo(
    val id: String,
    val name: String,
    val handle: String?,
    val avatarUrl: String?,
    val type: CoreCallType,
    val direction: CoreCallDirection,
    val extra: Map<String, String>,
)

data class CoreMissedCallOptions(
    val show: Boolean = true,
    val title: String? = null,
    val subtitle: String? = null,
    val showCallbackAction: Boolean = true,
    val callbackText: String? = null,
)

data class CoreAccentColors(
    val acceptArgb: Int? = null,
    val declineArgb: Int? = null,
    val accentArgb: Int? = null,
)

/** Per-call Android presentation options with §8.10 cold-install defaults. */
data class CoreCallOptions(
    val ringtone: String? = null,
    val vibrate: Boolean = true,
    val missedCall: CoreMissedCallOptions = CoreMissedCallOptions(),
    val colors: CoreAccentColors = CoreAccentColors(),
    val fullScreenBackground: String? = null,
    val lockScreenVisibility: CoreNotificationVisibility = CoreNotificationVisibility.PUBLIC,
    val lockScreenBehavior: CoreLockScreenBehavior = CoreLockScreenBehavior.FULL_SCREEN,
    val useTelecom: Boolean? = null,
    val avatarHeaders: Map<String, String> = emptyMap(),
)

/** showIncomingCall input after boundary mapping/parsing. */
data class CoreIncomingCall(
    val id: String,
    val callerName: String,
    val callerHandle: String? = null,
    val avatarUrl: String? = null,
    val type: CoreCallType = CoreCallType.AUDIO,
    /** null → config default; clamped to [10, 120] (§5.2). */
    val timeoutSeconds: Int? = null,
    /** Staleness-guard input; epoch ms (§5.2). */
    val sentAtWallMs: Long? = null,
    val extra: Map<String, String> = emptyMap(),
    val options: CoreCallOptions = CoreCallOptions(),
)

data class CoreOutgoingCall(
    val id: String,
    val calleeName: String,
    val calleeHandle: String? = null,
    val avatarUrl: String? = null,
    val type: CoreCallType = CoreCallType.AUDIO,
    val extra: Map<String, String> = emptyMap(),
    val options: CoreCallOptions = CoreCallOptions(),
)

data class CoreCallInfoUpdate(
    val callerName: String? = null,
    val callerHandle: String? = null,
    val avatarUrl: String? = null,
    val avatarHeaders: Map<String, String>? = null,
)

/**
 * One live call in the repository. Deadlines are elapsedRealtime stamped with
 * [bootCount]; a record from another boot has invalid elapsed math and is
 * handled exclusively by the sweep rules (§5.1 sweep row).
 */
data class CallRecord(
    val info: CoreCallInfo,
    val state: CoreCallState,
    val options: CoreCallOptions,
    val timeoutSeconds: Int,
    val shownAtWallMs: Long,
    val shownAtElapsedMs: Long,
    val bootCount: Int,
    /** RINGING ring deadline (elapsed). */
    val ringDeadlineElapsedMs: Long?,
    /** ACCEPTED/OUTGOING connection watchdog deadline (elapsed), §5.2. */
    val connectionDeadlineElapsedMs: Long?,
    /** Set when a notification/FSI surface was actually posted (§8.10 sweep presence check). */
    val surfacePosted: Boolean,
    /** setCallConnected timestamp — chronometer base; first write wins (§5.1). */
    val connectedAtElapsedMs: Long? = null,
    val connectedAtWallMs: Long? = null,
) {
    val callId: String get() = info.id
}

/**
 * Terminal marker, TTL 10 min (§5.2). Blocks re-ring of dead calls (R34) and
 * carries the winning terminal event's seq for alreadyEnded launch actions.
 */
data class Tombstone(
    val callId: String,
    val terminalType: TerminalType,
    val endReason: CoreEndReason? = null,
    val cancelReason: CoreCancelReason? = null,
    val terminalEventSeq: Long?,
    /** Snapshot for alreadyEnded launch actions after the live record is gone (§4.3). */
    val callInfo: CoreCallInfo?,
    val atElapsedMs: Long,
    val atWallMs: Long,
    val bootCount: Int,
)

/** Cancel-before-show marker, TTL 10 min (§5.1, R41). */
data class PreTombstone(
    val callId: String,
    val reason: CoreCancelReason,
    val atElapsedMs: Long,
    val atWallMs: Long,
    val bootCount: Int,
)

/** Persisted one-shot launch cause (§10.2, R44). */
data class LaunchAction(
    val type: CoreEventType,
    val callInfo: CoreCallInfo,
    val sequence: Long,
    val alreadyEnded: Boolean,
)

/**
 * A logged event (§10.1). CallRinging/AudioRouteChanged are live-only and never
 * appended (they draw seq from the same counter but have nothing to replay).
 */
data class CoreEvent(
    val seq: Long,
    val callId: String,
    val type: CoreEventType,
    val callInfo: CoreCallInfo,
    val endReason: CoreEndReason? = null,
    val cancelReason: CoreCancelReason? = null,
    val wallMs: Long,
    val elapsedMs: Long,
    val bootCount: Int,
    val consumed: Boolean = false,
    /** Headless-delivery lease expiry (elapsed ms), §10.2; null = un-leased. */
    val leaseUntilElapsedMs: Long? = null,
    /** Set only on live AUDIO_ROUTE_CHANGED events (§6.3, telecom); never persisted. */
    val audioRoute: CoreAudioRoute? = null,
)

/**
 * §11.5 telemetry module config. null on [CoreConfig.telemetry] ⇒ the
 * telemetry/ module is fully inert (rule 14). The apiKey is persisted with the
 * config (noBackupFilesDir) and is never logged at any level (§11.4).
 */
data class CoreTelemetryConfig(
    val endpoint: String,
    val apiKey: String,
    val deviceLabel: String,
    val uploadLevel: CoreLogLevel = CoreLogLevel.INFO,
    val dailyUploadBudgetKb: Int = 200,
) {
    /** §11.4: the apiKey never reaches ANY log — incl. config-clamp traces. */
    override fun toString(): String =
        "CoreTelemetryConfig(endpoint=$endpoint, apiKey=${RingLog.REDACTED}, " +
            "deviceLabel=$deviceLabel, uploadLevel=$uploadLevel, dailyUploadBudgetKb=$dailyUploadBudgetKb)"
}

/** §8.10 cold-install defaults; every field clamps invalid input (never throws). */
data class CoreConfig(
    val incomingChannelName: String = "Incoming calls",
    val missedChannelName: String = "Missed calls",
    val ongoingChannelName: String = "Ongoing call",
    val audibleChannelName: String = "Incoming calls (fallback)",
    val defaultTimeoutSeconds: Int = 45,
    val connectionTimeoutSeconds: Int = 120,
    val maxConcurrentRinging: Int = 3,
    val telecomIntegration: Boolean = false,
    val manageOngoingCall: Boolean = true,
    val foregroundBehavior: CoreForegroundBehavior = CoreForegroundBehavior.HEADS_UP,
    val notificationIcon: String? = null,
    val defaultRingtone: String? = null,
    val notificationIdBase: Int = 424200,
    val logLevel: CoreLogLevel = CoreLogLevel.ERROR,
    val channelSuffix: String = "",
    val telemetry: CoreTelemetryConfig? = null,
) {
    companion object {
        const val MIN_TIMEOUT_SECONDS = 10
        const val MAX_TIMEOUT_SECONDS = 120
        const val MIN_CONCURRENT_RINGING = 1
        const val MAX_CONCURRENT_RINGING = 5

        /** §5.2 ring-duration clamp. */
        fun clampTimeout(seconds: Int): Int = seconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)

        fun clampMaxRinging(count: Int): Int = count.coerceIn(MIN_CONCURRENT_RINGING, MAX_CONCURRENT_RINGING)
    }
}

/** §8.10 missed-call copy per cancel reason (English v1, Q7; ui/ may localize). */
object MissedCallCopy {
    const val GENERIC = "Missed call"
    const val ANSWERED_ELSEWHERE = "Answered on another device"
    const val DECLINED_ELSEWHERE = "Declined on another device"

    fun forCancelReason(reason: CoreCancelReason?): String =
        when (reason) {
            CoreCancelReason.ANSWERED_ELSEWHERE -> ANSWERED_ELSEWHERE
            CoreCancelReason.DECLINED_ELSEWHERE -> DECLINED_ELSEWHERE
            else -> GENERIC
        }
}

object CoreDurations {
    /** Tombstone / pre-tombstone TTL: 10 min ≥ 2× max timeout (§5.2). */
    const val TOMBSTONE_TTL_MS: Long = 10L * 60L * 1000L

    /** Headless delivery lease (§10.2). */
    const val EVENT_LEASE_MS: Long = 30_000L

    /** Unconsumed event cap (§8.10). */
    const val MAX_UNCONSUMED_EVENTS = 200

    /** Ring-trace history bound (§11.4). */
    const val MAX_RING_TRACES = 50
}
