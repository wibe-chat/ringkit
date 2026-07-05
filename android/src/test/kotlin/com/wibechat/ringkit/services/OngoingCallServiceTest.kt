package com.wibechat.ringkit.services

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreEndReason
import com.wibechat.ringkit.core.CoreEventType
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingKitIntents
import com.wibechat.ringkit.core.TestSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * §8.1 OngoingCallService: R40 foreground discipline (unconditional-first
 * startForeground, null-intent rebuild, START_NOT_STICKY), CallStyle
 * forOngoingCall surfaces per state (Connecting… / chronometer / Calling…),
 * onTaskRemoved → ENDED(failed), bus-driven stop with REMOVE, and the
 * manageOngoingCall:false notify() fallback (v1.3.4).
 */
@RunWith(RobolectricTestRunner::class)
class OngoingCallServiceTest {

    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance
    private lateinit var orchestrator: IncomingCallOrchestrator
    private val startedServiceIntents = mutableListOf<Intent>()
    private var controller: ServiceController<OngoingCallService>? = null

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RingKitCore.resetForTests()
        core = RingKitCore.get(context)
        orchestrator =
            IncomingCallOrchestrator(context, core) { _, intent -> startedServiceIntents.add(intent) }
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.destroy() } }
        controller = null
        RingKitCore.resetForTests()
    }

    private fun acceptedCall(id: String = "c-1") {
        orchestrator.showOnWriter(TestSupport.incomingCall(id = id))
        orchestrator.acceptProgrammaticOnWriter(id)
    }

    private fun driveService(callId: String? = "c-1"): OngoingCallService {
        val c = Robolectric.buildService(OngoingCallService::class.java)
        controller = c
        val service = c.create().get()
        val intent =
            callId?.let {
                RingKitIntents.withCall(Intent(context, OngoingCallService::class.java), RingKitIntents.ACTION_ONGOING, it)
            }
        if (intent != null) c.withIntent(intent).startCommand(0, 1) else c.startCommand(0, 1)
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return service
    }

    // ── R40 foreground discipline ────────────────────────────────────────

    @Test
    fun r40_null_intent_restart_starts_foreground_then_stops_when_no_call() {
        val service = driveService(callId = null)
        val shadow = shadowOf(service)
        assertEquals(
            "startForeground is the unconditional first statement (placeholder id = base-2)",
            CoreConfig().notificationIdBase - 2,
            shadow.lastForegroundNotificationId,
        )
        assertTrue("no in-call call ⇒ service stops itself", shadow.isStoppedBySelf)
        assertTrue("surface REMOVEd", shadow.notificationShouldRemoved)
    }

    @Test
    fun r40_null_intent_with_live_call_rebuilds_from_repository() {
        acceptedCall()
        val service = driveService(callId = null) // restart race: null intent
        val shadow = shadowOf(service)
        assertFalse("call live ⇒ service keeps running", shadow.isStoppedBySelf)
        assertEquals(core.repository.notificationIdFor("c-1")!!, shadow.lastForegroundNotificationId)
        assertEquals(
            "Connecting…",
            shadow.lastForegroundNotification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    // ── surfaces per state (§8.2) ────────────────────────────────────────

    @Test
    fun accepted_call_hosts_callstyle_connecting_surface() {
        acceptedCall()
        assertTrue(
            "accept under manageOngoingCall:true starts the ongoing FGS",
            startedServiceIntents.any { it.action == RingKitIntents.ACTION_ONGOING },
        )
        val service = driveService()
        val posted = shadowOf(service).lastForegroundNotification
        assertEquals("Connecting…", posted.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(
            "CallStyle forOngoingCall (validity by FGS attachment, v1.3.4)",
            (posted.extras.getString(Notification.EXTRA_TEMPLATE) ?: "").contains("CallStyle"),
        )
        assertNull("ongoing surface carries NO FSI", posted.fullScreenIntent)
    }

    @Test
    fun set_connected_repoints_to_chronometer_with_connect_base() {
        acceptedCall()
        val service = driveService()

        orchestrator.setConnectedOnWriter("c-1")
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val posted = shadowOf(service).lastForegroundNotification
        assertTrue(
            "chronometer on at ACTIVE (§8.2)",
            posted.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
        assertEquals(
            "base = connect time",
            core.repository.callFor("c-1")!!.connectedAtWallMs!!,
            posted.`when`,
        )
    }

    @Test
    fun outgoing_call_hosts_calling_surface() {
        orchestrator.startOutgoingOnWriter(
            com.wibechat.ringkit.core.CoreOutgoingCall(id = "out-1", calleeName = "Bob"),
        )
        assertTrue(startedServiceIntents.any { it.action == RingKitIntents.ACTION_ONGOING })
        val service = driveService(callId = "out-1")
        val posted = shadowOf(service).lastForegroundNotification
        assertEquals("Calling…", posted.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Bob", posted.extras.getString(Notification.EXTRA_TITLE))
    }

    // ── lifetime (§5.1/§8.6) ─────────────────────────────────────────────

    @Test
    fun hang_up_stops_service_and_removes_surface() {
        acceptedCall()
        val service = driveService()

        orchestrator.endOnWriter("c-1", CoreEndReason.LOCAL)
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val shadow = shadowOf(service)
        assertTrue("terminal ⇒ service stops", shadow.isStoppedBySelf)
        assertTrue(
            "REMOVE, not detach — cancel() is a no-op on FGS-attached notifications",
            shadow.notificationShouldRemoved,
        )
    }

    @Test
    fun task_removed_ends_every_in_call_call_with_failed_and_stops() {
        acceptedCall()
        val service = driveService()

        service.onTaskRemoved(null)
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull("call ended (an orphan in-call notification is a lie)", core.repository.callFor("c-1"))
        val terminal = core.repository.eventLog.all().last()
        assertEquals(CoreEventType.ENDED, terminal.type)
        assertEquals(CoreEndReason.FAILED, terminal.endReason)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    // ── manageOngoingCall:false (v1.3.4 fallback) ────────────────────────

    @Test
    fun opt_out_posts_non_callstyle_fallback_and_never_starts_the_service() {
        core.repository.updateConfig(core.repository.currentConfig().copy(manageOngoingCall = false))
        acceptedCall("fb-1")

        assertTrue(
            "no ongoing FGS start under opt-out",
            startedServiceIntents.none { it.action == RingKitIntents.ACTION_ONGOING },
        )
        val posted =
            notificationManager.activeNotifications.single {
                it.id == core.repository.notificationIdFor("fb-1")!!
            }.notification
        assertEquals("Connecting…", posted.extras.getString(Notification.EXTRA_TEXT))
        assertFalse(
            "no FGS ⇒ CallStyle validity unavailable ⇒ non-CallStyle build",
            (posted.extras.getString(Notification.EXTRA_TEMPLATE) ?: "").contains("CallStyle"),
        )

        orchestrator.setConnectedOnWriter("fb-1")
        val updated =
            notificationManager.activeNotifications.single {
                it.id == core.repository.notificationIdFor("fb-1")!!
            }.notification
        assertTrue("fallback chronometer at ACTIVE", updated.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    // ── sweep integration (§8.10, v1.3.4 grace) ──────────────────────────

    @Test
    fun sweep_spares_fresh_active_call_then_kills_orphan_after_grace_without_service() {
        acceptedCall("sw-1")
        orchestrator.setConnectedOnWriter("sw-1")
        // Service never actually ran (fake starter): within the 10 s connect
        // grace the ACTIVE call is spared…
        assertEquals(0, orchestrator.sweepNow().sweptCalls.size)
        assertEquals(CoreCallState.ACTIVE, core.repository.callFor("sw-1")!!.state)

        // …after the grace, no live service ⇒ orphaned ACTIVE ends failed (§5.1).
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(11))
        val report = orchestrator.sweepNow()
        assertEquals(1, report.sweptCalls.size)
        val event = report.sweptCalls.single().second.persistedEvents.single()
        assertEquals(CoreEventType.ENDED, event.type)
        assertEquals(CoreEndReason.FAILED, event.endReason)
    }
}
