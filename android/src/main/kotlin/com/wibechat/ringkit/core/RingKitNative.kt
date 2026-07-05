package com.wibechat.ringkit.core

import android.content.Context
import android.os.PowerManager

/**
 * Transport-agnostic native entry (PLAN §4.1, D6): any push stack — the fcm/
 * module (M3), HMS, self-hosted sockets, or the example's adb SIMULATE_CALL
 * trigger — hands the raw `rk.*` data map here. Parsing follows the normative
 * §7.5 wire table; showing runs the full §8.11-evaluated orchestrator path.
 * Dart is never on this critical path (Mode 2's <300 ms budget).
 */
object RingKitNative {

    /**
     * Deliver a push payload. [serverSentAtMs] is FCM's RemoteMessage.getSentTime()
     * when available — preferred over rk.sentAt for the staleness guard (§5.2).
     *
     * [onProcessed] fires (on the writer thread) once the payload has been fully
     * parsed AND acted on — transports with component lifetimes tie them here:
     * a broadcast receiver holds `goAsync()` until it fires, a messaging service
     * blocks its handler thread. Without that anchor the host process has zero
     * active components the instant the entry point returns, and the OS may
     * kill it BEFORE the show executes (§10.2's freezable-receiver reality —
     * observed on One UI/API 31).
     */
    /**
     * §7.4 cancel entry for custom native stacks (HMS, sockets): equivalent to
     * a `rk.type=cancel` payload without requiring the rk.* map shape. Unknown
     * ids write a pre-tombstone (out-of-order delivery, R41); cancel vs a
     * locally-ACCEPTED/ACTIVE call is ignored + traced (§5.1). [reason] uses
     * the §7.5 wire vocabulary (callerCancelled | answeredElsewhere |
     * declinedElsewhere); null/unknown default to callerCancelled.
     */
    fun handleCancel(
        context: Context,
        callId: String,
        reason: String? = null,
        onProcessed: (() -> Unit)? = null,
    ) {
        val core = RingKitCore.get(context)
        val mapped =
            when (reason) {
                "answeredElsewhere" -> CoreCancelReason.ANSWERED_ELSEWHERE
                "declinedElsewhere" -> CoreCancelReason.DECLINED_ELSEWHERE
                null, "", "callerCancelled" -> CoreCancelReason.CALLER_CANCELLED
                else -> {
                    core.trace.trace("wire", callId, "handleCancel reason invalid ($reason) — default callerCancelled")
                    CoreCancelReason.CALLER_CANCELLED
                }
            }
        core.writer.post {
            try {
                val orchestrator = IncomingCallOrchestrator(context, core)
                orchestrator.sweepNow() // §8.10: every native entry point
                orchestrator.cancelOnWriter(callId, mapped)
            } finally {
                onProcessed?.invoke()
            }
        }
    }

    fun deliverPayload(
        context: Context,
        data: Map<String, String>,
        serverSentAtMs: Long? = null,
        onProcessed: (() -> Unit)? = null,
    ) {
        val core = RingKitCore.get(context)
        // §8.1: short PARTIAL_WAKE_LOCK while processing the push — the ring
        // pipeline (FGS + FSI turnScreenOn) takes over from there.
        val wakeLock =
            runCatching {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ringkit:deliverPayload").apply {
                    setReferenceCounted(false)
                    acquire(10_000L)
                }
            }.getOrNull()

        core.writer.post {
            try {
                val orchestrator = IncomingCallOrchestrator(context, core)
                orchestrator.sweepNow() // §8.10: every native entry point
                when (val parsed = RkWireParser.parse(data, serverSentAtMs, core.trace)) {
                    is RkWireParser.ParseResult.Incoming -> orchestrator.showOnWriter(parsed.call)
                    is RkWireParser.ParseResult.Cancel ->
                        orchestrator.cancelOnWriter(parsed.callId, parsed.reason)
                    is RkWireParser.ParseResult.Rejected ->
                        core.ringLog.incident(
                            RingLog.IncidentType.PARSE_REJECTED, "RingKitNative.deliverPayload",
                            data["rk.callId"], data = mapOf("reason" to parsed.reason),
                        )
                    RkWireParser.ParseResult.NotRingKit ->
                        core.trace.trace("wire", null, "no rk.* keys — not a RingKit message")
                }
            } finally {
                runCatching { wakeLock?.release() }
                onProcessed?.invoke()
            }
        }
    }
}
