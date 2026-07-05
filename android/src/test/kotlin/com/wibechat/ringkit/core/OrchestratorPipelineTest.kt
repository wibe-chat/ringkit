package com.wibechat.ringkit.core

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.services.RingingService
import com.wibechat.ringkit.ui.AcceptTrampolineActivity
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The M2 ringing pipeline end-to-end under Robolectric: §8.11 precondition
 * rows with matching ShowCallResult inputs (G3), §4.3 action flows against
 * real notifications, sweep effects (r32 class), receiver cold-process
 * construction (r1), RingingService foreground discipline (r16/r40), and the
 * hardened trampoline (§4.3, r36/r44 class).
 */
@RunWith(RobolectricTestRunner::class)
class OrchestratorPipelineTest {

    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance

    /** Orchestrator whose FGS start succeeds silently (service not driven). */
    private lateinit var orchestrator: IncomingCallOrchestrator

    private val startedServiceIntents = mutableListOf<Intent>()

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
        RingKitCore.resetForTests()
    }

    private fun call(id: String = "c-1", missedCallShow: Boolean = true) =
        TestSupport.incomingCall(id = id, missedCallShow = missedCallShow)

    private fun postedNotifications(): List<Notification> =
        shadowOf(notificationManager).allNotifications

    private fun activeIds(): List<Int> =
        notificationManager.activeNotifications.map { it.id }

    // ── §8.11 rows (G3: the reported result matches what the eye sees) ───

    @Test
    @org.robolectric.annotation.Config(sdk = [33])
    fun shown_path_starts_fgs_marks_surface_and_reports_shown() {
        // SDK 33: no FSI special access exists (<34 ⇒ always usable) — the
        // clean shown path with an empty degradation set.
        val outcome = orchestrator.showOnWriter(call())

        assertEquals(TransitionOutcome.SHOWN, outcome.result.outcome)
        assertNull(outcome.failReason)
        assertTrue(outcome.degradations.isEmpty())
        assertEquals("FGS start carries the ring", 1, startedServiceIntents.size)
        assertEquals(RingKitIntents.ACTION_RING, startedServiceIntents.single().action)
        assertTrue("surfacePosted marks the presence-check contract", core.repository.callFor("c-1")!!.surfacePosted)
    }

    @Test
    fun r_fsiDenied_on_34plus_is_reported_but_never_blocks_the_ring() {
        // Robolectric 35 answers canUseFullScreenIntent()=false (no shadow
        // setter) — exactly the §8.11 fsiDenied row: FSI stays attached, the
        // ring proceeds, the result says degraded({fsiDenied}).
        val outcome = orchestrator.showOnWriter(call())

        assertEquals(TransitionOutcome.SHOWN, outcome.result.outcome)
        assertNull(outcome.failReason)
        assertTrue(CoreDegradeReason.FSI_DENIED in outcome.degradations)
        assertEquals("ring still starts", 1, startedServiceIntents.size)
    }

    @Test
    fun r_notificationsBlocked_no_surface_no_fgs_persisted_with_deadline() {
        shadowOf(notificationManager).setNotificationsEnabled(false)
        val outcome = orchestrator.showOnWriter(call())

        assertEquals(CoreFailReason.NOTIFICATIONS_BLOCKED, outcome.failReason)
        assertEquals(CoreSuppressReason.NOTIFICATIONS_BLOCKED, outcome.suppressReason)
        assertTrue("invisible ringing is banned: NO FGS", startedServiceIntents.isEmpty())
        assertTrue("NO notification", activeIds().isEmpty())
        val record = core.repository.callFor("c-1")!!
        assertFalse("no surface ⇒ deadline-only sweep", record.surfacePosted)
        assertNotNull("sweep-enforced deadline still armed", record.ringDeadlineElapsedMs)
    }

    @Test
    fun r_channelBlocked_reports_failed_channelBlocked() {
        // First show creates the channels; then the user blocks the channel.
        orchestrator.showOnWriter(call(id = "warmup"))
        notificationManager.getNotificationChannel("ringkit_incoming_v1").importance =
            NotificationManager.IMPORTANCE_NONE

        val outcome = orchestrator.showOnWriter(call(id = "c-2"))
        assertEquals(CoreFailReason.CHANNEL_BLOCKED, outcome.failReason)
        assertEquals(CoreSuppressReason.CHANNEL_BLOCKED, outcome.suppressReason)
    }

    @Test
    fun r_channelDemoted_proceeds_and_reports_degraded() {
        orchestrator.showOnWriter(call(id = "warmup"))
        notificationManager.getNotificationChannel("ringkit_incoming_v1").importance =
            NotificationManager.IMPORTANCE_LOW

        val outcome = orchestrator.showOnWriter(call(id = "c-2"))
        assertEquals(TransitionOutcome.SHOWN, outcome.result.outcome)
        assertNull(outcome.failReason)
        assertTrue(CoreDegradeReason.CHANNEL_DEMOTED in outcome.degradations)
        assertTrue("FGS + our ringtone still ring", startedServiceIntents.size == 2)
    }

    @Test
    fun r_fgsDenied_posts_directly_with_fsi_on_audible_channel() {
        val denied =
            IncomingCallOrchestrator(context, core) { _, _ ->
                throw IllegalStateException("background FGS start disallowed")
            }
        val outcome = denied.showOnWriter(call())

        assertTrue(CoreDegradeReason.FGS_DENIED in outcome.degradations)
        assertNull("degraded, not failed", outcome.failReason)
        val posted = postedNotifications().single()
        assertNotNull(
            "FSI attached even in degraded direct post (validity, R18/R33)",
            posted.fullScreenIntent,
        )
        assertEquals(
            "one-shot ring via the audible fallback channel (§8.11)",
            "ringkit_incoming_audible_v1",
            posted.channelId,
        )
        assertTrue(core.repository.callFor("c-1")!!.surfacePosted)
    }

    @Test
    fun invalid_params_map_to_failed_invalidParams() {
        val outcome = orchestrator.showOnWriter(TestSupport.incomingCall(id = ""))
        assertEquals(CoreFailReason.INVALID_PARAMS, outcome.failReason)
    }

    @Test
    fun staleness_expired_posts_missed_call_and_never_rings() {
        val stale = TestSupport.incomingCall(id = "c-old", sentAtWallMs = core.time.wallMs() - 120_000L)
        val outcome = orchestrator.showOnWriter(stale)

        assertEquals(TransitionOutcome.EXPIRED, outcome.result.outcome)
        assertTrue("never rings", startedServiceIntents.isEmpty())
        val missed = postedNotifications().single()
        assertEquals("Missed call", missed.extras.getString(Notification.EXTRA_TITLE))
        assertNull(core.repository.callFor("c-old"))
    }

    // ── §4.3 action flows ────────────────────────────────────────────────

    @Test
    fun r44_accept_from_action_persists_launch_action_and_posts_connecting() {
        orchestrator.showOnWriter(call())
        val accept = orchestrator.acceptFromActionOnWriter("c-1")

        assertEquals(TransitionOutcome.OK, accept.result.outcome)
        assertNotNull("launch action persisted natively (r44)", accept.launch)
        assertEquals(CoreEventType.ACCEPTED, accept.launch!!.type)
        assertFalse(accept.launch!!.alreadyEnded)

        val connecting =
            notificationManager.activeNotifications.single().notification
        assertEquals("Connecting…", connecting.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(CoreCallState.ACCEPTED, core.repository.callFor("c-1")!!.state)
        assertEquals("watchdog armed", 1, core.watchdogs.activeCount())
    }

    @Test
    fun decline_tears_down_without_missed_call() {
        orchestrator.showOnWriter(call())
        // Simulate the posted surface so teardown is observable.
        core.repository.notificationIdFor("c-1")!!.let { id ->
            notificationManager.notify(id, Notification.Builder(context, "ringkit_incoming_v1").setSmallIcon(com.wibechat.ringkit.R.drawable.ringkit_ic_call).build())
        }

        val result = orchestrator.declineOnWriter("c-1")
        assertEquals(TransitionOutcome.OK, result.outcome)
        assertTrue("decline posts NO missed-call (§4.3)", activeIds().isEmpty())
        assertNull(core.repository.callFor("c-1"))
    }

    @Test
    fun timeout_posts_missed_call_with_default_copy_in_its_own_id_band() {
        orchestrator.showOnWriter(call())
        val incomingId = core.repository.notificationIdFor("c-1")!!
        val result = orchestrator.timeoutOnWriter("c-1")

        assertEquals(TransitionOutcome.OK, result.outcome)
        val posted = notificationManager.activeNotifications.single()
        assertEquals("Missed call", posted.notification.extras.getString(Notification.EXTRA_TITLE))
        // Device-found regression: the FGS's STOP_FOREGROUND_REMOVE destroys a
        // missed-call posted under the call's own id — the band must differ.
        assertEquals(
            com.wibechat.ringkit.ui.CallNotificationFactory.missedIdFor(incomingId),
            posted.id,
        )
    }

    @Test
    fun remote_cancel_answeredElsewhere_carries_reason_copy() {
        orchestrator.showOnWriter(call())
        orchestrator.cancelOnWriter("c-1", CoreCancelReason.ANSWERED_ELSEWHERE)

        val missed = notificationManager.activeNotifications.single().notification
        assertEquals("Answered on another device", missed.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun missed_tap_auto_cancels_then_emits_and_sets_launch_action() {
        orchestrator.showOnWriter(call())
        orchestrator.timeoutOnWriter("c-1")
        assertEquals(1, activeIds().size)

        val launch = orchestrator.missedTappedOnWriter("c-1")

        assertTrue("auto-cancel BEFORE forwarding (#496/#562)", activeIds().isEmpty())
        assertNotNull(launch)
        assertEquals(CoreEventType.MISSED_CALL_TAPPED, launch!!.type)
        assertEquals(launch.sequence, core.repository.peekLaunchAction()!!.sequence)
        val logged = core.repository.eventLog.takePending().last()
        assertEquals(CoreEventType.MISSED_CALL_TAPPED, logged.type)
    }

    @Test
    fun accept_on_tombstone_still_launches_with_alreadyEnded() {
        orchestrator.showOnWriter(call())
        orchestrator.cancelOnWriter("c-1", CoreCancelReason.CALLER_CANCELLED)
        val accept = orchestrator.acceptFromActionOnWriter("c-1")

        assertEquals(TransitionOutcome.ALREADY_TERMINAL, accept.result.outcome)
        assertNotNull("trampoline still launches the app (§4.3)", accept.launch)
        assertTrue(accept.launch!!.alreadyEnded)
        assertTrue("no CallAccepted event", accept.result.persistedEvents.isEmpty())
    }

    @Test
    fun watchdog_fire_ends_connecting_call_with_failed() {
        orchestrator.showOnWriter(call())
        orchestrator.acceptProgrammaticOnWriter("c-1")
        val result = orchestrator.watchdogFireOnWriter("c-1")

        assertEquals(TransitionOutcome.OK, result.outcome)
        assertEquals(CoreEndReason.FAILED, result.persistedEvents.single().endReason)
        assertNull(core.repository.callFor("c-1"))
        assertTrue("connecting surface torn down", activeIds().isEmpty())
    }

    @Test
    fun set_connected_cancels_watchdog() {
        orchestrator.showOnWriter(call())
        orchestrator.acceptProgrammaticOnWriter("c-1")
        assertEquals(1, core.watchdogs.activeCount())
        orchestrator.setConnectedOnWriter("c-1")
        assertEquals(0, core.watchdogs.activeCount())
        assertEquals(CoreCallState.ACTIVE, core.repository.callFor("c-1")!!.state)
    }

    // ── sweep effects (r32 class) ────────────────────────────────────────

    @Test
    fun r32_sweep_times_out_deadline_passed_call_with_missed_surface() {
        orchestrator.showOnWriter(call())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(46))

        val report = orchestrator.sweepNow()

        assertEquals(1, report.sweptCalls.size)
        assertNull("no ghost in activeCalls()", core.repository.callFor("c-1"))
        val missed = notificationManager.activeNotifications.single().notification
        assertEquals("Missed call", missed.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun sweep_kills_posted_call_whose_notification_vanished_after_grace() {
        orchestrator.showOnWriter(call())
        // Surface marked posted, but nothing is actually in the shade and the
        // 10 s in-flight grace has passed → presence check kills it (§8.10).
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))

        val report = orchestrator.sweepNow()
        assertEquals(1, report.sweptCalls.size)
        assertNull(core.repository.callFor("c-1"))
    }

    @Test
    fun sweep_grace_window_protects_the_async_fgs_post() {
        orchestrator.showOnWriter(call())
        // Within 10 s of show: presence check must NOT kill the call even
        // though the service hasn't posted yet.
        val report = orchestrator.sweepNow()
        assertTrue(report.sweptCalls.isEmpty())
        assertNotNull(core.repository.callFor("c-1"))
    }

    @Test
    fun rearm_watchdogs_after_restart_covers_persisted_accepted_calls() {
        orchestrator.showOnWriter(call())
        orchestrator.acceptProgrammaticOnWriter("c-1")
        core.watchdogs.cancel("c-1") // simulate process death dropping timers
        assertEquals(0, core.watchdogs.activeCount())

        orchestrator.sweepNow() // every entry point re-arms (§5.2)
        assertEquals(1, core.watchdogs.activeCount())
    }

    // ── receiver (r1: cold-process construction; r39 flags asserted in factory tests) ──

    @Test
    fun r1_receiver_decline_works_with_no_plugin_attached() {
        orchestrator.showOnWriter(call())

        val receiver = CallActionReceiver()
        val intent =
            RingKitIntents.withCall(
                Intent(context, CallActionReceiver::class.java),
                RingKitIntents.ACTION_DECLINE,
                "c-1",
            )
        receiver.onReceive(context, intent)
        // goAsync work runs on the writer — barrier on it.
        core.writer.await { }

        assertNull("DECLINED persisted from a cold receiver", core.repository.callFor("c-1"))
        assertEquals(
            CoreEventType.DECLINED,
            core.repository.eventLog.takePending().last().type,
        )
    }

    @Test
    fun receiver_stale_decline_after_sweep_is_a_zero_side_effect_loser() {
        orchestrator.showOnWriter(call())
        orchestrator.timeoutOnWriter("c-1") // missed-call notification up
        assertEquals(1, activeIds().size)

        val receiver = CallActionReceiver()
        receiver.onReceive(
            context,
            RingKitIntents.withCall(Intent(context, CallActionReceiver::class.java), RingKitIntents.ACTION_DECLINE, "c-1"),
        )
        core.writer.await { }

        assertEquals(
            "losing decline must NOT cancel the winner's missed-call notification (§5.1)",
            1, activeIds().size,
        )
    }

    // ── RingingService foreground discipline (r16/r40) ───────────────────

    @Test
    fun r16_r40_null_intent_restart_starts_foreground_then_stops_when_no_ringing() {
        val controller = Robolectric.buildService(RingingService::class.java)
        val service = controller.create().get()
        controller.startCommand(0, 1) // null intent

        val shadow = shadowOf(service)
        // startForeground ran first (placeholder id = base-1); the shadow's
        // notification is already cleared by the STOP_FOREGROUND_REMOVE that
        // follows when no RINGING call exists.
        assertEquals(
            "startForeground is the unconditional first statement",
            CoreConfig().notificationIdBase - 1,
            shadow.lastForegroundNotificationId,
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("no RINGING call ⇒ service stops itself", shadow.isStoppedBySelf)
    }

    @Test
    fun service_rings_owner_and_stops_when_the_call_terminates() {
        orchestrator.showOnWriter(call())

        val controller = Robolectric.buildService(RingingService::class.java)
        val service = controller.create().get()
        controller.withIntent(
            RingKitIntents.withCall(Intent(context, RingingService::class.java), RingKitIntents.ACTION_RING, "c-1"),
        ).startCommand(0, 1)
        core.writer.await { }

        val shadow = shadowOf(service)
        val owner = shadow.lastForegroundNotification
        assertNotNull(owner)
        assertNotNull("owner notification carries the FSI in fullScreen mode", owner.fullScreenIntent)

        // Terminal transition → bus → service re-evaluates → stops itself,
        // REMOVING its notification: cancel() is a no-op on an FGS-attached
        // notification, so the terminal path must remove here (device-found).
        orchestrator.declineOnWriter("c-1")
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(
            "terminal foreground call ⇒ REMOVE (orchestrator cancel was a no-op)",
            shadow.notificationShouldRemoved,
        )
        controller.destroy()
    }

    @Test
    fun accept_keeps_connecting_surface_alive_through_service_stop() {
        orchestrator.showOnWriter(call())
        val controller = Robolectric.buildService(RingingService::class.java)
        controller.create()
        controller.withIntent(
            RingKitIntents.withCall(Intent(context, RingingService::class.java), RingKitIntents.ACTION_RING, "c-1"),
        ).startCommand(0, 1)
        core.writer.await { }

        orchestrator.acceptFromActionOnWriter("c-1")
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val connecting = notificationManager.activeNotifications.single()
        assertEquals(
            "Connecting…",
            connecting.notification.extras.getString(Notification.EXTRA_TEXT),
        )
        controller.destroy()
    }

    // ── trampoline (§4.3; r36 class) ─────────────────────────────────────

    @Test
    fun trampoline_accept_persists_then_forwards_launch_intent_synchronously() {
        orchestrator.showOnWriter(call())
        // The example/consumer app always has a launch intent; Robolectric's
        // test package needs a launcher activity registered for one to exist.
        val launcher =
            android.content.ComponentName(context.packageName, "com.wibechat.ringkit.FakeLauncherActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            launcher,
            android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )

        val controller =
            Robolectric.buildActivity(
                AcceptTrampolineActivity::class.java,
                RingKitIntents.withCall(
                    Intent(context, AcceptTrampolineActivity::class.java),
                    RingKitIntents.ACTION_ACCEPT,
                    "c-1",
                ),
            )
        val activity = controller.setup().get()

        assertEquals(CoreCallState.ACCEPTED, core.writer.await { core.repository.callFor("c-1")?.state })
        val next = shadowOf(activity).nextStartedActivity
        assertNotNull("forwards the app launch intent in onCreate", next)
        assertEquals("com.wibechat.ringkit.FakeLauncherActivity", next.component?.className)
        assertTrue(activity.isFinishing)
        assertNotNull("launch action persisted for consumeLaunchAction", core.writer.await { core.repository.peekLaunchAction() })
    }

    @Test
    fun trampoline_null_intent_finishes_quietly() {
        val controller = Robolectric.buildActivity(AcceptTrampolineActivity::class.java)
        val activity = controller.setup().get()
        assertTrue("null-intent-hardened: no crash, just finish", activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
