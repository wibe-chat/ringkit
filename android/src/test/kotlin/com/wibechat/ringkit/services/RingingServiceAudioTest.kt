package com.wibechat.ringkit.services

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingKitIntents
import com.wibechat.ringkit.core.TestSupport
import com.wibechat.ringkit.ui.RingPlaybackBackend
import com.wibechat.ringkit.ui.RingPlaybackCancellation
import com.wibechat.ringkit.ui.RingPlaybackEngine
import com.wibechat.ringkit.ui.RingPlaybackScheduler
import com.wibechat.ringkit.ui.RingtonePlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/** Fault the audio boundary while exercising the real service/repository/alarm integration. */
@RunWith(RobolectricTestRunner::class)
class RingingServiceAudioTest {
    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance
    private lateinit var orchestrator: IncomingCallOrchestrator
    private lateinit var controller: ServiceController<RingingService>
    private lateinit var audio: FailingAudio
    private lateinit var player: RingtonePlayer

    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RingKitCore.resetForTests()
        core = RingKitCore.get(context)
        orchestrator = IncomingCallOrchestrator(context, core) { _, _ -> }
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio = FailingAudio()
        player = RingtonePlayer(context, core.ringLog, audio, object : RingPlaybackScheduler {
            override fun schedule(delayMs: Long, block: () -> Unit) = RingPlaybackCancellation { }
        }, dispatchPlayback = { it() })
        setProcessPlayer(player)
        core.writer.await { orchestrator.showOnWriter(TestSupport.incomingCall(id = "audio-fault")) }
        controller = Robolectric.buildService(RingingService::class.java)
        controller.create().withIntent(
            RingKitIntents.withCall(Intent(context, RingingService::class.java), RingKitIntents.ACTION_RING, "audio-fault"),
        ).startCommand(0, 1)
        core.writer.await { }
    }

    @After
    fun tearDown() {
        if (RingingService.isRunning) controller.destroy()
        core.writer.await { player.stop() }
        core.writer.await { }
        setProcessPlayer(null)
        RingKitCore.resetForTests()
    }

    @Test
    fun r32_late_audio_failure_arms_no_audio_timeout_without_ending_call() {
        assertTrue("playing owner initially needs no alarm backstop", alarms.scheduledAlarms.isEmpty())
        audio.error(1, -19)
        core.writer.await { }

        assertEquals(CoreCallState.RINGING, core.repository.callFor("audio-fault")!!.state)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertEquals(core.repository.callFor("audio-fault")!!.ringDeadlineElapsedMs, alarm.triggerAtTime)
        assertEquals(RingKitIntents.ACTION_TIMEOUT, shadowOf(alarm.operation).savedIntent.action)
        assertTrue("diagnose trace contains the later playback failure", core.trace.buffer.entries.any {
            it.callId == "audio-fault" && it.message.contains("audio state: Failed")
        })
    }

    @Test
    fun r32_late_audio_callback_after_accept_does_not_rearm_ringing() {
        core.writer.await { orchestrator.acceptProgrammaticOnWriter("audio-fault") }
        core.writer.await { }
        audio.error(1, -19)
        core.writer.await { }

        assertEquals(CoreCallState.ACCEPTED, core.repository.callFor("audio-fault")!!.state)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(1, audio.openCount)
        assertTrue(audio.stopped)
    }

    @Test
    fun r37_fgs_denied_accept_stops_fallback_audio_and_late_errors() {
        controller.destroy()
        core.writer.await { }
        audio.stopped = false
        core.writer.await {
            RingingService.bestEffortInProcessRing(context, core, "audio-fault")
            orchestrator.acceptProgrammaticOnWriter("audio-fault")
        }
        assertTrue("accept without a live service must stop fallback", audio.stopped)
        val count = audio.openCount
        audio.error(1, -19)
        assertEquals(count, audio.openCount)
    }

    @Test
    fun r37_old_service_destroy_does_not_stop_replacement_session() {
        core.writer.await { RingingService.bestEffortInProcessRing(context, core, "audio-fault") }
        audio.stopped = false
        controller.destroy()
        core.writer.await { }
        org.junit.Assert.assertFalse(audio.stopped)
    }

    @Test
    fun r37_fgs_denied_decline_stops_fallback() = checkFallbackTerminal { orchestrator.declineOnWriter(it) }

    @Test
    fun r37_fgs_denied_timeout_stops_fallback() = checkFallbackTerminal { orchestrator.timeoutOnWriter(it) }

    private fun checkFallbackTerminal(terminal: (String) -> Unit) {
        controller.destroy()
        core.writer.await { }
        audio.stopped = false
        core.writer.await {
            RingingService.bestEffortInProcessRing(context, core, "audio-fault")
            terminal("audio-fault")
        }
        assertTrue(audio.stopped)
    }

    @Test
    fun r37_focus_loss_then_fallback_accept_cancels_vibration() = focusLossThenStop(false)

    @Test
    fun r37_focus_loss_then_service_destroy_cancels_vibration() = focusLossThenStop(true)

    private fun focusLossThenStop(destroyService: Boolean) {
        if (!destroyService) {
            controller.destroy()
            core.writer.await { }
            core.writer.await { RingingService.bestEffortInProcessRing(context, core, "audio-fault") }
        }
        val vibrator = player.vibrator()
        assertTrue(shadowOf(vibrator).isVibrating)
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(manager).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue("audio focus loss alone preserves ring vibration", shadowOf(vibrator).isVibrating)
        if (destroyService) controller.destroy()
        else core.writer.await { orchestrator.acceptProgrammaticOnWriter("audio-fault") }
        core.writer.await { }
        org.junit.Assert.assertFalse("terminal cleanup must still own the vibration session", shadowOf(vibrator).isVibrating)
        if (!destroyService) {
            val field = RingingService::class.java.getDeclaredField("fallbackSubscription").apply { isAccessible = true }
            org.junit.Assert.assertNull(field.get(null))
        }
    }

    @Test
    fun r37_destroy_after_queued_start_stops_that_service_session() {
        core.writer.await { player.stop() }
        val service = controller.get()
        for (name in listOf("audioSession", "audioOwnerCallId")) {
            RingingService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, null)
        }
        audio.stopped = false
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        core.writer.post { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            core.writer.post {
                RingingService::class.java.getDeclaredMethod("reevaluateOnWriter", String::class.java)
                    .apply { isAccessible = true }.invoke(service, "queued-start")
            }
            controller.destroy()
        } finally { release.countDown() }
        core.writer.await { }
        assertTrue("destroy must stop the session started ahead of its writer cleanup", audio.stopped)
        org.junit.Assert.assertFalse(shadowOf(player.vibrator()).isVibrating)
    }

    @Test
    fun r37_production_async_error_recovers_on_writer() = assertRecoveryOnWriter(true)

    @Test
    fun r37_production_delayed_playback_check_recovers_on_writer() = assertRecoveryOnWriter(false)

    private fun assertRecoveryOnWriter(asyncError: Boolean) {
        core.writer.await { player.stop() }
        setProcessPlayer(null)
        player = RingingService.sharedPlayer(context, core)
        val creations = java.util.Collections.synchronizedList(mutableListOf<Pair<org.robolectric.shadows.ShadowMediaPlayer, Thread>>())
        org.robolectric.shadows.ShadowMediaPlayer.setCreateListener { _, shadow -> creations.add(shadow to Thread.currentThread()) }
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider { org.robolectric.shadows.ShadowMediaPlayer.MediaInfo() }
        lateinit var writerThread: Thread
        core.writer.await {
            writerThread = Thread.currentThread()
            player.start(core.repository.callFor("audio-fault")!!, "file:///tone.wav", false)
        }
        val primary = creations.first().first
        if (asyncError) primary.invokeErrorListener(1, -19)
        else {
            primary.setState(org.robolectric.shadows.ShadowMediaPlayer.State.STOPPED)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
        }
        core.writer.await { }
        assertEquals(2, creations.size)
        assertEquals("fallback provider and prepare work belongs on the writer", writerThread, creations.last().second)
    }

    private fun setProcessPlayer(value: RingtonePlayer?) {
        RingingService::class.java.getDeclaredField("processPlayer").apply { isAccessible = true }.set(null, value)
    }

    private class FailingAudio : RingPlaybackBackend, RingPlaybackEngine {
        override val name = "MediaPlayer"
        var openCount = 0
        var stopped = false
        lateinit var error: (Int, Int) -> Unit
        override fun openPrimary(uri: Uri, attributes: AudioAttributes, onError: (Int, Int) -> Unit): RingPlaybackEngine {
            openCount++
            error = onError
            return this
        }
        override fun openRingtone(uri: Uri, attributes: AudioAttributes): RingPlaybackEngine? = null
        override fun openBundled(attributes: AudioAttributes, onError: (Int, Int) -> Unit): RingPlaybackEngine =
            throw IllegalStateException("bundled playback unavailable")
        override fun start() = Unit
        override fun isPlaying() = !stopped
        override fun stop() { stopped = true }
    }
}
