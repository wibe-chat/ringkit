package com.wibechat.ringkit.adapter

import android.os.Handler
import android.os.Looper

/**
 * Tracks attached Flutter engines and the DESIGNATED delivery engine
 * (PLAN §10.3 step 1): the engine holding Activity attachment, falling back to
 * the most-recently-attached non-headless engine. Exactly one engine consumes
 * events; acks from any other engine are ignored by [RingKitHostApiImpl].
 *
 * Event sinks are EventChannel-backed and MUST be touched on the main thread —
 * [emitToDesignated] hops from the writer thread accordingly.
 */
internal object EngineRegistry {

    internal class Engine(val id: Int, val headless: Boolean) {
        var sink: PigeonEventSink<CallEventRecord>? = null
        var activityAttached: Boolean = false
    }

    private val engines = LinkedHashMap<Int, Engine>() // insertion order = attach order
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun register(id: Int, headless: Boolean = false) {
        engines[id] = Engine(id, headless)
    }

    @Synchronized
    fun unregister(id: Int) {
        engines.remove(id)
    }

    @Synchronized
    fun setSink(id: Int, sink: PigeonEventSink<CallEventRecord>?) {
        engines[id]?.sink = sink
    }

    @Synchronized
    fun setActivityAttached(id: Int, attached: Boolean) {
        engines[id]?.activityAttached = attached
    }

    @Synchronized
    fun designatedId(): Int? {
        val candidates = engines.values.filterNot { it.headless }
        return (candidates.lastOrNull { it.activityAttached } ?: candidates.lastOrNull())?.id
    }

    fun isDesignated(id: Int): Boolean = designatedId() == id

    /**
     * Live-path delivery (§10.2 rung 1). Returns whether a designated engine is
     * actively LISTENING (has a non-null sink) to receive them (checked
     * synchronously) — false ⇒ the caller routes to the background handler or
     * leaves the events for replay. The actual
     * `sink.success` hops to main; a designated engine vanishing in that window
     * simply leaves the events un-acked for replay (never lost).
     */
    fun emitToDesignated(records: List<CallEventRecord>): Boolean {
        if (records.isEmpty()) return false
        // Live delivery counts ONLY if the designated engine is actually LISTENING
        // (sink != null). A third-party background isolate that registered the plugin
        // without subscribing — notably FlutterFire's FCM background isolate under Mode 2,
        // which RingKit cannot flag headless (consumeExpectingHeadless only knows RingKit's
        // own booted engine) — becomes the fallback "designated" engine with a null sink.
        // Returning true here would post to nothing AND suppress the background handler
        // (decline/timeout/cancel-while-dead silently lost, v1.3.10, device-found). Report
        // NOT-consumed so the caller routes to the background handler (§10.2 rung 2).
        if (synchronized(this) { designatedId()?.let { engines[it]?.sink } } == null) return false
        main.post {
            val sink =
                synchronized(this) {
                    val id = designatedId() ?: return@post
                    engines[id]?.sink ?: return@post
                }
            records.forEach { sink.success(it) }
        }
        return true
    }

    @Synchronized
    internal fun resetForTests() {
        engines.clear()
    }
}
