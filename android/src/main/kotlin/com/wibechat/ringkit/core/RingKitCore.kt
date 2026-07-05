package com.wibechat.ringkit.core

import android.content.Context

/**
 * Process-wide core singleton (PLAN D4): every component — plugin adapter,
 * receivers, services, Mode-2 service — constructs the SAME repository from
 * applicationContext + persisted state. No component ever depends on the
 * Flutter plugin being attached (the incumbent's null-manager class, R1).
 *
 * The reconciliation sweep runs at every native entry point (§8.10); callers
 * hit [sweepOnEntry] once per process-entry via the writer executor.
 */
object RingKitCore {

    @Volatile
    private var instance: Instance? = null

    class Instance internal constructor(internal val appContext: Context) {
        val time: TimeSource = RealTimeSource(appContext)
        val store: CoreStore = CoreStore.open(appContext)
        val writer: WriterExecutor = WriterExecutor()

        /** §5.2 connection watchdogs (orchestrator-hosted, service-independent). */
        val watchdogs: WatchdogScheduler = WatchdogScheduler(writer)

        /** §11.5 always-on capture sink; level follows persisted config below. */
        val ringLog: RingLog = RingLog.open(java.io.File(appContext.noBackupFilesDir, CoreStore.DIR_NAME), time)

        /** §11.4 ring-trace buffer + sink tee; the repository traces through it. */
        val trace: SinkTrace = SinkTrace(ringLog)

        val repository: CallRepository = CallRepository.open(store, time, trace)

        /** §8.6 in-process signalling (orchestrator publishes on the writer). */
        val bus: CallBus = CallBus(ringLog)

        /**
         * §10.2 rung 1, source-agnostic: the adapter registers live delivery to
         * the designated engine here; the orchestrator emits EVERY transition's
         * events through it exactly once — receiver declines, service timeouts,
         * sweep kills and evictions reach a live listener like any Dart-initiated
         * command. Null (cold native process) ⇒ events wait in the log for
         * replay. Suppress applies only to the live CallRinging (§8.11 rows).
         */
        @Volatile
        var liveSink: ((events: List<CoreEvent>, suppress: CoreSuppressReason?) -> Unit)? = null

        /**
         * Sweep probes (§8.10). The notification-presence check applies only to
         * calls that actually posted a surface, with a 10 s post-in-flight grace
         * (the FGS posts asynchronously moments after show marks the flag).
         * ongoingServiceAlive stays `true` until OngoingCallService exists (M3)
         * — a lie in the safe direction (never falsely sweeps ACTIVE calls).
         */
        @Volatile
        var sweepEnv: SweepEnv =
            SweepEnv(
                notificationPresent = { callId ->
                    // Runs on the writer during sweep — repository reads are safe.
                    val record = repository.callFor(callId)
                    val notifId = repository.notificationIdFor(callId)
                    when {
                        record == null || notifId == null -> true
                        time.elapsedMs() - record.shownAtElapsedMs < POST_GRACE_MS -> true
                        else ->
                            runCatching {
                                val manager =
                                    appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                                        as android.app.NotificationManager
                                manager.activeNotifications.any { it.id == notifId }
                            }.getOrDefault(true)
                    }
                },
                ongoingServiceAlive = true,
            )

        /**
         * Process-wide avatar loader (§8.2): the in-flight registry must span
         * orchestrator instances (receivers construct one per event) so a
         * terminal transition can always cancel the fetch it didn't start.
         */
        val avatarLoader: com.wibechat.ringkit.ui.AvatarLoader by lazy {
            com.wibechat.ringkit.ui.AvatarLoader(
                appContext,
                java.io.File(appContext.noBackupFilesDir, CoreStore.DIR_NAME),
                ringLog,
            )
        }

        init {
            ringLog.level = repository.currentConfig().logLevel
            writer.onUncaught = { t ->
                ringLog.incident(RingLog.IncidentType.BOUNDARY_EXCEPTION, "WriterExecutor.post", null, throwable = t)
            }
        }
    }

    fun get(context: Context): Instance {
        val existing = instance
        if (existing != null) return existing
        synchronized(this) {
            val double = instance
            if (double != null) return double
            val created = Instance(context.applicationContext)
            instance = created
            return created
        }
    }

    /** Grace window before the sweep trusts the notification-presence probe. */
    const val POST_GRACE_MS = 10_000L

    /**
     * §8.10: orchestrated sweep on the writer at every native entry point —
     * swept calls get their teardown + missed-call effects, not just state.
     */
    fun sweepOnEntry(context: Context) {
        val core = get(context)
        val appContext = context.applicationContext
        core.writer.post { IncomingCallOrchestrator(appContext, core).sweepNow() }
    }

    /** Tests only: drop the process singleton between cases. */
    internal fun resetForTests() {
        synchronized(this) {
            instance?.writer?.shutdown()
            instance = null
        }
    }
}
