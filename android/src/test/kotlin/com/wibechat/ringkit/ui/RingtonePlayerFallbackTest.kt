package com.wibechat.ringkit.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.core.CallRecord
import com.wibechat.ringkit.core.CoreCallDirection
import com.wibechat.ringkit.core.CoreCallInfo
import com.wibechat.ringkit.core.CoreCallOptions
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.CoreCallType
import com.wibechat.ringkit.core.FakeTime
import com.wibechat.ringkit.core.RingLog
import com.wibechat.ringkit.core.TestSupport
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingtonePlayerFallbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun r37_live_telecom_owner_does_not_request_competing_audio_focus() {
        val primary = FakeEngine("MediaPlayer")
        val player = player(FakeBackend(primary = primary, ringtone = null),
            focusOwnerFor = { RingFocusOwner.TELECOM })
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val outcome = player.start(record("content://settings/system/ringtone"), null, false)

        assertEquals("MediaPlayer", outcome.engine)
        assertTrue(outcome.vibrating)
        assertNull(shadowOf(manager).lastAudioFocusRequest,
            "Telecom already owns focus; a second request is stopped by Telecom's ring focus")
        player.stop()
    }

    @Test
    fun r37_telecom_option_without_live_owner_keeps_independent_focus_loss_policy() {
        val primary = FakeEngine("MediaPlayer")
        val player = player(FakeBackend(primary = primary, ringtone = null))
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val call = record("content://settings/system/ringtone")
        player.start(call.copy(options = call.options.copy(useTelecom = true)), null, false)

        shadowOf(manager).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(1, primary.stopCount)
        assertTrue(shadowOf(player.vibrator()).isVibrating)
        player.stop()
        assertTrue(!shadowOf(player.vibrator()).isVibrating)
    }

    @Test
    fun r37_suspended_telecom_owner_keeps_vibration_without_audio_or_focus() {
        val backend = FakeBackend(primary = FakeEngine("MediaPlayer"), ringtone = null)
        val player = player(backend, focusOwnerFor = { RingFocusOwner.TELECOM_SUSPENDED })
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val outcome = player.start(record("content://settings/system/ringtone"), null, false)

        assertTrue(backend.opened.isEmpty())
        assertEquals("telecomInactive", outcome.resolution)
        assertTrue(outcome.vibrating)
        assertNull(shadowOf(manager).lastAudioFocusRequest)
        player.stop()
    }

    @Test
    fun r37_stale_focus_and_telecom_callbacks_cannot_stop_a_new_delegated_owner() {
        val primary = FakeEngine("MediaPlayer")
        val player = player(FakeBackend(primary = primary, ringtone = null),
            focusOwnerFor = { if (it == "call-b") RingFocusOwner.TELECOM else RingFocusOwner.RINGKIT })
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        player.start(record("content://settings/system/ringtone", "call-a"), null, false)
        val oldRequest = shadowOf(manager).lastAudioFocusRequest
        player.start(record("content://settings/system/ringtone", "call-b"), null, false)

        oldRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        player.stopTelecomAudio("call-a", "scopeClosed")

        assertEquals(1, primary.stopCount, "only replacement stopped call A")
        assertTrue(oldRequest === shadowOf(manager).lastAudioFocusRequest)
        player.stopTelecomAudio("call-b", "inactive")
        assertEquals(2, primary.stopCount)
        assertTrue(shadowOf(player.vibrator()).isVibrating)
        player.stop()
        assertTrue(!shadowOf(player.vibrator()).isVibrating)
    }

    @Test
    fun asynchronous_primary_error_advances_once_to_ringtone() {
        val primary = FakeEngine("MediaPlayer")
        val ringtone = FakeEngine("Ringtone")
        val backend = FakeBackend(primary = primary, ringtone = ringtone)
        val states = mutableListOf<RingAudioState>()
        val player = player(backend)

        val outcome = player.start(
            record(ringtone = "content://settings/system/ringtone"),
            defaultRingtone = null,
            anyInCallState = false,
            onAudioState = states::add,
        )
        primary.failAsync(1, -19)

        assertEquals("MediaPlayer", outcome.engine)
        assertEquals(listOf("MediaPlayer", "Ringtone"), backend.opened)
        assertEquals(1, primary.stopCount)
        assertEquals(RingAudioState.Playing("Ringtone"), states.last())
    }

    @Test
    fun transient_duck_stops_an_active_ringtone() {
        val primary = FakeEngine("MediaPlayer")
        val player = player(FakeBackend(primary = primary, ringtone = null))

        player.start(
            record(ringtone = "content://settings/system/ringtone"),
            defaultRingtone = null,
            anyInCallState = false,
        )
        player.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, "call-1")

        assertEquals(1, primary.stopCount)
    }

    @Test
    fun stale_focus_loss_from_previous_call_does_not_stop_current_call() {
        val primary = FakeEngine("MediaPlayer")
        val player = player(FakeBackend(primary = primary, ringtone = null))
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowAudioManager = shadowOf(audioManager)

        player.start(
            record(ringtone = "content://settings/system/ringtone", callId = "call-a"),
            defaultRingtone = null,
            anyInCallState = false,
        )
        val callAFocusListener = shadowAudioManager.lastAudioFocusRequest.listener

        player.start(
            record(ringtone = "content://settings/system/ringtone", callId = "call-b"),
            defaultRingtone = null,
            anyInCallState = false,
        )
        val callBFocusListener = shadowAudioManager.lastAudioFocusRequest.listener

        assertEquals(1, primary.stopCount, "starting call B releases call A's engine")
        callAFocusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, primary.stopCount, "stale call A focus loss must not stop call B")

        callBFocusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(2, primary.stopCount, "call B's own focus loss still stops call B")
    }

    @Test
    fun ringtone_that_never_plays_advances_to_bundled_default() {
        val scheduler = ManualScheduler()
        val ringtone = FakeEngine("Ringtone", playing = false)
        val bundled = FakeEngine("BundledDefault")
        val backend =
            FakeBackend(
                primary = FakeEngine("MediaPlayer", startError = IllegalStateException("primary failed")),
                ringtone = ringtone,
                bundled = bundled,
            )
        val states = mutableListOf<RingAudioState>()
        val player = player(backend, scheduler = scheduler)

        player.start(
            record(ringtone = "content://settings/system/ringtone"),
            defaultRingtone = null,
            anyInCallState = false,
            onAudioState = states::add,
        )
        scheduler.runAll()

        assertEquals(listOf("MediaPlayer", "Ringtone", "BundledDefault"), backend.opened)
        assertEquals(1, ringtone.stopCount)
        assertEquals(RingAudioState.Playing("BundledDefault"), states.last())
    }

    @Test
    fun late_error_from_stopped_generation_cannot_restart_audio() {
        val primary = FakeEngine("MediaPlayer")
        val backend = FakeBackend(primary = primary, ringtone = null)
        val states = mutableListOf<RingAudioState>()
        val player = player(backend)

        player.start(
            record(ringtone = "content://settings/system/ringtone"),
            defaultRingtone = null,
            anyInCallState = false,
            onAudioState = states::add,
        )
        player.stop()
        primary.failAsync(1, -19)

        assertEquals(listOf("MediaPlayer"), backend.opened)
        assertEquals(RingAudioState.Stopped, states.last())
    }

    @Test
    fun primary_that_accepts_start_but_never_plays_advances_to_ringtone() {
        val scheduler = ManualScheduler()
        val primary = FakeEngine("MediaPlayer", playing = false)
        val ringtone = FakeEngine("Ringtone")
        val backend = FakeBackend(primary = primary, ringtone = ringtone)
        val states = mutableListOf<RingAudioState>()
        val player = player(backend, scheduler = scheduler)

        player.start(
            record(ringtone = "content://settings/system/ringtone"),
            defaultRingtone = null,
            anyInCallState = false,
            onAudioState = states::add,
        )
        scheduler.runAll()

        assertEquals(listOf("MediaPlayer", "Ringtone"), backend.opened)
        assertEquals(1, primary.stopCount)
        assertEquals(RingAudioState.Playing("Ringtone"), states.last())
    }

    @Test
    fun pending_playback_verification_cannot_restart_after_silence() {
        val scheduler = ManualScheduler()
        val primary = FakeEngine("MediaPlayer", playing = false)
        val backend = FakeBackend(primary, FakeEngine("Ringtone"))
        val player = player(backend, scheduler = scheduler)
        player.start(record("content://settings/system/ringtone"), null, false)
        player.silence("call-1")
        scheduler.runAll()
        primary.failAsync(1, -19)
        assertEquals(listOf("MediaPlayer"), backend.opened)
        assertEquals(1, primary.stopCount)
    }

    @Test
    fun throwing_playback_probe_logs_failure_and_advances() {
        val scheduler = ManualScheduler()
        val messages = mutableListOf<String>()
        val log = RingLog(TestSupport.newTempDir(), FakeTime()) { _, message -> messages.add(message) }
        val backend = FakeBackend(FakeEngine("MediaPlayer", probeError = IllegalStateException("probe failure")), FakeEngine("Ringtone"))
        val player = player(backend, log, scheduler)
        player.start(record("content://settings/system/ringtone"), null, false)
        scheduler.runAll()
        assertTrue(log.awaitDrained())
        assertTrue(messages.any { it.contains("playback probe failed") })
        assertEquals(listOf("MediaPlayer", "Ringtone"), backend.opened)
        player.stop()
    }

    @Test
    fun throwing_stop_observer_cannot_interrupt_cleanup_or_next_start() {
        val primary = FakeEngine("MediaPlayer")
        val backend = FakeBackend(primary, null)
        val player = player(backend)
        player.start(record("file:///tone.wav"), null, false) { state ->
            if (state is RingAudioState.Stopped) throw java.util.concurrent.RejectedExecutionException("observer closed")
        }
        player.stop()
        assertEquals(1, primary.stopCount)
        org.junit.Assert.assertFalse(shadowOf(player.vibrator()).isVibrating)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        org.junit.Assert.assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        player.start(record("file:///tone.wav", "call-2"), null, false)
        assertEquals(2, backend.opened.size)
        player.stop()
    }

    @Test
    fun queued_error_and_check_cannot_recover_after_stop() {
        assertQueuedRecoveryIsFenced(false)
    }

    @Test
    fun queued_error_and_check_cannot_replace_new_owner() {
        assertQueuedRecoveryIsFenced(true)
    }

    private fun assertQueuedRecoveryIsFenced(replace: Boolean) {
        val jobs = mutableListOf<() -> Unit>()
        val scheduler = ManualScheduler()
        val primary = FakeEngine("MediaPlayer", playing = false)
        val backend = FakeBackend(primary, FakeEngine("Ringtone"))
        val player = RingtonePlayer(context, RingLog(TestSupport.newTempDir(), FakeTime()) { _, _ -> },
            backend, scheduler, dispatchPlayback = { jobs.add(it) })
        player.start(record("content://settings/system/ringtone"), null, false)
        primary.failAsync(1, -19)
        scheduler.runAll()
        assertEquals(2, jobs.size)
        player.stop()
        if (replace) player.start(record("content://settings/system/ringtone", "call-2"), null, false)
        jobs.toList().forEach { it() }
        assertEquals(if (replace) listOf("MediaPlayer", "MediaPlayer") else listOf("MediaPlayer"), backend.opened)
        player.stop()
    }

    private fun player(
        backend: RingPlaybackBackend,
        ringLog: RingLog = RingLog(TestSupport.newTempDir(), FakeTime()) { _, _ -> },
        scheduler: RingPlaybackScheduler = ManualScheduler(),
        focusOwnerFor: (String) -> RingFocusOwner = { RingFocusOwner.RINGKIT },
    ): RingtonePlayer =
        RingtonePlayer(
            context = context,
            ringLog = ringLog,
            backend = backend,
            scheduler = scheduler,
            focusOwnerFor = focusOwnerFor,
            dispatchPlayback = { it() },
        )

    private fun record(ringtone: String?, callId: String = "call-1"): CallRecord {
        val time = FakeTime()
        return CallRecord(
            info =
                CoreCallInfo(
                    id = callId,
                    name = "Alice",
                    handle = "+15550100",
                    avatarUrl = null,
                    type = CoreCallType.AUDIO,
                    direction = CoreCallDirection.INCOMING,
                    extra = emptyMap(),
                ),
            state = CoreCallState.RINGING,
            options = CoreCallOptions(ringtone = ringtone, vibrate = true),
            timeoutSeconds = 45,
            shownAtWallMs = time.wall,
            shownAtElapsedMs = time.elapsed,
            bootCount = time.boot,
            ringDeadlineElapsedMs = time.elapsed + 45_000L,
            connectionDeadlineElapsedMs = null,
            surfacePosted = true,
        )
    }

    private class FakeBackend(
        private val primary: FakeEngine,
        private val ringtone: FakeEngine?,
        private val bundled: FakeEngine? = null,
    ) : RingPlaybackBackend {
        val opened = mutableListOf<String>()

        override fun openPrimary(
            uri: Uri,
            attributes: AudioAttributes,
            onError: (what: Int, extra: Int) -> Unit,
        ): RingPlaybackEngine = primary.apply {
            opened += name
            errorListener = onError
        }

        override fun openRingtone(uri: Uri, attributes: AudioAttributes): RingPlaybackEngine? =
            ringtone?.apply { opened += name }

        override fun openBundled(
            attributes: AudioAttributes,
            onError: (what: Int, extra: Int) -> Unit,
        ): RingPlaybackEngine =
            requireNotNull(bundled) { "bundled fallback must not be reached" }.apply {
                opened += name
                errorListener = onError
            }
    }

    private class FakeEngine(
        override val name: String,
        private val startError: Throwable? = null,
        private val playing: Boolean = true,
        private val probeError: Exception? = null,
    ) : RingPlaybackEngine {
        var errorListener: ((Int, Int) -> Unit)? = null
        var stopCount = 0

        override fun start() {
            startError?.let { throw it }
        }

        override fun isPlaying(): Boolean {
            probeError?.let { throw it }
            return playing
        }

        override fun stop() {
            stopCount++
        }

        fun failAsync(what: Int, extra: Int) {
            errorListener?.invoke(what, extra)
        }
    }

    private class ManualScheduler : RingPlaybackScheduler {
        private val tasks = mutableListOf<() -> Unit>()

        override fun schedule(delayMs: Long, block: () -> Unit): RingPlaybackCancellation {
            tasks += block
            return RingPlaybackCancellation { tasks.remove(block) }
        }

        fun runAll() {
            val pending = tasks.toList()
            tasks.clear()
            pending.forEach { it() }
        }
    }
}
