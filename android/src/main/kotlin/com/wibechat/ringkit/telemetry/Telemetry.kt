package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.CoreEventType
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreStore
import com.wibechat.ringkit.core.CoreTelemetryConfig
import com.wibechat.ringkit.core.RingKitCore
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import com.wibechat.ringkit.diag.DiagnosticsEngine
import org.json.JSONArray
import org.json.JSONObject

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
    val PLUGIN_VERSION: String = com.wibechat.ringkit.BuildConfig.RINGKIT_VERSION

    @Volatile
    internal var session: Session? = null
        private set

    private val lock = Any()
    private var stateOwner: RingKitCore.Instance? = null
    private var sharedState: Lazy<TelemetryState>? = null

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

    /** initialize() can enable telemetry after the host Activity has already started. */
    fun seedForeground(activity: android.app.Activity?) {
        val current = session ?: return
        if (activity == null) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (current.active && !activity.isFinishing && !activity.isDestroyed &&
                (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true) {
                current.lifecycle?.onActivityStarted(activity)
            }
        }
    }

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
        executor.execute { if (current.active) current.scheduler.scheduleUserReport() }
        return true
    }

    /**
     * §11.5 tester settings snapshot — null when telemetry is disabled. Reads
     * the (already-loaded) state file; NEVER exposes the apiKey or token. Safe
     * off any thread (state reads are synchronized); no network.
     */
    fun status(): StatusSnapshot? {
        val s = session ?: return null
        val state = s.state
        val today = TelemetryEngine.dayUtc(s.core.time.wallMs())
        return StatusSnapshot(
            deviceLabel = s.config.deviceLabel,
            endpoint = s.config.endpoint,
            registered = state.deviceToken != null,
            captureLevel = s.core.ringLog.level,
            pullPending = state.pullPending,
            sampled = state.sampled,
            budgetKb = state.knobBudgetKb ?: s.config.dailyUploadBudgetKb,
            killed = state.killed,
            bytesUploadedToday = state.bytesToday(today),
        )
    }

    /** Flutter-free status holder; the adapter maps it to the Pigeon type. */
    internal data class StatusSnapshot(
        val deviceLabel: String,
        val endpoint: String,
        val registered: Boolean,
        val captureLevel: CoreLogLevel,
        val pullPending: Boolean,
        val sampled: Boolean,
        val budgetKb: Int,
        val killed: Boolean,
        val bytesUploadedToday: Int,
    )

    // ── session ──────────────────────────────────────────────────────────

    internal class Session(
        val core: RingKitCore.Instance,
        val config: CoreTelemetryConfig,
        val scheduler: TelemetryScheduler,
        private val transport: CheckinTransport,
        private val stateDelegate: Lazy<TelemetryState>,
    ) {
        @Volatile var active = true
        @Volatile var activeCaptureUntil = 0L
        var lifecycle: TelemetryLifecycle? = null
        /** Lazy: the file read happens on the executor / a worker thread. */
        val state: TelemetryState get() = stateDelegate.value

        internal var busSubscription: AutoCloseable? = null

        fun engine(enabled: () -> Boolean = { active }): TelemetryEngine =
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
                packageName = core.appContext.packageName,
                config = config,
                state = state,
                transport = transport,
                scheduler = scheduler,
                identity = { NativeIdentity.capture(core.appContext) },
                diagnose = {
                    core.writer.await {
                        JSONArray(DiagnosticsEngine.run(core.appContext, core).map {
                            JSONObject().put("id", it.id).put("severity", it.severity.name)
                        })
                    }
                },
                enabled = enabled,
                onCaptureChanged = {
                    activeCaptureUntil = if (it) state.diagnostics.requests().filter { r -> r.optString("kind") == "diagnostic_session" && !r.optBoolean("complete") }.maxOfOrNull { r -> r.optLong("deadline") } ?: 0L else 0L
                    lifecycle?.refreshCadence()
                },
            )
    }

    private fun start(core: RingKitCore.Instance, config: CoreTelemetryConfig): Session {
        if (stateOwner !== core) {
            stateOwner = core
            sharedState = lazy { TelemetryState.open(File(core.appContext.noBackupFilesDir, CoreStore.DIR_NAME), core.store.installId) }
        }
        val s = Session(core, config, schedulerFactory(core.appContext), transportFactory(), sharedState!!)
        core.ringLog.diagnosticIdentityJson = NativeIdentity.capture(core.appContext).toString()
        // Seams are cheap enqueues — the writer / RingLog writer never blocks.
        core.showOutcomeSink = { outcome, fail, degradations ->
            executor.execute { if (s.active) recordShow(s, outcome, fail, degradations) }
        }
        core.ringLog.onIncidentSnapshot = { _ ->
            executor.execute { if (s.active) s.scheduler.scheduleIncidentUpload() }
        }
        s.busSubscription = core.bus.subscribe { signal ->
            executor.execute {
                if (!s.active) return@execute
                recordEvent(s, signal.eventType)
                if (!s.state.killed && s.state.serverProtocol >= 2 && s.state.diagnosticsEnabled && s.state.diagnostics.active(core.time)) {
                    val changed = s.state.diagnostics.noteCall(signal.callId, signal.liveState == com.wibechat.ringkit.core.CoreCallState.RINGING, core.time, NativeIdentity.sdk().optString("sdk_build_id"))
                    if (changed && signal.liveState != com.wibechat.ringkit.core.CoreCallState.RINGING) s.scheduler.scheduleControl(1_000, afterCurrent = true)
                }
            }
        }
        s.lifecycle = TelemetryLifecycle(core.appContext, { foreground -> enqueueControl(s, foreground) }, {
            if (core.time.elapsedMs() < s.activeCaptureUntil) 60_000L else 300_000L
        })
        executor.execute { reconcile(s) }
        return s
    }

    private fun stop(s: Session) {
        s.active = false
        s.lifecycle?.close()
        s.core.showOutcomeSink = null
        s.core.ringLog.onIncidentSnapshot = null
        s.core.ringLog.diagnosticIdentityJson = null
        runCatching { s.busSubscription?.close() }
        s.scheduler.cancelAllWork()
        executor.execute { s.state.diagnostics.stopAll("disabled") }
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
            if (!s.active) return@runCatching
            s.core.ringLog.level = s.state.knobLogLevel ?: s.core.repository.currentConfig().logLevel
            if (s.state.killed) {
                s.scheduler.scheduleKillPoll(delayed = false) // worker day-throttles
                return@runCatching
            }
            s.state.diagnostics.reconcile(s.core.time, NativeIdentity.sdk().optString("sdk_build_id"))
            s.scheduler.ensureHeartbeat()
            if (s.engine().pendingIncidentFiles().isNotEmpty()) s.scheduler.scheduleIncidentUpload()
            if (s.state.pullPending) s.scheduler.schedulePull()
            if (s.state.pendingUserReport != null) s.scheduler.scheduleUserReport()
            enqueueControl(s, true)
        }
    }

    private fun enqueueControl(s: Session, foreground: Boolean) {
        executor.execute {
            if (!s.active || s.state.killed || (!foreground && !s.state.diagnosticsEnabled)) return@execute
            // Discover enrollment on an explicit app entry even if yesterday's
            // build was not in the canary. No repeated polling before enrollment.
            if (foreground && !s.state.diagnosticsEnabled) s.state.requestMetadata()
            val bootstrap = s.state.metadataDue(TelemetryEngine.dayUtc(s.core.time.wallMs()), NativeIdentity.capture(s.core.appContext).toString())
            if (!bootstrap && !s.state.diagnosticsEnabled) return@execute
            val delay = s.state.controlDelay(s.core.time.bootCount(), s.core.time.elapsedMs())
            if (s.state.reserveControlEnqueue(s.core.time.bootCount(), s.core.time.elapsedMs(), 30_000L) || foreground) s.scheduler.scheduleControl(delay)
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
