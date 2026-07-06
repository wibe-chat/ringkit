package com.wibechat.ringkit.core

/**
 * Append-only, seq-numbered, ack-gated event log (PLAN §10.1, §8.10 bounds).
 *
 * - Write happens FIRST in every action path (before UI effects/delivery).
 * - Live-only event types (RINGING, AUDIO_ROUTE_CHANGED) draw seq from the
 *   same counter but are never appended (nothing to replay); [drawSeq] serves
 *   them.
 * - Consumption: [ack] marks consumed exactly the delivered sequences ≤
 *   upToSeq (§10.3 — a late lower-seq arrival after a higher ack is never
 *   silently consumed). Consumed events are pruned immediately.
 * - Bounds: unconsumed capped at [CoreDurations.MAX_UNCONSUMED_EVENTS]; the
 *   oldest are dropped and counted in droppedEvents (§8.10).
 * - Leases (§10.2): events handed to a headless run are excluded from
 *   [takePending] until lease expiry. [takeLeased] re-derives the actionable
 *   batch after a cold start; the host [renewLease]s while working, [ack]s on
 *   the handler's completion, or [releaseLease]s on a graceful abort. A lease
 *   that expires without an ack is [reapExpiredLeases]d by the sweep — the
 *   event returns to the pending pool for replay and the caller records a
 *   LEASE_EXPIRED incident (R38).
 *
 * Confined to the writer executor (§8.9).
 */
