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
     */
    fun deliverPayload(context: Context, data: Map<String, String>, serverSentAtMs: Long? = null) {
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
            }
        }
    }
}
