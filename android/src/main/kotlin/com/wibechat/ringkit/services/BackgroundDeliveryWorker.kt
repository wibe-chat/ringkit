package com.wibechat.ringkit.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.wibechat.ringkit.adapter.BackgroundDeliveryCoordinator
import com.wibechat.ringkit.adapter.BackgroundEngineRunner
import com.wibechat.ringkit.core.RingKitCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * §10.2 rung 2 — the API 26–33 durable delivery host: an **expedited** worker
 * that survives process death + reboot (JobScheduler-backed). It boots the
 * headless engine via the runner and suspends until the dispatcher signals it
 * finished — or the budget elapses, after which any un-acked lease expires and
 * replays. While waiting it renews the leases so a multi-event batch never
 * expires. [getForegroundInfo] satisfies the pre-Android-12 expedited-work FGS
 * quota.
 */
class BackgroundDeliveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val core = RingKitCore.get(applicationContext)
        val done = CompletableDeferred<Unit>()
        BackgroundDeliveryCoordinator.awaitFinish { done.complete(Unit) }
        BackgroundEngineRunner(applicationContext, core).deliverPending { prep ->
            if (prep != BackgroundEngineRunner.Prep.LEASED) done.complete(Unit)
        }
        withTimeoutOrNull(BUDGET_MS) {
            while (!done.isCompleted) {
                val finished = withTimeoutOrNull(RENEW_INTERVAL_MS) { done.await() }
                if (finished == null) BackgroundDeliveryCoordinator.renewActiveLeases(core)
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            BackgroundDeliveryHost.NOTIFICATION_ID,
            BackgroundDeliveryHost.notification(applicationContext),
        )

    companion object {
        private const val BUDGET_MS = 180_000L
        private const val RENEW_INTERVAL_MS = 15_000L
    }
}
