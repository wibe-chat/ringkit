package com.wibechat.ringkit.adapter

import com.wibechat.ringkit.core.RingKitCore

/**
 * Process-wide completion glue between the headless dispatcher and its host
 * (§10.2). `finishBackgroundDelivery` (via the plugin) lands in [signalFinished]:
 * it tears the engine down after its idle grace and releases the waiting host
 * (service `stopSelf` / worker future). One background delivery runs at a time
 * (a single headless engine), so the single release slot never overlaps.
 *
 * Lives in `adapter/` (it drives [FlutterEngineHost]); the `services/` hosts
 * call it — a one-way services → adapter reference, no cycle.
 */
internal object BackgroundDeliveryCoordinator {
    @Volatile
    private var onHostRelease: (() -> Unit)? = null

    fun awaitFinish(release: () -> Unit) {
        onHostRelease = release
    }

    fun signalFinished() {
        FlutterEngineHost.scheduleTeardown()
        val release = onHostRelease
        onHostRelease = null
        release?.invoke()
    }

    /** Extend the active headless leases so a slow/multi-event batch never expires mid-run (§10.2). */
    fun renewActiveLeases(core: RingKitCore.Instance) {
        core.writer.post {
            val seqs = core.repository.eventLog.takeLeased(actionableOnly = false).map { it.seq }
            if (seqs.isNotEmpty()) core.repository.eventLog.renewLease(seqs)
        }
    }
}
