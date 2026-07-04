package com.wibechat.ringkit.adapter

import com.wibechat.ringkit.core.CallRepository
import com.wibechat.ringkit.core.CoreDeliveryPath
import com.wibechat.ringkit.core.CoreStore
import com.wibechat.ringkit.core.FakeTime
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.TestSupport
import com.wibechat.ringkit.core.TransitionInput
import com.wibechat.ringkit.core.TransitionOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** M1 adapter: designation rules (§10.3), foreign-ack ignore, handle persistence (§10.4). */
@RunWith(RobolectricTestRunner::class)
class AdapterTest {

    @After
    fun tearDown() {
        EngineRegistry.resetForTests()
        RingKitCore.resetForTests()
    }

    // ── EngineRegistry designation (§10.3 step 1) ────────────────────────

    @Test
    fun designated_prefers_activity_attached_over_recency() {
        EngineRegistry.register(1)
        EngineRegistry.register(2)
        assertEquals("most recent wins absent activity", 2, EngineRegistry.designatedId())

        EngineRegistry.setActivityAttached(1, true)
        assertEquals("activity attachment wins", 1, EngineRegistry.designatedId())

        EngineRegistry.setActivityAttached(1, false)
        assertEquals(2, EngineRegistry.designatedId())
    }

    @Test
    fun headless_engines_are_never_designated() {
        EngineRegistry.register(1)
        EngineRegistry.register(99, headless = true)
        assertEquals("headless never designated", 1, EngineRegistry.designatedId())
        EngineRegistry.unregister(1)
        assertEquals("no candidates → none", null, EngineRegistry.designatedId())
    }

    // ── ack gating through the host API (§10.3) ──────────────────────────

    @Test
    fun ack_from_non_designated_engine_is_ignored() {
        val core = RingKitCore.get(RuntimeEnvironment.getApplication())
        val repo = core.repository

        awaitWriter(core) { repo.dispatch(TransitionInput.Show(TestSupport.incomingCall(id = "ack-1"))) }
        awaitWriter(core) { repo.dispatch(TransitionInput.Decline("ack-1")) }
        val seq = repo.eventLog.takePending().single().seq

        EngineRegistry.register(1)
        EngineRegistry.register(2) // most recent → designated
        val foreign = RingKitHostApiImpl(core, engineId = 1)
        val designated = RingKitHostApiImpl(core, engineId = 2)

        call { done -> foreign.ackEvents(seq, listOf(seq)) { done() } }
        assertEquals("foreign ack ignored", 1, repo.eventLog.takePending().size)

        call { done -> designated.ackEvents(seq, listOf(seq)) { done() } }
        assertEquals("designated ack consumes", 0, repo.eventLog.takePending().size)
    }

    // ── background handles persisted as 64-bit longs (§10.4) ────────────

    @Test
    fun background_handles_round_trip_as_longs() {
        val core = RingKitCore.get(RuntimeEnvironment.getApplication())
        val api = RingKitHostApiImpl(core, engineId = 7)

        // A handle that overflows Int32 — the incumbent's cast bug regression.
        val dispatcher = 0x1_2345_6789L
        val handler = 0x7FFF_FFFF_FFL
        call { done -> api.registerBackgroundEventHandler(dispatcher, handler) { done() } }

        assertEquals(dispatcher to handler, core.store.readBackgroundHandles())
    }

    // ── host API end-to-end over the writer (§8.9) ───────────────────────

    @Test
    fun show_then_take_pending_then_ack_via_host_api() {
        val core = RingKitCore.get(RuntimeEnvironment.getApplication())
        EngineRegistry.register(5)
        val api = RingKitHostApiImpl(core, engineId = 5)

        var showResult: ShowCallResult? = null
        call { done ->
            api.showIncomingCall(pigeonIncoming("host-1")) { r -> showResult = r.getOrThrow(); done() }
        }
        assertEquals(ShowCallResultCode.SHOWN, showResult!!.code)

        var cmd: CallCommandResult? = null
        call { done -> api.declineCall("host-1") { r -> cmd = r.getOrThrow(); done() } }
        assertEquals(CallCommandResult.OK, cmd)

        var pending: List<CallEventRecord> = emptyList()
        call { done -> api.takePendingEvents { r -> pending = r.getOrThrow(); done() } }
        assertEquals(1, pending.size)
        assertEquals(CallEventType.DECLINED, pending.single().type)
        assertEquals(DeliveryPath.REPLAYED, pending.single().deliveryPath)
        assertTrue("persisted flag set", pending.single().persisted)

        call { done -> api.ackEvents(pending.single().sequence, listOf(pending.single().sequence)) { done() } }
        call { done -> api.takePendingEvents { r -> pending = r.getOrThrow(); done() } }
        assertTrue(pending.isEmpty())
    }

    @Test
    fun invalid_show_maps_to_failed_invalid_params() {
        val core = RingKitCore.get(RuntimeEnvironment.getApplication())
        EngineRegistry.register(6)
        val api = RingKitHostApiImpl(core, engineId = 6)

        var result: ShowCallResult? = null
        call { done -> api.showIncomingCall(pigeonIncoming("   ")) { r -> result = r.getOrThrow(); done() } }
        assertEquals(ShowCallResultCode.FAILED, result!!.code)
        assertEquals(FailReason.INVALID_PARAMS, result!!.failReason)
        assertFalse("nothing recorded", awaitWriter(core) { core.repository.activeCalls().isNotEmpty() })
    }

    // ── mapper spot checks ───────────────────────────────────────────────

    @Test
    fun event_record_mapping_carries_reasons_and_path() {
        val dir = TestSupport.newTempDir()
        val time = FakeTime()
        val repo = CallRepository.open(CoreStore.openAt(dir), time)
        repo.dispatch(TransitionInput.Show(TestSupport.incomingCall(id = "map-1")))
        val result = repo.dispatch(
            TransitionInput.Cancel("map-1", com.wibechat.ringkit.core.CoreCancelReason.ANSWERED_ELSEWHERE),
        )
        assertEquals(TransitionOutcome.OK, result.outcome)

        val record = PigeonMapper.eventRecord(result.persistedEvents.single(), CoreDeliveryPath.LIVE)
        assertEquals(CallEventType.CANCELLED, record.type)
        assertEquals(CancelReason.ANSWERED_ELSEWHERE, record.cancelReason)
        assertEquals(DeliveryPath.LIVE, record.deliveryPath)
        assertEquals("map-1", record.call.id)
        assertNotNull(record.sequence)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun pigeonIncoming(id: String): IncomingCall =
        IncomingCall(
            id = id,
            callerName = "Alice",
            type = CallType.AUDIO,
            extra = emptyMap(),
            android =
                AndroidCallOptions(
                    vibrate = true,
                    lockScreenVisibility = NotificationVisibility.PUBLIC,
                    lockScreenBehavior = LockScreenBehavior.FULL_SCREEN,
                ),
        )

    private fun call(block: (done: () -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        block { latch.countDown() }
        assertTrue("host call completed", latch.await(5, TimeUnit.SECONDS))
    }

    private fun <T> awaitWriter(core: RingKitCore.Instance, block: () -> T): T =
        core.writer.await(block = block)
}
