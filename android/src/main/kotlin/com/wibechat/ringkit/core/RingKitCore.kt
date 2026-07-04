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

    class Instance internal constructor(appContext: Context) {
        val time: TimeSource = RealTimeSource(appContext)
        val store: CoreStore = CoreStore.open(appContext)
        val writer: WriterExecutor = WriterExecutor()

        /** §11.5 always-on capture sink; level follows persisted config below. */
        val ringLog: RingLog = RingLog.open(java.io.File(appContext.noBackupFilesDir, CoreStore.DIR_NAME), time)

        /** §11.4 ring-trace buffer + sink tee; the repository traces through it. */
        val trace: SinkTrace = SinkTrace(ringLog)

        val repository: CallRepository = CallRepository.open(store, time, trace)

        /** §8.6 in-process signalling (orchestrator publishes on the writer). */
        val bus: CallBus = CallBus(ringLog)

        /**
         * Sweep probes (§8.10). notificationPresent is wired to the real
         * NotificationManager by the orchestrator's init (ui/ owns the ids);
         * ongoingServiceAlive stays `true` until OngoingCallService exists (M3)
         * — a lie in the safe direction (never falsely sweeps ACTIVE calls).
         */
        @Volatile
        var sweepEnv: SweepEnv = SweepEnv(notificationPresent = { true }, ongoingServiceAlive = true)

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

    /** §8.10: sweep on the writer at every native entry point. */
    fun sweepOnEntry(context: Context) {
        val core = get(context)
        core.writer.post { core.repository.sweep(core.sweepEnv) }
    }

    /** Tests only: drop the process singleton between cases. */
    internal fun resetForTests() {
        synchronized(this) {
            instance?.writer?.shutdown()
            instance = null
        }
    }
}
