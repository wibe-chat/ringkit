package com.wibechat.ringkit.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process call-state signalling (PLAN §8.6): component-to-component
 * "call state changed" notification uses repository-observer callbacks —
 * NEVER broadcasts-to-activities (the incumbent's literally-undeliverable
 * design, #215/#247/#258/#578).
 *
 * Signals are published by the orchestrator ON THE WRITER THREAD immediately
 * after a transition persists; listeners may read the repository synchronously
 * (writer-confined = consistent) but MUST hop to main before touching UI.
 * A throwing listener never blocks the others (logged, §11.5).
 */
class CallBus internal constructor(private val ringLog: RingLog? = null) {

    /**
     * One transition's outward view. [liveState] is the record's state after
     * the transition — null means no live record remains (terminal, or the
     * signal is about a non-live artifact such as a missed-call tap).
     */
    data class CallSignal(
        val callId: String,
        val liveState: CoreCallState?,
        val eventType: CoreEventType?,
    ) {
        val terminal: Boolean get() = liveState == null
    }

    fun interface Listener {
        fun onSignal(signal: CallSignal)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun subscribe(listener: Listener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    fun publish(signal: CallSignal) {
        listeners.forEach { listener ->
            try {
                listener.onSignal(signal)
            } catch (t: Throwable) {
                ringLog?.incident(
                    RingLog.IncidentType.BOUNDARY_EXCEPTION,
                    "CallBus.publish",
                    signal.callId,
                    throwable = t,
                )
            }
        }
    }

    /** Listener count — used by tests and diagnostics. */
    fun listenerCount(): Int = listeners.size
}