class EventLog internal constructor(
    private val store: CoreStore,
    private val time: TimeSource,
) {
    companion object {
        /**
         * §10.2 rung 2: the terminal events that reach a background handler
         * while the app is dead. ACCEPTED/MISSED_CALL_TAPPED launch the app (not
         * headless-delivered); RINGING/AUDIO_ROUTE_CHANGED are live-only (never
         * appended); ENDED terminates an already-established call (the app was
         * alive for it).
         */
        val ACTIONABLE_TYPES =
            setOf(CoreEventType.DECLINED, CoreEventType.TIMED_OUT, CoreEventType.CANCELLED)
    }

    private var events: MutableList<CoreEvent> = store.readEvents().toMutableList()
    private var nextSeq: Long = maxOf(store.readNextSeq(), (events.maxOfOrNull { it.seq } ?: 0L) + 1L)
    private var droppedEvents: Int = store.readDroppedEvents()

    /** Monotonic across the install; persists the counter before returning. */
    fun drawSeq(): Long {
        val seq = nextSeq
        nextSeq += 1
        store.writeNextSeq(nextSeq)
        return seq
    }

    /** Appends a persisted event (caller draws seq via [drawSeq]). */
    fun append(event: CoreEvent) {
        events.add(event)
        store.appendEvent(event)
        enforceBounds()
    }

    /** Unconsumed, un-leased, seq-ordered (§10.3 step 3). */
    fun takePending(): List<CoreEvent> {
        val now = time.elapsedMs()
        return events
            .filter { !it.consumed && (it.leaseUntilElapsedMs == null || it.leaseUntilElapsedMs <= now) }
            .sortedBy { it.seq }
    }

    /**
     * Marks consumed exactly [deliveredSequences] ∩ (seq ≤ [upToSeq]); consumed
     * events are pruned (§8.10). Returns the number actually consumed.
     */
    fun ack(upToSeq: Long, deliveredSequences: Collection<Long>): Int {
        val delivered = deliveredSequences.filter { it <= upToSeq }.toHashSet()
        if (delivered.isEmpty()) return 0
        val before = events.size
        events = events.filterNot { it.seq in delivered }.toMutableList()
        val consumed = before - events.size
        if (consumed > 0) store.rewriteEvents(events)
        return consumed
    }

    /** Leases [sequences] to a headless run for [CoreDurations.EVENT_LEASE_MS] (§10.2). */
    fun lease(sequences: Collection<Long>): Int {
        val target = sequences.toHashSet()
        val until = time.elapsedMs() + CoreDurations.EVENT_LEASE_MS
        var changed = 0
        events = events.map { e ->
            if (e.seq in target && !e.consumed) {
                changed++
                e.copy(leaseUntilElapsedMs = until)
            } else {
                e
            }
        }.toMutableList()
        if (changed > 0) store.rewriteEvents(events)
        return changed
    }

    /**
     * Extends the lease on **still-active**, unconsumed [sequences] (host
     * keep-alive, §10.2). An already-expired lease is deliberately NOT renewed —
     * the sweep may already have handed it back to replay, so re-hiding it would
     * reopen the double-delivery window this lease exists to close. Returns the
     * number renewed.
     */
    fun renewLease(sequences: Collection<Long>): Int {
        val now = time.elapsedMs()
        val target = sequences.toHashSet()
        val until = now + CoreDurations.EVENT_LEASE_MS
        var changed = 0
        events =
            events.map { e ->
                if (e.seq in target && !e.consumed && (e.leaseUntilElapsedMs ?: 0L) > now) {
                    changed++
                    e.copy(leaseUntilElapsedMs = until)
                } else {
                    e
                }
            }.toMutableList()
        if (changed > 0) store.rewriteEvents(events)
        return changed
    }

    /**
     * Currently-leased (active), unconsumed events the headless host holds,
     * seq-ordered. [actionableOnly] restricts to [ACTIONABLE_TYPES] — the durable
     * fallback (expedited work after process death) re-derives its handler batch
     * here without the originating transition in hand (§10.2).
     */
    fun takeLeased(actionableOnly: Boolean): List<CoreEvent> {
        val now = time.elapsedMs()
        return events
            .filter {
                !it.consumed &&
                    (it.leaseUntilElapsedMs ?: 0L) > now &&
                    (!actionableOnly || it.type in ACTIONABLE_TYPES)
            }
            .sortedBy { it.seq }
    }

    /**
     * Hands leased [sequences] back to the pending pool WITHOUT consuming them
     * (graceful host abort — replay covers them immediately, no LEASE_EXPIRED;
     * §10.2). Distinct from [ack], which prunes. Returns the number released.
     */
    fun releaseLease(sequences: Collection<Long>): Int {
        val target = sequences.toHashSet()
        var changed = 0
        events =
            events.map { e ->
                if (e.seq in target && !e.consumed && e.leaseUntilElapsedMs != null) {
                    changed++
                    e.copy(leaseUntilElapsedMs = null)
                } else {
                    e
                }
            }.toMutableList()
        if (changed > 0) store.rewriteEvents(events)
        return changed
    }

    /**
     * Clears leases that **expired without a completion ack** (a failed headless
     * delivery, R38) and RETURNS the affected events so the caller records one
     * `LEASE_EXPIRED` incident each (§10.2). The events revert to un-leased —
     * replay then re-delivers them. A successful delivery left nothing behind
     * ([ack] pruned it), so this never fires for one. Idempotent: the second
     * call over the same expiry finds the field already cleared and returns
     * empty (no duplicate incidents). Run from the reconciliation sweep at every
     * native entry point (§8.10).
     */
    fun reapExpiredLeases(): List<CoreEvent> {
        val now = time.elapsedMs()
        val expired =
            events.filter {
                !it.consumed && it.leaseUntilElapsedMs != null && it.leaseUntilElapsedMs <= now
            }
        if (expired.isEmpty()) return emptyList()
        val expiredSeqs = expired.mapTo(HashSet()) { it.seq }
        events = events.map { e -> if (e.seq in expiredSeqs) e.copy(leaseUntilElapsedMs = null) else e }.toMutableList()
        store.rewriteEvents(events)
        return expired.sortedBy { it.seq }
    }

    fun unconsumedCount(): Int = events.count { !it.consumed }

    fun activeLeaseCount(): Int {
        val now = time.elapsedMs()
        return events.count { !it.consumed && (it.leaseUntilElapsedMs ?: 0L) > now }
    }

    fun droppedCount(): Int = droppedEvents

    fun all(): List<CoreEvent> = events.toList()

    /** Latest persisted terminal event for [callId] (alreadyEnded launch actions, §4.3). */
    fun latestTerminalFor(callId: String): CoreEvent? =
        events
            .filter {
                it.callId == callId &&
                    it.type in
                    setOf(
                        CoreEventType.DECLINED,
                        CoreEventType.TIMED_OUT,
                        CoreEventType.ENDED,
                        CoreEventType.CANCELLED,
                    )
            }
            .maxByOrNull { it.seq }

    private fun enforceBounds() {
        var unconsumed = events.count { !it.consumed }
        if (unconsumed <= CoreDurations.MAX_UNCONSUMED_EVENTS) return
        // Drop the OLDEST unconsumed until at the cap (§8.10); count them.
        val sorted = events.sortedBy { it.seq }.toMutableList()
        val toDrop = HashSet<Long>()
        for (e in sorted) {
            if (unconsumed <= CoreDurations.MAX_UNCONSUMED_EVENTS) break
            if (!e.consumed) {
                toDrop.add(e.seq)
                unconsumed--
            }
        }
        if (toDrop.isNotEmpty()) {
            droppedEvents += toDrop.size
            store.writeDroppedEvents(droppedEvents)
            events = events.filterNot { it.seq in toDrop }.toMutableList()
            store.rewriteEvents(events)
        }
    }
}
