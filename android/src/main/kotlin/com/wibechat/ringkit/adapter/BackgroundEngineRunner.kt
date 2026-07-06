package com.wibechat.ringkit.adapter

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wibechat.ringkit.core.EventLog
import com.wibechat.ringkit.core.RingKitCore
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineGroup
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.view.FlutterCallbackInformation

/**
 * §10.2 rung 2 — hosts the headless FlutterEngine that runs the app's registered
 * background handler while the app is dead.
 *
 * [prepareOnWriter] (writer thread, testable in isolation) reads the persisted
 * handles, verifies the handler still resolves, and leases the actionable
 * pending batch. A stale handle (app updated → no longer resolves) skips WITHOUT
 * leasing: the events stay pending for replay and `diagnose()` surfaces
 * `backgroundHandlerStale` — never a crash (§10.4). [deliverPending] then boots
 * the engine (main thread) only when a batch was leased; the dispatcher pulls it
 * via [RingKitBackgroundApi], invokes the handler, and acks on completion.
 *
 * The FlutterEngineGroup is cached process-wide (keeps the VM warm); the engine
 * is torn down after an idle grace once the dispatcher finishes. The hosts that
 * keep the process alive around this — EventDeliveryService (34+) / expedited
 * WorkManager (26–33), §8.1 — are the next milestone; this class is transport-
 * agnostic and unaware of which one invoked it.
 */
class BackgroundEngineRunner(
    context: Context,
    private val core: RingKitCore.Instance,
    private val engineHost: BackgroundEngineHost = FlutterEngineHost,
    private val callbackResolvable: (Long) -> Boolean = ::resolvable,
) {
    private val appContext = context.applicationContext

    enum class Prep { UNREGISTERED, NOTHING_PENDING, STALE, LEASED }

    /**
     * Writer-thread decision + lease. UNREGISTERED (no handler) / NOTHING_PENDING
     * (no actionable events) / STALE (handle no longer resolves — kept for
     * replay, no lease) / LEASED (batch fenced for the headless run).
     */
    fun prepareOnWriter(): Prep {
        val handles = core.store.readBackgroundHandles() ?: return Prep.UNREGISTERED
        val actionable = core.repository.eventLog.takePending().filter { it.type in EventLog.ACTIONABLE_TYPES }
        if (actionable.isEmpty()) return Prep.NOTHING_PENDING
        if (!callbackResolvable(handles.second)) {
            core.trace.trace(
                "bg", null,
                "background handler handle stale — keeping ${actionable.size} event(s) for replay",
            )
            return Prep.STALE
        }
        core.repository.eventLog.lease(actionable.map { it.seq })
        return Prep.LEASED
    }

    /** Prepare on the writer; boot the engine (host self-hops to main) only when leased. */
    fun deliverPending(onPrepared: (Prep) -> Unit = {}) {
        core.writer.submit({ prepareOnWriter() }) { result ->
            val prep = result.getOrDefault(Prep.UNREGISTERED)
            if (prep == Prep.LEASED) engineHost.boot(appContext)
            onPrepared(prep)
        }
    }

    companion object {
        /**
         * Set by [FlutterEngineHost] immediately before booting so the plugin
         * registers the background engine as headless (never designated, §10.3).
         * Read-and-cleared once at attach (single-threaded boot ⇒ no interleave).
         */
        @Volatile
        private var expectingHeadless: Boolean = false

        fun markExpectingHeadless() {
            expectingHeadless = true
        }

        @Synchronized
        fun consumeExpectingHeadless(): Boolean {
            val v = expectingHeadless
            expectingHeadless = false
            return v
        }

        private fun resolvable(handle: Long): Boolean =
            runCatching { FlutterCallbackInformation.lookupCallbackInformation(handle) != null }.getOrDefault(false)
    }
}

/**
 * Seam over the real (device-only) engine lifecycle so [BackgroundEngineRunner]'s
 * decision logic is Robolectric-testable without a Dart VM.
 */
interface BackgroundEngineHost {
    fun boot(context: Context)

    fun scheduleTeardown()
}

/**
 * Production headless-engine lifecycle (§10.2). All engine ops run on the MAIN
 * thread. One headless engine per process at a time; the FlutterEngineGroup is
 * cached (VM stays warm) and the engine destroyed after [IDLE_GRACE_MS] once the
 * dispatcher signals it drained its batch.
 */
internal object FlutterEngineHost : BackgroundEngineHost {
    private const val IDLE_GRACE_MS = 5_000L
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var group: FlutterEngineGroup? = null

    @Volatile
    private var engine: FlutterEngine? = null

    private var teardown: Runnable? = null

    override fun boot(context: Context) {
        val app = context.applicationContext
        main.post {
            cancelTeardown()
            if (engine != null) return@post // one headless engine per process
            val loader = FlutterInjector.instance().flutterLoader()
            loader.startInitialization(app)
            loader.ensureInitializationComplete(app, null)
            BackgroundEngineRunner.markExpectingHeadless()
            val entrypoint = DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "ringkitBackgroundDispatcher")
            val g = group ?: FlutterEngineGroup(app).also { group = it }
            engine = g.createAndRunEngine(app, entrypoint)
        }
    }

    override fun scheduleTeardown() {
        main.post {
            cancelTeardown()
            val r =
                Runnable {
                    engine?.destroy()
                    engine = null
                    teardown = null
                }
            teardown = r
            main.postDelayed(r, IDLE_GRACE_MS)
        }
    }

    private fun cancelTeardown() {
        teardown?.let { main.removeCallbacks(it) }
        teardown = null
    }
}
