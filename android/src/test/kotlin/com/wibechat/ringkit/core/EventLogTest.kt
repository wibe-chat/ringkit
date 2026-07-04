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

    private fun event(seq: Long, callId: String = "c-$seq") =
        CoreEvent(
            seq = seq,
            callId = callId,
            type = CoreEventType.DECLINED,
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
