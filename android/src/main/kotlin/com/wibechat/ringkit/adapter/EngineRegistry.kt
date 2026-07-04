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

    /** Live-path delivery (§10.2 rung 1); undeliverable events stay in the log. */
    fun emitToDesignated(records: List<CallEventRecord>) {
        if (records.isEmpty()) return
        main.post {
            val sink =
                synchronized(this) {
                    val id = designatedId() ?: return@post
                    engines[id]?.sink ?: return@post
                }
            records.forEach { sink.success(it) }
        }
    }

    @Synchronized
    internal fun resetForTests() {
        engines.clear()
    }
}
