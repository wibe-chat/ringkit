package com.wibechat.ringkit.core

import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cross-restart and concurrency guarantees that individual table cells can't
 * express: cold-process reconstruction (R1 at core level), launch-action
 * one-shot (R44), stable notification IDs (§5.2), writer serialization (§5.2).
 */
@RunWith(RobolectricTestRunner::class)
class RepositoryLifecycleTest {

    private val dir = TestSupport.newTempDir()
    private val time = FakeTime()

    private fun repo(): CallRepository = CallRepository.open(CoreStore.openAt(dir), time)

    @Test
    fun state_survives_process_death_and_reconstruction() {
        val first = repo()
        first.dispatch(TransitionInput.Show(TestSupport.incomingCall(id = "persist-1")))
        first.dispatch(TransitionInput.Accept("persist-1", fromAction = true))

        // "Process death": a brand-new repository over the same directory.
        val second = repo()
        assertEquals(CoreCallState.ACCEPTED, second.callFor("persist-1")?.state)
        assertEquals(1, second.eventLog.takePending().size)
        assertNotNull("launch action survives recreation (R44)", second.peekLaunchAction())
        assertNotNull("registry survives", second.notificationIdFor("persist-1"))
    }

    @Test
    fun launch_action_is_a_one_shot() {
        val r = repo()
        r.dispatch(TransitionInput.Show(TestSupport.incomingCall(id = "one-shot")))
        r.dispatch(TransitionInput.Accept("one-shot", fromAction = true))

        val consumed = r.consumeLaunchAction()
        assertNotNull(consumed)
        assertEquals(CoreEventType.ACCEPTED, consumed!!.type)
        assertNull("clears on read", r.consumeLaunchAction())
        assertNull("cleared for new repositories too", repo().consumeLaunchAction())
    }

    @Test
    fun notification_ids_are_stable_and_allocated_from_base() {
        val r = repo()
        r.dispatch(TransitionInput.Show(TestSupport.incomingCall(id = "a")))
        r.dispatch(TransitionInput.Show(TestSupport.incomingCall(id = "b")))
        val idA = r.notificationIdFor("a")
        val idB = r.notificationIdFor("b")
        assertEquals(424200, idA)
        assertEquals(424201, idB)

        // Re-show of the same id keeps its id (idempotency); a new repository
        // over the same store sees the same registry.
        assertEquals(idA, repo().notificationIdFor("a"))
    }

    @Test
    fun freed_ids_are_reusable_after_tombstone_prune() {
        val r = repo()
        r.dispatch(TransitionInput.Show(TestSupport.incomingCall(id = "a")))
        r.dispatch(TransitionInput.Decline("a"))
        time.advanceMs(CoreDurations.TOMBSTONE_TTL_MS + 1)
        r.sweep(SweepEnv(notificationPresent = { true }, ongoingServiceAlive = true))
        assertNull("registry pruned with tombstone", r.notificationIdFor("a"))

        r.dispatch(TransitionInput.Show(TestSupport.incomingCall(id = "z")))
        assertEquals("freed id reused", 424200, r.notificationIdFor("z"))
    }

    @Test
    fun concurrent_same_id_shows_serialize_one_wins() {
        val writer = WriterExecutor()
        val r = repo()
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<TransitionOutcome>())
        val done = CountDownLatch(2)

        repeat(2) {
            Thread {
                writer.submit({
                    r.dispatch(TransitionInput.Show(TestSupport.incomingCall(id = "race-1")))
                }) { result ->
                    outcomes.add(result.getOrThrow().outcome)
                    done.countDown()
                }
            }.start()
        }

        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        writer.shutdown()
        // §5.2 concurrency guarantee: as-if-sequential — exactly one SHOWN.
        assertEquals(
            listOf("ALREADY_SHOWN", "SHOWN"),
            outcomes.map { it.name }.sorted(),
        )
        assertEquals(1, r.activeCalls().size)
    }

    @Test
    fun events_round_trip_snapshot_after_call_record_is_gone() {
        val r = repo()
        r.dispatch(
            TransitionInput.Show(
                TestSupport.incomingCall(id = "gone", extra = mapOf("session" to "s-42")),
            ),
        )
        r.dispatch(TransitionInput.Decline("gone"))

        // The live record is gone; the event still carries the full snapshot
        // (events outlive calls — replay after cleanup, §10.1).
        assertNull(r.callFor("gone"))
        val pending = r.eventLog.takePending()
        assertEquals(1, pending.size)
        assertEquals("s-42", pending.first().callInfo.extra["session"])
        assertEquals("Alice", pending.first().callInfo.name)
    }
}
