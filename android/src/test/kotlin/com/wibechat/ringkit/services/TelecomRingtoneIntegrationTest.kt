package com.wibechat.ringkit.services

import android.content.Context
import android.app.AlarmManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.core.*
import com.wibechat.ringkit.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/** Real orchestrator → shared player → service/fallback ownership and lifecycle. */
@RunWith(RobolectricTestRunner::class)
class TelecomRingtoneIntegrationTest {
    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance
    private lateinit var orch: IncomingCallOrchestrator
    private lateinit var player: RingtonePlayer
    private val bridge = Bridge()
    private val engines = mutableListOf<Engine>()
    private var controller: ServiceController<RingingService>? = null

    private val manager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RingKitCore.resetForTests()
        core = RingKitCore.get(context)
        manager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        core.repository.updateConfig(core.repository.currentConfig().copy(telecomIntegration = true))
        val backend = object : RingPlaybackBackend {
            override fun openPrimary(source: Uri, attributes: AudioAttributes, onError: (Int, Int) -> Unit) =
                Engine().also { engines += it }
            override fun openRingtone(source: Uri, attributes: AudioAttributes): RingPlaybackEngine? = null
            override fun openBundled(attributes: AudioAttributes, onError: (Int, Int) -> Unit): RingPlaybackEngine =
                error("No fallback expected in this ownership test")
        }
        player = RingtonePlayer(context, core.ringLog, backend,
            object : RingPlaybackScheduler {
                override fun schedule(delayMs: Long, block: () -> Unit) = RingPlaybackCancellation { }
            },
            focusOwnerFor = { RingingService.focusOwnerFor(core, it) },
            dispatchPlayback = { core.writer.post(it) })
        RingingService::class.java.getDeclaredField("processPlayer").apply { isAccessible = true }.set(null, player)
        orch = IncomingCallOrchestrator(context, core, telecomProvider = { bridge }, serviceStarter = { _, _ -> })
    }

    @After
    fun tearDown() {
        controller?.destroy()
        core.writer.await { player.stop() }
        core.writer.await { }
        RingingService::class.java.getDeclaredField("processPlayer").apply { isAccessible = true }.set(null, null)
        RingKitCore.resetForTests()
    }

    private fun show(id: String, telecom: Boolean = true) {
        val call = TestSupport.incomingCall(id = id)
        core.writer.await { orch.showOnWriter(call.copy(options = call.options.copy(
            useTelecom = telecom, ringtone = "content://settings/system/ringtone",
        ))) }
    }

    private fun startService(id: String) {
        controller = Robolectric.buildService(RingingService::class.java).also {
            it.create().withIntent(RingKitIntents.withCall(
                Intent(context, RingingService::class.java), RingKitIntents.ACTION_RING, id,
            )).startCommand(0, 1)
        }
        core.writer.await { }
    }

    @Test
    fun r37_fgs_denied_player_delegates_and_scope_loss_preserves_terminal_vibration_cleanup() {
        show("fallback")
        core.writer.await { RingingService.bestEffortInProcessRing(context, core, "fallback") }
        assertNull(shadowOf(manager).lastAudioFocusRequest)
        assertFalse(engines.single().stopped)
        assertTrue(shadowOf(player.vibrator()).isVibrating)

        bridge.live.remove("fallback")
        bridge.hooks.getValue("fallback").onScopeClosed("fallback")
        core.writer.await { }
        assertTrue(engines.single().stopped)
        assertTrue(shadowOf(player.vibrator()).isVibrating)
        assertFalse(core.telecomCalls.containsKey("fallback"))

        core.writer.await { orch.declineOnWriter("fallback") }
        assertFalse(shadowOf(player.vibrator()).isVibrating)
    }

    @Test
    fun r37_held_service_recreation_does_not_restart_audio_or_take_focus() {
        show("held")
        startService("held")
        assertNull(shadowOf(manager).lastAudioFocusRequest)
        bridge.hooks.getValue("held").onSetInactive("held")
        core.writer.await { }
        core.writer.await { }
        assertTrue(engines.single().stopped)
        assertTrue(shadowOf(player.vibrator()).isVibrating)
        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertEquals(core.repository.callFor("held")!!.ringDeadlineElapsedMs,
            alarms.scheduledAlarms.single().triggerAtTime)

        controller!!.destroy()
        core.writer.await { }
        controller = null
        startService("held")
        bridge.hooks.getValue("held").onSetActive("held")
        core.writer.await { }

        assertEquals(1, engines.size)
        assertNull(shadowOf(manager).lastAudioFocusRequest)
        assertTrue(shadowOf(player.vibrator()).isVibrating)
        core.writer.await { orch.declineOnWriter("held") }
        core.writer.await { }
        assertFalse(shadowOf(player.vibrator()).isVibrating)
    }

    @Test
    fun r37_nontelecom_owner_promotion_takes_focus_and_ignores_old_scope_callbacks() {
        show("telecom")
        startService("telecom")
        show("standalone", telecom = false)
        assertNull(shadowOf(manager).lastAudioFocusRequest)

        core.writer.await { orch.declineOnWriter("telecom") }
        core.writer.await { }
        assertNotNull(shadowOf(manager).lastAudioFocusRequest)
        val current = engines.last()
        assertFalse(current.stopped)
        bridge.hooks.getValue("telecom").onScopeClosed("telecom")
        bridge.hooks.getValue("telecom").onSetInactive("telecom")
        core.writer.await { }

        assertFalse(current.stopped)
        core.writer.await { orch.declineOnWriter("standalone") }
        core.writer.await { }
        assertTrue(current.stopped)
    }

    private class Engine : RingPlaybackEngine {
        override val name = "MediaPlayer"
        var stopped = false
        override fun start() { stopped = false }
        override fun isPlaying() = !stopped
        override fun stop() { stopped = true }
    }

    private class Bridge : TelecomBridge {
        val live = mutableSetOf<String>()
        val hooks = mutableMapOf<String, TelecomHooks>()
        override val isRegistered = true
        override fun addCall(call: CoreIncomingCall, hooks: TelecomHooks): Boolean {
            live += call.id
            this.hooks[call.id] = hooks
            return true
        }
        override fun hasCall(callId: String) = callId in live
        override fun onLocalAnswer(callId: String) {}
        override fun onLocalActive(callId: String) {}
        override fun onLocalDisconnect(callId: String, cause: TelecomDisconnectCause) { live -= callId }
        override fun setAudioRoute(route: CoreAudioRoute) = false
    }
}
