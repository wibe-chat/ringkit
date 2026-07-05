package com.wibechat.ringkit.ui

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Vibrator
import android.os.VibratorManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * §8.4: ringing-policy resolver (ringer modes × DND × in-call etiquette,
 * matchesCallFilter 34+ ONLY — r5), vibration two-branch selection (r37/r5
 * class), and the source-grammar/ladder seams that run without real audio.
 */
@RunWith(RobolectricTestRunner::class)
class RingtonePlayerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun player() = RingtonePlayer(context, RingLog(TestSupport.newTempDir(), FakeTime()) { _, _ -> })

    private fun record(vibrate: Boolean = true): CallRecord {
        val time = FakeTime()
        return CallRecord(
            info =
                CoreCallInfo(
                    id = "call-1", name = "Alice", handle = "+15550100", avatarUrl = null,
                    type = CoreCallType.AUDIO, direction = CoreCallDirection.INCOMING, extra = emptyMap(),
                ),
            state = CoreCallState.RINGING,
            options = CoreCallOptions(vibrate = vibrate),
            timeoutSeconds = 45,
            shownAtWallMs = time.wall,
            shownAtElapsedMs = time.elapsed,
            bootCount = time.boot,
            ringDeadlineElapsedMs = time.elapsed + 45_000L,
            connectionDeadlineElapsedMs = null,
            surfacePosted = true,
        )
    }

    // ── resolver (§8.4) ──────────────────────────────────────────────────

    @Test
    fun resolver_normal_mode_allows_sound_and_vibration() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val policy = RingPolicyResolver.resolve(context, wantVibrate = true, personUri = null, anyInCallState = false)
        assertTrue(policy.sound)
        assertTrue(policy.vibrate)
        assertEquals("normal", policy.reason)
    }

    @Test
    fun resolver_silent_mode_suppresses_everything() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        val policy = RingPolicyResolver.resolve(context, wantVibrate = true, personUri = null, anyInCallState = false)
        assertFalse(policy.sound)
        assertFalse(policy.vibrate)
        assertEquals("ringerSilent", policy.reason)
    }

    @Test
    fun resolver_vibrate_mode_vibrates_only_when_call_wants_it() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val wants = RingPolicyResolver.resolve(context, wantVibrate = true, personUri = null, anyInCallState = false)
        assertFalse(wants.sound)
        assertTrue(wants.vibrate)
        val declines = RingPolicyResolver.resolve(context, wantVibrate = false, personUri = null, anyInCallState = false)
        assertFalse(declines.vibrate)
    }

    @Test
    fun resolver_in_call_etiquette_beats_everything() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val policy = RingPolicyResolver.resolve(context, wantVibrate = true, personUri = "tel:+1", anyInCallState = true)
        assertFalse(policy.sound)
        assertFalse(policy.vibrate)
        assertEquals("inCallEtiquette", policy.reason)
    }

    @Test
    @Config(sdk = [33])
    fun r5_dnd_on_api_33_suppresses_conservatively_never_calls_matchesCallFilter() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true); notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val policy = RingPolicyResolver.resolve(context, wantVibrate = true, personUri = "tel:+15550100", anyInCallState = false)
        assertFalse(policy.sound)
        assertEquals("dndConservativeSuppress", policy.reason)
    }

    @Test
    @Config(sdk = [34])
    fun dnd_on_api_34_consults_matchesCallFilter() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true); notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val policy = RingPolicyResolver.resolve(context, wantVibrate = true, personUri = "tel:+15550100", anyInCallState = false)
        // Robolectric's matchesCallFilter answers false (no DND exception data):
        // the resolver must land on the suppressed branch, not throw.
        assertFalse(policy.sound)
        assertEquals("dndSuppressed", policy.reason)
    }

    // ── vibration two-branch (§8.4, r37/r5 class) ────────────────────────

    @Test
    @Config(sdk = [26])
    fun vibration_on_api_26_uses_legacy_vibrator_service() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val player = player()
        player.start(record(vibrate = true), defaultRingtone = null, anyInCallState = false)
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        assertTrue("26–32: AudioAttributes overload must vibrate", shadowOf(vibrator).isVibrating)
        player.stop()
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    @Config(sdk = [33])
    fun vibration_on_api_33_uses_vibration_attributes_branch() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val player = player()
        player.start(record(vibrate = true), defaultRingtone = null, anyInCallState = false)
        val vibrator =
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        assertTrue("33+: VibrationAttributes overload must vibrate", shadowOf(vibrator).isVibrating)
        player.stop()
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun silence_stops_vibration_and_audio_but_is_reversible_state() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val player = player()
        val outcome = player.start(record(vibrate = true), defaultRingtone = null, anyInCallState = false)
        assertTrue(outcome.vibrating)
        player.silence("call-1")
        val vibrator =
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    // ── outcome honesty in the sandbox ───────────────────────────────────

    @Test
    fun sandbox_ladder_failure_reports_inaudible_never_throws() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        // Robolectric has no real audio; every rung may fail — the outcome must
        // say so (G3: report what the ear would hear), never crash.
        val outcome = player().start(record(), defaultRingtone = "raw:not_a_real_tone", anyInCallState = false)
        assertEquals("normal", outcome.resolution)
        // audible may be true only if a shadow MediaPlayer accepted the source;
        // what matters is coherence: engine==null ⇒ audible==false.
        if (outcome.engine == null) assertFalse(outcome.audible)
    }
}
