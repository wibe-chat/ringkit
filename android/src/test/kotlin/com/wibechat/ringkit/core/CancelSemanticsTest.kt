package com.wibechat.ringkit.core

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * §5.1/§7.4 cancel + far-end-hangup semantics at the orchestrator layer, with
 * UI effects asserted (the table suite pins the state machine; these pin the
 * user-visible outcomes): R34 tombstone re-ring block, R41 pre-tombstones,
 * accept-wins-over-cancel, endCall(remote) live hangup, reason-copy mapping,
 * and the §7.4 `RingKitNative.handleCancel` custom-stack entry.
 */
@RunWith(RobolectricTestRunner::class)
class CancelSemanticsTest {

    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance
    private lateinit var orchestrator: IncomingCallOrchestrator
    private val fgsStarts = mutableListOf<String>()

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RingKitCore.resetForTests()
        core = RingKitCore.get(context)
        orchestrator =
            IncomingCallOrchestrator(context, core) { _, intent ->
                fgsStarts.add(RingKitIntents.callIdOf(intent) ?: "?")
            }
    }

    @After
    fun tearDown() {
        RingKitCore.resetForTests()
    }

    private fun show(id: String) = orchestrator.showOnWriter(TestSupport.incomingCall(id = id))

    private fun titles(): List<String?> =
        notificationManager.activeNotifications.map { it.notification.extras.getString(Notification.EXTRA_TITLE) }

    // ── R34: post-terminal re-delivery never re-rings ────────────────────

    @Test
    fun r34_post_terminal_redelivery_never_re_rings() {
        show("dup")
        orchestrator.declineOnWriter("dup")
        fgsStarts.clear()

        val redelivery = show("dup")

        assertEquals(TransitionOutcome.ALREADY_TERMINAL, redelivery.result.outcome)
        assertTrue("no ring effect", redelivery.result.effects.isEmpty())
        assertTrue("no FGS start on re-delivery (R34)", fgsStarts.isEmpty())
        assertNull("no live record resurrected", core.repository.callFor("dup"))
    }

    // ── R41: out-of-order cancel → pre-tombstone → show never rings ──────

    @Test
    fun r41_out_of_order_cancel_pre_tombstone_never_rings() {
        val cancel = orchestrator.cancelOnWriter("ooo", CoreCancelReason.ANSWERED_ELSEWHERE)
        assertEquals(TransitionOutcome.NOT_FOUND, cancel.outcome)
        assertTrue("pre-tombstone written (R41)", cancel.preTombstoneWritten)
        assertNotNull(core.repository.preTombstoneFor("ooo"))

        val show = show("ooo")

        assertEquals(TransitionOutcome.ALREADY_TERMINAL, show.result.outcome)
        assertTrue("never rings", fgsStarts.isEmpty())
        assertTrue("cancelled-before-arrival is fully invisible: no surfaces", titles().isEmpty())
        assertNull(core.repository.callFor("ooo"))
    }

    // ── cancel vs local accept: accept wins, zero side effects ───────────

    @Test
    fun cancel_after_accept_is_ignored_and_connecting_surface_survives() {
        show("race")
        orchestrator.acceptProgrammaticOnWriter("race")
        assertEquals(listOf("Alice"), titles()) // Connecting… surface up (title = caller)

        val cancel = orchestrator.cancelOnWriter("race", CoreCancelReason.CALLER_CANCELLED)

        assertEquals("ignored + traced (§5.1 — local accept wins)", TransitionOutcome.WRONG_STATE, cancel.outcome)
        assertTrue("no event emitted", cancel.persistedEvents.isEmpty())
        assertEquals("Connecting… surface untouched (zero side effects)", listOf("Alice"), titles())
        assertEquals(CoreCallState.ACCEPTED, core.repository.callFor("race")!!.state)
        assertEquals("watchdog still armed", 1, core.watchdogs.activeCount())
    }

    // ── endCall(remote): the far-end hangup of a LIVE call ───────────────

    @Test
    fun end_remote_on_accepted_call_ends_with_ENDED_remote_and_tears_down() {
        show("live")
        orchestrator.acceptProgrammaticOnWriter("live")

        val end = orchestrator.endOnWriter("live", CoreEndReason.REMOTE)

        assertEquals(TransitionOutcome.OK, end.outcome)
        val event = end.persistedEvents.single()
        assertEquals(CoreEventType.ENDED, event.type)
        assertEquals(CoreEndReason.REMOTE, event.endReason)
        assertNull(core.repository.callFor("live"))
        assertTrue("in-call surface torn down, no missed-call for a hangup", titles().isEmpty())
        assertEquals("watchdog cancelled", 0, core.watchdogs.activeCount())
    }

    @Test
    fun end_remote_on_RINGING_maps_to_cancelled_callerCancelled_with_missed_call() {
        show("ringer")
        val end = orchestrator.endOnWriter("ringer", CoreEndReason.REMOTE)

        assertEquals(TransitionOutcome.OK, end.outcome)
        val event = end.persistedEvents.single()
        assertEquals("§5.1 documented default mapping", CoreEventType.CANCELLED, event.type)
        assertEquals(CoreCancelReason.CALLER_CANCELLED, event.cancelReason)
        assertEquals("missed-call posted", listOf("Missed call"), titles())
    }

    // ── reason-copy mapping on the missed-call surface (§8.10) ───────────

    @Test
    fun cancel_reasons_map_to_missed_call_copy() {
        show("ans")
        orchestrator.cancelOnWriter("ans", CoreCancelReason.ANSWERED_ELSEWHERE)
        show("dec")
        orchestrator.cancelOnWriter("dec", CoreCancelReason.DECLINED_ELSEWHERE)

        assertEquals(
            setOf("Answered on another device", "Declined on another device"),
            titles().toSet(),
        )
    }

    // ── §7.4 RingKitNative.handleCancel (custom native stacks) ───────────

    @Test
    fun handleCancel_unknown_id_writes_pre_tombstone_with_mapped_reason() {
        RingKitNative.handleCancel(context, "native-ooo", reason = "answeredElsewhere")
        core.writer.await { }

        val pre = core.repository.preTombstoneFor("native-ooo")
        assertNotNull("pre-tombstone written (R41)", pre)
        assertEquals(CoreCancelReason.ANSWERED_ELSEWHERE, pre!!.reason)
    }

    @Test
    fun handleCancel_live_call_cancels_with_reason_copy() {
        show("native-live")
        RingKitNative.handleCancel(context, "native-live", reason = "declinedElsewhere")
        core.writer.await { }

        assertNull(core.repository.callFor("native-live"))
        assertEquals(listOf("Declined on another device"), titles())
        val logged = core.repository.eventLog.all().last()
        assertEquals(CoreEventType.CANCELLED, logged.type)
        assertEquals(CoreCancelReason.DECLINED_ELSEWHERE, logged.cancelReason)
    }

    @Test
    fun handleCancel_invalid_reason_defaults_to_callerCancelled() {
        RingKitNative.handleCancel(context, "native-bad", reason = "hungUpAngrily")
        core.writer.await { }
        assertEquals(CoreCancelReason.CALLER_CANCELLED, core.repository.preTombstoneFor("native-bad")!!.reason)
    }
}
