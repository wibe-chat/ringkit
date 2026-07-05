package com.wibechat.ringkit.core

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.services.RingingService
import com.wibechat.ringkit.ui.CallNotificationFactory
import com.wibechat.ringkit.ui.IncomingCallActivity
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

/**
 * §5.2 multi-call: eviction at admission, waiting-call surfaces (FSI always —
 * §8.2 validity, R33), the short-cue/etiquette split, service promotion and
 * re-pointing, the waiting-silence alarm backstop (§4.3), FSI-activity
 * owner-binding + promotion re-bind (§8.5), and the §10.2 rung-1 live sink for
 * native-origin events.
 */
@RunWith(RobolectricTestRunner::class)
class MultiCallTest {

    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance
    private lateinit var orchestrator: IncomingCallOrchestrator

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RingKitCore.resetForTests()
        core = RingKitCore.get(context)
        orchestrator = IncomingCallOrchestrator(context, core) { _, _ -> /* FGS start ok, not driven */ }
    }

    @After
    fun tearDown() {
        RingKitCore.resetForTests()
    }

    private fun show(id: String, lockScreenBehavior: CoreLockScreenBehavior = CoreLockScreenBehavior.FULL_SCREEN) =
        orchestrator.showOnWriter(TestSupport.incomingCall(id = id, lockScreenBehavior = lockScreenBehavior))

    private fun notifIdOf(callId: String): Int = core.repository.notificationIdFor(callId)!!

    private fun activeIds(): List<Int> = notificationManager.activeNotifications.map { it.id }

    // ── waiting-call surfaces (§5.2 + §8.2 validity invariant) ───────────

    @Test
    fun r33_waiting_call_notification_always_carries_fsi() {
        show("owner")
        show("waiting")

        // The owner's surface belongs to the FGS (not driven here); the waiting
        // call was posted directly by the orchestrator via notify().
        val posted = notificationManager.activeNotifications.single { it.id == notifIdOf("waiting") }
        assertNotNull("waiting call carries the FSI (§8.2, R33)", posted.notification.fullScreenIntent)
    }

    @Test
    fun r33_waiting_call_fsi_attached_even_in_notification_only_mode() {
        show("owner")
        show("waiting", lockScreenBehavior = CoreLockScreenBehavior.NOTIFICATION_ONLY)

        val posted = notificationManager.activeNotifications.single { it.id == notifIdOf("waiting") }
        assertNotNull(
            "notificationOnly omits the FSI ONLY on the FGS-posted owner — never a waiting call (§8.2)",
            posted.notification.fullScreenIntent,
        )
    }

    @Test
    fun second_call_while_in_call_keeps_fgs_surface_but_rings_as_waiting_silence() {
        show("first")
        orchestrator.acceptProgrammaticOnWriter("first")
        val outcome = show("second")

        assertEquals(TransitionOutcome.SHOWN, outcome.result.outcome)
        // "second" is the ONLY RINGING call ⇒ its surface belongs to the FGS
        // (no direct waiting post) — but §5.2 in-call demotion makes it
        // waiting-SILENCE: the short attention cue fires instead of the loop.
        assertTrue(
            "owner surface belongs to the FGS — no direct waiting post",
            notificationManager.activeNotifications.none { it.id == notifIdOf("second") },
        )
        assertTrue(core.repository.callFor("second")!!.surfacePosted)
        val vibrator =
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager)
                .defaultVibrator
        org.junit.Assert.assertArrayEquals(
            "in-call arrival got the §5.2 attention cue",
            longArrayOf(0, 150, 120, 150),
            shadowOf(vibrator).pattern,
        )
    }

    // ── eviction (§5.2, table-mirrored at the orchestrator layer) ────────

    @Test
    fun eviction_tears_down_victim_surface_and_posts_missed_call() {
        show("c1")
        show("c2")
        show("c3")
        assertTrue("c2 waiting surface up", activeIds().contains(notifIdOf("c2")))

        val outcome = show("c4") // cap 3 → evicts c2, the oldest waiting

        assertEquals(TransitionOutcome.SHOWN, outcome.result.outcome)
        assertEquals(listOf("c2"), outcome.result.evictions.map { it.callId })
        assertNull("victim gone from activeCalls()", core.repository.callFor("c2"))
        assertNotNull("victim tombstoned", core.repository.tombstoneFor("c2"))
        assertFalse("victim's incoming surface torn down", activeIds().contains(notifIdOf("c2")))
        val missed =
            notificationManager.activeNotifications.single {
                it.id == CallNotificationFactory.missedIdFor(notifIdOf("c2"))
            }
        assertEquals(
            "full TIMED_OUT semantics include the missed-call surface",
            "Missed call",
            missed.notification.extras.getString(Notification.EXTRA_TITLE),
        )
        // Owner untouched; younger waiting call and the arrival both ring on.
        assertEquals(CoreCallState.RINGING, core.repository.callFor("c1")!!.state)
        assertEquals(CoreCallState.RINGING, core.repository.callFor("c3")!!.state)
        assertEquals(CoreCallState.RINGING, core.repository.callFor("c4")!!.state)
    }

    @Test
    fun cap_one_second_arrival_expires_with_missed_call_owner_untouched() {
        core.repository.updateConfig(core.repository.currentConfig().copy(maxConcurrentRinging = 1))
        show("only")
        val outcome = show("rejected")

        assertEquals(
            "cap-1 arrival is dead on arrival: expired, never rings (§5.1/§5.2)",
            TransitionOutcome.EXPIRED, outcome.result.outcome,
        )
        assertNull(core.repository.callFor("rejected"))
        assertNotNull(core.repository.tombstoneFor("rejected"))
        val missed =
            notificationManager.activeNotifications.single {
                it.id == CallNotificationFactory.missedIdFor(notifIdOf("rejected"))
            }
        assertEquals("Missed call", missed.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("owner never evicted", CoreCallState.RINGING, core.repository.callFor("only")!!.state)
    }

    // ── §10.2 rung 1: live sink is source-agnostic ───────────────────────

    @Test
    fun live_sink_gets_native_origin_events_and_evictions() {
        val emitted = mutableListOf<CoreEvent>()
        core.liveSink = { events, _ -> emitted.addAll(events) }

        show("c1")
        assertEquals(
            "live CallRinging emitted through the sink",
            listOf(CoreEventType.RINGING), emitted.map { it.type },
        )
        emitted.clear()

        show("c2")
        show("c3")
        show("c4") // evicts c2
        assertEquals(
            "eviction TIMED_OUT precedes the arrival's RINGING in the same batch",
            listOf(CoreEventType.RINGING, CoreEventType.RINGING, CoreEventType.TIMED_OUT, CoreEventType.RINGING),
            emitted.map { it.type },
        )
        assertEquals("c2", emitted.first { it.type == CoreEventType.TIMED_OUT }.callId)
        emitted.clear()

        // Native-origin path: a receiver decline (no Pigeon involved) must
        // reach a live listener immediately (§10.2 rung 1, source-agnostic).
        val receiver = CallActionReceiver()
        receiver.onReceive(
            context,
            RingKitIntents.withCall(Intent(context, CallActionReceiver::class.java), RingKitIntents.ACTION_DECLINE, "c1"),
        )
        core.writer.await { }
        assertTrue(
            "receiver decline emitted live",
            emitted.any { it.type == CoreEventType.DECLINED && it.callId == "c1" },
        )
    }

    // ── service: promotion, re-pointing, waiting-silence backstop ────────

    @Test
    fun service_promotes_oldest_surviving_waiting_call_when_owner_terminates() {
        show("c1")
        show("c2")
        show("c3")

        val controller = Robolectric.buildService(RingingService::class.java)
        val service = controller.create().get()
        controller.withIntent(
            RingKitIntents.withCall(Intent(context, RingingService::class.java), RingKitIntents.ACTION_RING, "c1"),
        ).startCommand(0, 1)
        core.writer.await { }
        val shadow = shadowOf(service)
        assertEquals("foreground = owner c1", notifIdOf("c1"), shadow.lastForegroundNotificationId)

        orchestrator.declineOnWriter("c1")
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("service lives while RINGING calls remain (§5.2)", shadow.isStoppedBySelf)
        assertEquals(
            "foreground re-points to the promoted oldest-surviving waiting call",
            notifIdOf("c2"), shadow.lastForegroundNotificationId,
        )

        orchestrator.declineOnWriter("c2")
        orchestrator.declineOnWriter("c3")
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("no RINGING left ⇒ service stops", shadow.isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun waiting_calls_arm_the_doze_safe_alarm_backstop() {
        show("c1")
        show("c2")

        val controller = Robolectric.buildService(RingingService::class.java)
        controller.create()
        controller.withIntent(
            RingKitIntents.withCall(Intent(context, RingingService::class.java), RingKitIntents.ACTION_RING, "c2"),
        ).startCommand(0, 1)
        core.writer.await { }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val scheduled = shadowOf(alarmManager).scheduledAlarms
        val c2Deadline = core.repository.callFor("c2")!!.ringDeadlineElapsedMs!!
        assertTrue(
            "waiting-silence call carries an AlarmManager backstop at its ring deadline (§4.3)",
            scheduled.any { it.triggerAtMs == c2Deadline },
        )
        controller.destroy()
    }

    // ── FSI activity: owner binding + promotion re-bind (§8.5) ───────────

    @Test
    fun activity_binds_to_ringer_owner_even_when_launched_for_a_waiting_call() {
        show("owner")
        show("waiting")

        val controller =
            Robolectric.buildActivity(
                IncomingCallActivity::class.java,
                RingKitIntents.withCall(
                    Intent(context, IncomingCallActivity::class.java),
                    RingKitIntents.ACTION_SHOW_FULL_SCREEN,
                    "waiting",
                ),
            )
        val activity = controller.setup().get()

        assertFalse(activity.isFinishing)
        val name = activity.findViewByText("Alice")
        assertNotNull("renders the OWNER, not the waiting call", name)
        val banner = activity.findViewByText("1 more call waiting")
        assertNotNull("waiting call renders as the banner", banner)
        controller.destroy()
    }

    @Test
    fun activity_rebinds_to_promoted_owner_then_finishes_when_none_remain() {
        show("first")
        orchestrator.updateInfoOnWriter("first", CoreCallInfoUpdate(callerName = "First Caller"))
        show("second")
        orchestrator.updateInfoOnWriter("second", CoreCallInfoUpdate(callerName = "Second Caller"))

        val controller =
            Robolectric.buildActivity(
                IncomingCallActivity::class.java,
                RingKitIntents.withCall(
                    Intent(context, IncomingCallActivity::class.java),
                    RingKitIntents.ACTION_SHOW_FULL_SCREEN,
                    "first",
                ),
            )
        val activity = controller.setup().get()
        assertNotNull(activity.findViewByText("First Caller"))

        orchestrator.declineOnWriter("first")
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("promotion re-binds instead of finishing (§8.5/§5.2)", activity.isFinishing)
        assertNotNull("now renders the promoted owner", activity.findViewByText("Second Caller"))

        orchestrator.declineOnWriter("second")
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("no RINGING left ⇒ finishes ≤250 ms", activity.isFinishing)
        controller.destroy()
    }
}

/** Find a rendered TextView by its exact text (programmatic Views — no ids). */
private fun android.app.Activity.findViewByText(text: String): android.widget.TextView? {
    val root = findViewById<android.view.ViewGroup>(android.R.id.content)
    val queue = ArrayDeque<android.view.View>().apply { add(root) }
    while (queue.isNotEmpty()) {
        val view = queue.removeFirst()
        if (view is android.widget.TextView &&
            view.text?.toString() == text &&
            view.visibility == android.view.View.VISIBLE
        ) {
            return view
        }
        if (view is android.view.ViewGroup) {
            repeat(view.childCount) { queue.add(view.getChildAt(it)) }
        }
    }
    return null
}
