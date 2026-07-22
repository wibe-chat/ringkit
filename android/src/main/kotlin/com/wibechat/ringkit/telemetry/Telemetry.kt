package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.CoreEventType
import com.wibechat.ringkit.core.CoreStore
import com.wibechat.ringkit.core.CoreTelemetryConfig
import com.wibechat.ringkit.core.RingKitCore
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * §11.5 telemetry gateway. FULLY INERT when `RingKitConfig.telemetry == null`
 * (rule 14): [activate] returns before touching WorkManager, the filesystem or
 * any seam. When enabled it wires the capture-side seams (CallBus tallies,
 * show-outcome tallies, incident-snapshot trigger), applies persisted knobs and
 * reconciles schedules — all on its own MIN_PRIORITY executor, NEVER the show
 * path. Core calls [activate] from every process entry (Instance.init) so a
 * killed-state incident ships without Flutter ever attaching.
 */
internal object Telemetry {

    /** Ships in every device block. Keep in sync with pubspec.yaml `version:`. */
    const val PLUGIN_VERSION = "0.2.0"

    @Volatile
    internal var session: Session? = null
        private set

    private val lock = Any()

    /** Test seams — production uses the real transport/scheduler. */
    internal var transportFactory: () -> CheckinTransport = { HttpCheckinTransport() }
    internal var schedulerFactory: (android.content.Context) -> TelemetryScheduler =
        { WorkTelemetryScheduler(it) }

    /** Lazy so an inert process never spins the thread. */
    private val executorDelegate = lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "ringkit-telemetry").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }
    }
    private val executor: Executor get() = executorDelegate.value

    // ── lifecycle ────────────────────────────────────────────────────────

    /** Every-process entry (core Instance.init). Null config ⇒ nothing happens. */
    fun activate(core: RingKitCore.Instance) {
        val config = core.repository.currentConfig().telemetry ?: return
        synchronized(lock) {
            if (session != null) return
            session = start(core, config)
        }
    }

    /** initialize() ran: follow enable/disable/change transitions. */
    fun onConfigChanged(core: RingKitCore.Instance) {
        val config = core.repository.currentConfig().telemetry
        synchronized(lock) {
            val current = session
            when {
                config == null && current == null -> Unit // stays inert
                config == null -> {
                    stop(current!!)
                    session = null
                }
                current == null -> session = start(core, config)
                current.config != config -> {
                    stop(current)
                    session = start(core, config)
                }
                else -> Unit
            }
        }
    }

    /** M3 "Report issue now" — false when telemetry is disabled. */
    fun userReportNow(): Boolean {
        val current = session ?: return false
        executor.execute { current.scheduler.scheduleUserReport() }
        return true
    }

    // ── session ──────────────────────────────────────────────────────────

    internal class Session(
        val core: RingKitCore.Instance,
        val config: CoreTelemetryConfig,
        val scheduler: TelemetryScheduler,
        private val transport: CheckinTransport,
    ) {
        /** Lazy: the file read happens on the executor / a worker thread. */
        val state: TelemetryState by lazy {
            TelemetryState.open(File(core.appContext.noBackupFilesDir, CoreStore.DIR_NAME), core.store.installId)
        }

        internal var busSubscription: AutoCloseable? = null

        fun engine(): TelemetryEngine =
            TelemetryEngine(
                installId = core.store.installId,
                time = core.time,
                ringLog = core.ringLog,
                appLogLevel = { core.repository.currentConfig().logLevel },
                appVersion = {
                    runCatching {
                        core.appContext.packageManager
                            .getPackageInfo(core.appContext.packageName, 0).versionName
                    }.getOrNull() ?: "unknown"
                },
                config = config,
                state = state,
                transport = transport,
                scheduler = scheduler,
            )
    }

    private fun start(core: RingKitCore.Instance, config: CoreTelemetryConfig): Session {
        val s = Session(core, config, schedulerFactory(core.appContext), transportFactory())
        // Seams are cheap enqueues — the writer / RingLog writer never blocks.
        core.showOutcomeSink = { outcome, fail, degradations ->
            executor.execute { recordShow(s, outcome, fail, degradations) }
        }
        core.ringLog.onIncidentSnapshot = { _ ->
            executor.execute { s.scheduler.scheduleIncidentUpload() }
        }
        s.busSubscription = core.bus.subscribe { signal ->
            executor.execute { recordEvent(s, signal.eventType) }
        }
        executor.execute { reconcile(s) }
        return s
    }

    private fun stop(s: Session) {
        s.core.showOutcomeSink = null
        s.core.ringLog.onIncidentSnapshot = null
        runCatching { s.busSubscription?.close() }
        s.scheduler.cancelAllWork()
        // Drop any server level override with the session.
        runCatching { s.core.ringLog.level = s.core.repository.currentConfig().logLevel }
    }

    /**
     * Activation reconciliation (R32 philosophy — every entry point sweeps):
     * re-apply persisted knobs, re-ensure the daily heartbeat, pick up incident
     * files a died process never got to enqueue, resume a pending pull.
     */
    private fun reconcile(s: Session) {
        runCatching {
            s.core.ringLog.level = s.state.knobLogLevel ?: s.core.repository.currentConfig().logLevel
            if (s.state.killed) {
                s.scheduler.scheduleKillPoll(delayed = false) // worker day-throttles
                return@runCatching
            }
            s.scheduler.ensureHeartbeat()
            if (s.engine().pendingIncidentFiles().isNotEmpty()) s.scheduler.scheduleIncidentUpload()
            if (s.state.pullPending) s.scheduler.schedulePull()
        }
    }

    // ── heartbeat tallies (§11.5 tier 2 inputs) ──────────────────────────

    private fun recordShow(s: Session, outcome: String, fail: String?, degradations: Set<String>) {
        runCatching {
            val day = TelemetryEngine.dayUtc(s.core.time.wallMs())
            when {
                fail != null -> s.state.incrementCounter(day, "fail.${TelemetryEngine.wireName(fail)}")
                outcome == "SHOWN" -> s.state.incrementCounter(day, "shows")
                outcome == "EXPIRED" -> s.state.incrementCounter(day, "expired")
            }
            degradations.forEach { s.state.incrementCounter(day, "deg.${TelemetryEngine.wireName(it)}") }
        }
    }

    private fun recordEvent(s: Session, type: CoreEventType?) {
        val key =
            when (type) {
                CoreEventType.ACCEPTED -> "accepts"
                CoreEventType.DECLINED -> "declines"
                CoreEventType.TIMED_OUT -> "timeouts"
                CoreEventType.CANCELLED -> "cancels"
                CoreEventType.ENDED -> "ends"
                CoreEventType.MISSED_CALL_TAPPED -> "missedTaps"
                else -> null
            } ?: return
        runCatching { s.state.incrementCounter(TelemetryEngine.dayUtc(s.core.time.wallMs()), key) }
    }

    /** Tests only (mirrors RingKitCore.resetForTests). */
    internal fun resetForTests() {
        synchronized(lock) {
            session?.let { stop(it) }
            session = null
            transportFactory = { HttpCheckinTransport() }
            schedulerFactory = { WorkTelemetryScheduler(it) }
        }
    }
}
