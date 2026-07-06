package com.wibechat.ringkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** §10.1/§10.3 EventLog semantics + §8.10 bounds. */
@RunWith(RobolectricTestRunner::class)
class EventLogTest {

    private val time = FakeTime()
    private val dir = TestSupport.newTempDir()
    private val store = CoreStore.openAt(dir)
    private val log = EventLog(store, time)

    private fun event(seq: Long, callId: String = "c-$seq", type: CoreEventType = CoreEventType.DECLINED) =
        CoreEvent(
            seq = seq,
            callId = callId,
            type = type,
            callInfo =
                CoreCallInfo(
                    id = callId,
                    name = "Alice",
                    handle = null,
                    avatarUrl = null,
                    type = CoreCallType.AUDIO,
                    direction = CoreCallDirection.INCOMING,
                    extra = emptyMap(),
                ),
            wallMs = time.wallMs(),
            elapsedMs = time.elapsedMs(),
            bootCount = time.bootCount(),
        )

    @Test
    fun seq_is_monotonic_and_survives_restart() {
        val first = log.drawSeq()
        val second = log.drawSeq()
        assertTrue(second > first)

        // "Process death": a new log over the same store must not reuse seqs,
        // even after events are pruned (§10.1: counter persisted separately).
        log.append(event(second))
        log.ack(second, listOf(second))
        val reopened = EventLog(store, time)
        assertTrue(reopened.drawSeq() > second)
    }

    @Test
    fun ack_consumes_only_delivered_sequences_up_to_seq() {
        listOf(1L, 2L, 3L, 4L).forEach { log.append(event(it)) }

        // Facade delivered 1 and 3 (2 arrived late, 4 beyond upToSeq).
        val consumed = log.ack(upToSeq = 3L, deliveredSequences = listOf(1L, 3L, 4L))
        assertEquals(2, consumed)

        // §10.3: the late lower-seq event (2) must NOT be silently consumed.
        assertEquals(listOf(2L, 4L), log.takePending().map { it.seq })
    }

    @Test
    fun ack_from_foreign_list_never_over_consumes() {
        log.append(event(10L))
        assertEquals(0, log.ack(upToSeq = 100L, deliveredSequences = emptyList()))
        assertEquals(1, log.takePending().size)
    }

    @Test
    fun unconsumed_capped_at_200_oldest_dropped_and_counted() {
        for (seq in 1L..205L) log.append(event(seq))
        assertEquals(CoreDurations.MAX_UNCONSUMED_EVENTS, log.unconsumedCount())
        assertEquals(5, log.droppedCount())
        // Oldest were dropped: the log starts at seq 6.
        assertEquals(6L, log.takePending().first().seq)

        // droppedEvents counter survives restart (§8.10 diagnostics counter).
        assertEquals(5, EventLog(store, time).droppedCount())
    }

    @Test
    fun leased_events_are_excluded_until_expiry() {
        log.append(event(1L))
        log.append(event(2L))
        log.lease(listOf(1L))

        assertEquals(listOf(2L), log.takePending().map { it.seq })
        assertEquals(1, log.activeLeaseCount())

        // Lease expiry restores visibility (§10.2 — main engine can replay it).
        time.advanceMs(CoreDurations.EVENT_LEASE_MS + 1)
        assertEquals(listOf(1L, 2L), log.takePending().map { it.seq })
        assertEquals(0, log.activeLeaseCount())
    }

    @Test
    fun leased_event_completion_acks_and_prunes() {
        log.append(event(1L))
        log.lease(listOf(1L))
        assertEquals(1, log.ack(1L, listOf(1L)))
        assertTrue(log.takePending().isEmpty())
        time.advanceMs(CoreDurations.EVENT_LEASE_MS + 1)
        assertTrue("consumed events never resurface", log.takePending().isEmpty())
    }

    @Test
    fun main_engine_mid_handler_cannot_see_leased_event() {
        // The double-delivery invariant (§10.2): while a headless run holds the
        // lease, an engine attaching mid-handler and flushing pending events
        // never observes the leased seq — only the un-leased one.
        log.append(event(1L))
        log.append(event(2L))
        log.lease(listOf(1L))
        assertEquals(listOf(2L), log.takePending().map { it.seq })
    }

