package com.wibechat.ringkit.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.core.CoreCancelReason
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingKitIntents
import com.wibechat.ringkit.core.TestSupport
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * §8.5 IncomingCallActivity: the full-screen call UI observes the in-process
 * CallBus and finishes when its bound call terminates (remote cancel / decline
 * / timeout) — never via component-addressed broadcasts (#215 class).
 *
 * The named regression here is the killed-state cold-start cancel: the bus
 * listener runs on the writer thread, so a remote cancel can publish before
 * onCreate's initial bind has committed `boundCallId` on main. The old
 * `boundCallId == null` short-circuit dropped that terminal signal and left the
 * FSI ringing forever (device-reported, killed-state Mode 1).
 */
@RunWith(RobolectricTestRunner::class)
class IncomingCallActivityTest {

    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance
    private lateinit var orchestrator: IncomingCallOrchestrator
    private var controller: ActivityController<IncomingCallActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RingKitCore.resetForTests()
        core = RingKitCore.get(context)
        // Fake service starter: the ringing FGS never actually spins up under
        // Robolectric; we only assert the Activity's bus-driven teardown.
        orchestrator = IncomingCallOrchestrator(context, core) { _, _ -> }
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.destroy() } }
        controller = null
        RingKitCore.resetForTests()
    }

    private fun drain() {
        core.writer.await { }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun launch(callId: String): IncomingCallActivity {
        val intent =
            RingKitIntents.withCall(
                Intent(context, IncomingCallActivity::class.java),
                RingKitIntents.ACTION_SHOW_FULL_SCREEN,
                callId,
            )
        val c = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller = c
        val activity = c.create().get()
        drain()
        return activity
    }

    @Test
    @Config(sdk = [26, 27, 28, 29, 30, 35])
    fun r20_incoming_screen_renders_caller_and_actions_on_supported_apis() {
        orchestrator.showOnWriter(TestSupport.incomingCall(id = "c-1", name = "Alice"))
        val activity = launch("c-1")
        assertFalse("the live incoming screen stays open", activity.isFinishing)

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val matches = ArrayList<View>()
        content.findViewsWithText(matches, "Alice", View.FIND_VIEWS_WITH_TEXT)
        assertEquals("caller identity is rendered", 1, matches.size)
        for (label in listOf("Answer", "Decline")) {
            matches.clear()
            content.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT)
            assertEquals("$label action is rendered", 1, matches.size)
            assertTrue("$label action is enabled", matches.single().isEnabled)
            assertTrue("$label action is clickable", matches.single().isClickable)
        }
    }

    @Test
    @Config(sdk = [26])
    fun r20_api26_incoming_screen_can_show_over_keyguard_and_wake_screen() {
        orchestrator.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        val activity = launch("c-1")
        @Suppress("DEPRECATION")
        val requiredFlags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        assertEquals(requiredFlags, activity.window.attributes.flags and requiredFlags)
    }

    @Test
    @Config(sdk = [26, 27, 28, 29, 30, 35])
    fun r20_system_bar_insets_pad_the_incoming_screen_without_accumulating() {
        orchestrator.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        val activity = launch("c-1")
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(5, 24, 7, 48))
            .build()

        repeat(2) {
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            assertEquals(5, root.paddingLeft)
            assertEquals(24, root.paddingTop)
            assertEquals(7, root.paddingRight)
            assertEquals(48, root.paddingBottom)
        }
        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
                .build(),
        )
        assertEquals(0, root.paddingLeft)
        assertEquals(0, root.paddingTop)
        assertEquals(0, root.paddingRight)
        assertEquals(0, root.paddingBottom)
    }

    @Test
    @Config(sdk = [26, 29, 35])
    fun remote_cancel_finishes_the_fsi() {
        orchestrator.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        val activity = launch("c-1")
        assertFalse("binds to the live RINGING owner", activity.isFinishing)

        orchestrator.cancelOnWriter("c-1", CoreCancelReason.CALLER_CANCELLED)
        drain()

        assertTrue("remote cancel tears down the FSI (§8.5)", activity.isFinishing)
    }

    @Test
    fun no_live_owner_at_bind_finishes_quietly() {
        // Never shown (or already terminal): the FSI must not linger on a
        // process-death restore with a stale intent (§8.5).
        val activity = launch("ghost")
        assertTrue(activity.isFinishing)
    }

    @Test
    fun r45_writer_busy_timeout_keeps_the_fsi_alive() {
        // R45: with telecom on, addCall can hold the writer up to its own 2 s
        // budget — exactly bind's owner-lookup budget. A busy-writer TIMEOUT must
        // NOT be read as "no live owner" (pre-fix it collapsed to the finish-quietly
        // path and the FSI answer UI self-destructed mid-ring). Occupy the writer,
        // launch, and assert the activity fail-opens (stays alive) rather than
        // finishing on a phantom empty.
        orchestrator.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        drain()

        val hold = java.util.concurrent.CountDownLatch(1)
        core.writer.post { hold.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        try {
            val intent =
                RingKitIntents.withCall(
                    Intent(context, IncomingCallActivity::class.java),
                    RingKitIntents.ACTION_SHOW_FULL_SCREEN,
                    "c-1",
                )
            // onCreate → bind() → awaitCatching times out against the held writer.
            val activity = Robolectric.buildActivity(IncomingCallActivity::class.java, intent).also { controller = it }
                .create().get()
            assertFalse("writer-busy timeout must not tear down the ring UI (R45)", activity.isFinishing)
        } finally {
            hold.countDown()
        }
    }

    @Test
    fun cold_start_cancel_before_binding_is_not_dropped() {
        // Reproduce the killed-state race: the CallBus listener fires on the
        // writer thread and a cancel can arrive before onCreate's initial bind
        // has committed boundCallId on main. Simulate that exact window by
        // clearing boundCallId, then dispatch the cancel: the listener MUST
        // still finish the activity (pre-fix it short-circuited on the null
        // guard → stuck full-screen UI, still ringing).
        orchestrator.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        val activity = launch("c-1")
        assertFalse(activity.isFinishing)

        activity.setBoundCallIdForTest(null)
        orchestrator.cancelOnWriter("c-1", CoreCancelReason.CALLER_CANCELLED)
        drain()

        assertTrue("terminal signal honored even while unbound", activity.isFinishing)
    }
}
