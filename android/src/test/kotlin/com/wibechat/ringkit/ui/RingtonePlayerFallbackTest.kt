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
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingtonePlayerFallbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

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
    fun transient_duck_does_not_stop_an_active_ringtone() {
        val primary = FakeEngine("MediaPlayer")
        val player = player(FakeBackend(primary = primary, ringtone = null))

        player.start(
            record(ringtone = "content://settings/system/ringtone"),
            defaultRingtone = null,
            anyInCallState = false,
        )
        player.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, "call-1")

        assertEquals(0, primary.stopCount)
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
    fun final_playback_failure_creates_ring_audio_failed_incident() {
        val log = RingLog(TestSupport.newTempDir(), FakeTime()) { _, _ -> }
        val backend =
            FakeBackend(
                primary = FakeEngine("MediaPlayer", startError = IllegalStateException("primary failed")),
                ringtone = null,
                bundled = FakeEngine("BundledDefault", startError = IllegalStateException("bundled failed")),
            )

        player(backend, log).start(
            record(ringtone = "content://settings/system/ringtone"),
            defaultRingtone = null,
            anyInCallState = false,
        )

        assertTrue(log.awaitDrained())
        val incidents = log.incidentFiles().joinToString("\n") { it.readText() }
        assertTrue(incidents.contains("RING_AUDIO_FAILED"), incidents)
        assertTrue(incidents.contains("ringVolume"), incidents)
        assertTrue(incidents.contains("ringerMode"), incidents)
        assertTrue(incidents.contains("focusResult"), incidents)
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

    private fun player(
        backend: RingPlaybackBackend,
        ringLog: RingLog = RingLog(TestSupport.newTempDir(), FakeTime()) { _, _ -> },
        scheduler: RingPlaybackScheduler = ImmediateRingPlaybackScheduler,
    ): RingtonePlayer =
        RingtonePlayer(
            context = context,
            ringLog = ringLog,
            backend = backend,
            scheduler = scheduler,
            postToOwner = { it() },
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
    ) : RingPlaybackEngine {
        var errorListener: ((Int, Int) -> Unit)? = null
        var stopCount = 0

        override fun start() {
            startError?.let { throw it }
        }

        override fun isPlaying(): Boolean = playing

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
