package com.wibechat.ringkit.adapter

import com.wibechat.ringkit.core.CoreDeliveryPath
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingLog

/**
 * §10.2 rung 2 — the headless dispatcher's native side, set up ONLY on the
 * background engine (never designated, §10.3).
 *
 * [fetchBackgroundDelivery] and [ackBackgroundEvent] drive the EventLog lease
 * protocol DIRECTLY: the public [RingKitApi.ackEvents] is designated-gated and a
 * headless isolate is never designated, so consumption here is ungated — the
 * lease already fenced these seqs from the main engine, so there is no
 * double-consume risk. [finishBackgroundDelivery] signals the runner to tear the
 * engine down after its idle grace. Every method hops to the single writer
 * executor (§8.9); the platform/isolate thread is never blocked.
 */
internal class RingKitBackgroundApiImpl(
    private val core: RingKitCore.Instance,
    private val onFinish: () -> Unit,
) : RingKitBackgroundApi {

    private fun <T> onWriter(callback: (Result<T>) -> Unit, block: () -> T) {
        core.writer.submit(block) { result ->
            result.exceptionOrNull()?.let { t ->
                core.ringLog.incident(RingLog.IncidentType.BOUNDARY_EXCEPTION, "RingKitBackgroundApiImpl", null, throwable = t)
            }
            callback(result)
        }
    }

    override fun fetchBackgroundDelivery(callback: (Result<BackgroundDelivery>) -> Unit) =
        onWriter(callback) {
            val handlerHandle = core.store.readBackgroundHandles()?.second ?: 0L
            val leased = core.repository.eventLog.takeLeased(actionableOnly = true)
            if (leased.isNotEmpty()) {
                runCatching { core.store.writeLastDeliveryPath("background") }
            }
            BackgroundDelivery(
                handlerHandle = handlerHandle,
                events = leased.map { PigeonMapper.eventRecord(it, CoreDeliveryPath.BACKGROUND) },
            )
        }

    override fun ackBackgroundEvent(sequence: Long, callback: (Result<Unit>) -> Unit) =
        onWriter(callback) {
            // Completion ack (§10.2): consume exactly this seq. Ungated on purpose
            // — the lease fenced it from the designated engine (§10.3).
            core.repository.eventLog.ack(sequence, listOf(sequence))
            Unit
        }

    override fun finishBackgroundDelivery(callback: (Result<Unit>) -> Unit) =
        onWriter(callback) {
            onFinish()
            Unit
        }
}