    @Test
    fun takeLeased_returns_active_leases_with_actionable_filter() {
        log.append(event(1L, type = CoreEventType.DECLINED)) // actionable
        log.append(event(2L, type = CoreEventType.ACCEPTED)) // launches the app, not headless
        log.append(event(3L)) // DECLINED, left un-leased
        log.lease(listOf(1L, 2L))

        assertEquals("only leased, actionable events reach the handler", listOf(1L), log.takeLeased(actionableOnly = true).map { it.seq })
        assertEquals("all active leases when unfiltered", listOf(1L, 2L), log.takeLeased(actionableOnly = false).map { it.seq })

        // Expiry ends the lease → nothing is "leased" any more.
        time.advanceMs(CoreDurations.EVENT_LEASE_MS + 1)
        assertTrue(log.takeLeased(actionableOnly = true).isEmpty())
    }

    @Test
    fun renewLease_extends_only_still_active_leases() {
        log.append(event(1L))
        log.lease(listOf(1L))

        // Just before expiry: renewal pushes the deadline out a full window.
        time.advanceMs(CoreDurations.EVENT_LEASE_MS - 1)
        assertEquals(1, log.renewLease(listOf(1L)))

        // Past the ORIGINAL deadline but inside the renewed one: still hidden.
        time.advanceMs(2)
        assertEquals(1, log.activeLeaseCount())
        assertTrue(log.takePending().isEmpty())

        // An already-expired lease is never resurrected (would reopen the
        // double-delivery window the sweep just closed).
        time.advanceMs(CoreDurations.EVENT_LEASE_MS + 1)
        assertEquals(0, log.renewLease(listOf(1L)))
        assertEquals(listOf(1L), log.takePending().map { it.seq })
    }

    @Test
    fun releaseLease_returns_event_to_pending_without_consuming() {
        log.append(event(1L))
        log.lease(listOf(1L))
        assertTrue(log.takePending().isEmpty())

        assertEquals(1, log.releaseLease(listOf(1L)))
        assertEquals("visible again immediately, no expiry wait", listOf(1L), log.takePending().map { it.seq })
        assertEquals("released ≠ consumed", 1, log.unconsumedCount())
        assertEquals(0, log.activeLeaseCount())
        // Releasing an already-released (un-leased) event is a no-op.
        assertEquals(0, log.releaseLease(listOf(1L)))
    }

    @Test
    fun reapExpiredLeases_returns_expired_unconsumed_then_clears() {
        log.append(event(1L))
        log.append(event(2L))
        log.lease(listOf(1L, 2L))
        time.advanceMs(CoreDurations.EVENT_LEASE_MS + 1)

        val reaped = log.reapExpiredLeases()
        assertEquals("expiry-without-ack surfaces both for a LEASE_EXPIRED incident", listOf(1L, 2L), reaped.map { it.seq })
        assertEquals(0, log.activeLeaseCount())
        assertEquals("reaped events revert to plain pending (replay covers them)", listOf(1L, 2L), log.takePending().map { it.seq })

        // Idempotent: a second sweep over the same expiry emits nothing.
        assertTrue(log.reapExpiredLeases().isEmpty())
    }

    @Test
    fun reapExpiredLeases_ignores_acked_and_still_active_leases() {
        log.append(event(1L)) // will be acked (successful headless delivery)
        log.append(event(2L)) // lease stays active
        log.lease(listOf(1L, 2L))
        log.ack(1L, listOf(1L)) // completion ack prunes seq 1

        // Not yet expired: an active lease is not a failure.
        assertTrue(log.reapExpiredLeases().isEmpty())

        time.advanceMs(CoreDurations.EVENT_LEASE_MS + 1)
        // Only the un-acked lease is reaped; the acked one left nothing behind.
        assertEquals(listOf(2L), log.reapExpiredLeases().map { it.seq })
    }

    @Test
    fun corrupt_event_lines_are_skipped_and_counted() {
        log.append(event(1L))
        val file = java.io.File(dir, "events.jsonl")
        file.appendText("{not valid json}\n")
        file.appendText(CoreJson.event(event(2L)).toString() + "\n")

        val reopened = CoreStore.openAt(dir)
        val events = reopened.readEvents()
        assertEquals(listOf(1L, 2L), events.map { it.seq })
        assertEquals(1, reopened.quarantinedCount)
    }
}
