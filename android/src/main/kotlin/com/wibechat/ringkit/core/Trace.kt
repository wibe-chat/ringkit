package com.wibechat.ringkit.core

/**
 * Minimal trace hook (§11.4 ring trace / §11.5 RingLog land in M2 — this seam
 * exists from day 1 so rejected/losing transitions are trace-only, never
 * silent, per the §5.1 zero-side-effect rule).
 */
interface CoreTrace {
    fun trace(tag: String, callId: String?, message: String)

    object NoOp : CoreTrace {
        override fun trace(tag: String, callId: String?, message: String) = Unit
    }
}

/** In-memory bounded trace (used by tests; the M2 RingLog sink replaces it). */
class RecordingTrace : CoreTrace {
    data class Entry(val tag: String, val callId: String?, val message: String)

    private val _entries = ArrayDeque<Entry>()
    val entries: List<Entry> get() = _entries.toList()

    override fun trace(tag: String, callId: String?, message: String) {
        _entries.addLast(Entry(tag, callId, message))
        while (_entries.size > CoreDurations.MAX_RING_TRACES) _entries.removeFirst()
    }
}
