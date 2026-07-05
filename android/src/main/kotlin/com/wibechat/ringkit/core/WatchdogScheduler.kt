package com.wibechat.ringkit.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * ACCEPTED/OUTGOING connection watchdogs (PLAN §5.2/§8.3): hosted on the
 * orchestrator side whenever the process is alive — independent of any service,
 * so `manageOngoingCall: false` doesn't lose them. Fire callbacks are posted to
 * the writer executor. Process death drops the timers; the reconciliation
 * sweep's deadline enforcement is the cross-process-death backstop.
 */
class WatchdogScheduler internal constructor(private val writer: WriterExecutor) {

    private val scheduler =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "ringkit-watchdog").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
        }

    private val tasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** (Re-)arm the watchdog for [callId]; a fresh arm replaces the previous one. */
    fun arm(callId: String, delayMs: Long, fire: () -> Unit) {
        cancel(callId)
        val future =
            scheduler.schedule(
                {
                    tasks.remove(callId)
                    writer.post(fire)
                },
                delayMs.coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
        tasks[callId] = future
    }

    fun cancel(callId: String) {
        tasks.remove(callId)?.cancel(false)
    }

    fun activeCount(): Int = tasks.size

    internal fun shutdown() {
        scheduler.shutdownNow()
        tasks.clear()
    }
}
