package com.wibechat.ringkit.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.wibechat.ringkit.R
import com.wibechat.ringkit.core.CallRecord
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.RingLog

/**
 * Ringing policy resolver (PLAN §8.4) — runs BEFORE any sound. Deliberate
 * silence ≠ bug: the outcome is always ring-traced, and it never sets
 * `CallRinging.suppressed` (reserved for §8.11 failed/silent-mode rows).
 */
object RingPolicyResolver {

    data class Policy(val sound: Boolean, val vibrate: Boolean, val reason: String)

    fun resolve(
        context: Context,
        wantVibrate: Boolean,
        personUri: String?,
        anyInCallState: Boolean,
    ): Policy {
        // §5.2 in-call etiquette: while any call is in an in-call state,
        // incoming calls never grab audio focus or play the full ringtone.
        if (anyInCallState) return Policy(sound = false, vibrate = false, reason = "inCallEtiquette")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> return Policy(false, false, "ringerSilent")
            AudioManager.RINGER_MODE_VIBRATE -> return Policy(false, wantVibrate, "ringerVibrate")
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val filter = notificationManager.currentInterruptionFilter
        if (filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) {
            // matchesCallFilter is public API 34+ ONLY (hidden earlier; coding it
            // lower throws) — 26–33 suppress conservatively (§8.4, R5).
            return if (Build.VERSION.SDK_INT >= 34) {
                val allowed =
                    personUri != null &&
                        runCatching { notificationManager.matchesCallFilter(Uri.parse(personUri)) }.getOrDefault(false)
                if (allowed) Policy(true, wantVibrate, "dndCallExempted")
                else Policy(false, false, "dndSuppressed")
            } else {
                Policy(false, false, "dndConservativeSuppress")
            }
        }
        return Policy(true, wantVibrate, "normal")
    }
}

/**
 * The ring audio engine (PLAN §8.4): MediaPlayer primary → `Ringtone` fallback
 * on content:// failure (the default-ringtone settings URI and shared-storage
 * tones are NOT app-readable; `Ringtone` sidesteps via SystemUI's remote
 * IRingtonePlayer — R37) → plugin-bundled raw resource as the failure rung.
 *
 * NO screen-off receiver, NO volume-change receiver (#827/#792), and
 * `AudioManager.mode` is NEVER touched (#416): stream volume 0 or focus loss
 * silences both engines without any state mutation on our side.
 */
class RingtonePlayer(
    private val context: Context,
    private val ringLog: RingLog,
) {
    data class RingOutcome(
        val audible: Boolean,
        val vibrating: Boolean,
        val engine: String?,
        val resolution: String,
    )

    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var focusRequest: AudioFocusRequest? = null
    private var vibrating = false

    private val ringAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /** Start ring per policy; safe to call once per owner call (stop() first when re-pointing). */
    fun start(record: CallRecord, defaultRingtone: String?, anyInCallState: Boolean): RingOutcome {
        val personUri = record.info.handle?.let { "tel:$it" }
        val policy =
            RingPolicyResolver.resolve(context, record.options.vibrate, personUri, anyInCallState)
        ringLog.log(
            CoreLogLevel.INFO, "ring", "RingtonePlayer.start", record.callId, "policy",
            mapOf("reason" to policy.reason, "sound" to policy.sound.toString(), "vibrate" to policy.vibrate.toString()),
        )

        var engine: String? = null
        if (policy.sound) {
            requestFocus(record.callId)
            engine = playLadder(record.callId, record.options.ringtone ?: defaultRingtone)
        }
        if (policy.vibrate) startVibration(record.callId)

        return RingOutcome(
            audible = engine != null,
            vibrating = policy.vibrate,
            engine = engine,
            resolution = policy.reason,
        )
    }

    /**
     * §5.2 short attention cue for a waiting call: one brief vibration pulse —
     * never audio, never focus. Policy-resolved (silent/DND/vibrate honored);
     * skipped while the owner's vibration loop holds the vibrator (a one-shot
     * `vibrate()` would replace and kill the loop).
     */
    fun waitingCue(record: CallRecord) {
        val personUri = record.info.handle?.let { "tel:$it" }
        val policy =
            RingPolicyResolver.resolve(context, record.options.vibrate, personUri, anyInCallState = false)
        ringLog.log(
            CoreLogLevel.INFO, "ring", "RingtonePlayer.waitingCue", record.callId, "waiting_cue",
            mapOf("reason" to policy.reason, "vibrate" to policy.vibrate.toString(), "ownerVibrating" to vibrating.toString()),
        )
        if (!policy.vibrate || vibrating) return
        try {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 150, 120, 150), -1)
            val vibrator = vibrator()
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, ringAttributes)
            }
        } catch (e: Exception) {
            ringLog.log(CoreLogLevel.ERROR, "ring", "RingtonePlayer.waitingCue", record.callId, "cue failed", emptyMap(), e)
        }
    }

    /** Volume-key silence (§8.4: exists ONLY via IncomingCallActivity.onKeyDown). */
    fun silence(callId: String?) {
        ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.silence", callId, "user_silenced")
        stopAudioOnly()
        stopVibration()
    }

    fun stop() {
        stopAudioOnly()
        stopVibration()
    }

    // ── engine ladder ────────────────────────────────────────────────────

    private fun playLadder(callId: String, source: String?): String? {
        val resolved = resolveSource(callId, source)

        // Rung 1: MediaPlayer (uniform looping ≥26, focus integration).
        try {
            val player = MediaPlayer()
            player.setAudioAttributes(ringAttributes)
            player.setDataSource(context, resolved)
            player.isLooping = true
            player.setOnErrorListener { _, what, extra ->
                ringLog.log(
                    CoreLogLevel.ERROR, "ring", "RingtonePlayer.playLadder", callId,
                    "MediaPlayer async error", mapOf("what" to "$what", "extra" to "$extra"),
                )
                true
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            return "MediaPlayer"
        } catch (e: Exception) {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            ringLog.log(
                CoreLogLevel.INFO, "ring", "RingtonePlayer.playLadder", callId,
                "MediaPlayer rung failed — trying Ringtone", mapOf("source" to resolved.toString()), e,
            )
        }

        // Rung 2: Ringtone via IRingtonePlayer — reads content:// we cannot (R37).
        // 26–27 cannot loop it (§8.8): skip straight to the loopable bundled default.
        if (Build.VERSION.SDK_INT >= 28 && resolved.scheme == "content") {
            try {
                val tone = RingtoneManager.getRingtone(context, resolved)
                if (tone != null) {
                    tone.audioAttributes = ringAttributes
                    tone.isLooping = true
                    tone.play()
                    ringtone = tone
                    return "Ringtone"
                }
            } catch (e: Exception) {
                runCatching { ringtone?.stop() }
                ringtone = null
                ringLog.log(
                    CoreLogLevel.INFO, "ring", "RingtonePlayer.playLadder", callId,
                    "Ringtone rung failed — bundled default", emptyMap(), e,
                )
            }
        }

        // Failure rung: plugin-bundled raw resource (code-referenced, R8-safe).
        try {
            val player = MediaPlayer.create(context, R.raw.ringkit_default_ringtone)
            if (player != null) {
                player.setAudioAttributes(ringAttributes)
                player.isLooping = true
                player.start()
                mediaPlayer = player
                return "BundledDefault"
            }
        } catch (e: Exception) {
            ringLog.log(
                CoreLogLevel.ERROR, "ring", "RingtonePlayer.playLadder", callId,
                "every rung failed — silent ring", emptyMap(), e,
            )
        }
        return null
    }

    /**
     * §8.4 source grammar: unset ⇒ 'default' → device ringtone; 'raw:<name>' →
     * app res/raw; 'content://…' as-is; 'file://…' = the initialize()-extracted
     * flutterAsset copy. Every rung try-caught (#219/#341 classes).
     */
    private fun resolveSource(callId: String, source: String?): Uri {
        val spec = source?.takeIf { it.isNotBlank() } ?: "default"
        return when {
            spec == "default" -> deviceDefaultRingtone(callId)
            spec.startsWith("raw:") -> {
                val name = spec.removePrefix("raw:")
                val resId = context.resources.getIdentifier(name, "raw", context.packageName)
                if (resId != 0) {
                    Uri.parse("android.resource://${context.packageName}/$resId")
                } else {
                    ringLog.log(
                        CoreLogLevel.ERROR, "ring", "RingtonePlayer.resolveSource", callId,
                        "raw:$name unresolvable (keep.xml? R8?) — device default",
                    )
                    deviceDefaultRingtone(callId)
                }
            }
            spec.startsWith("content://") || spec.startsWith("file://") -> Uri.parse(spec)
            else -> {
                ringLog.log(
                    CoreLogLevel.ERROR, "ring", "RingtonePlayer.resolveSource", callId,
                    "unknown ringtone grammar — device default", mapOf("spec" to spec),
                )
                deviceDefaultRingtone(callId)
            }
        }
    }

    private fun deviceDefaultRingtone(callId: String): Uri {
        // Primary: the actual-default lookup; OEM 'Settings key not readable'
        // class (#219) falls through to a cursor walk, then the bundled tone.
        runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        }.getOrNull()?.let { return it }
        runCatching {
            val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_RINGTONE) }
            val cursor = manager.cursor
            if (cursor.moveToFirst()) manager.getRingtoneUri(cursor.position) else null
        }.getOrNull()?.let {
            ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.deviceDefaultRingtone", callId, "cursor-walk fallback")
            return it
        }
        ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.deviceDefaultRingtone", callId, "no device default — bundled")
        return Uri.parse("android.resource://${context.packageName}/${R.raw.ringkit_default_ringtone}")
    }

    // ── audio focus (§8.4) ───────────────────────────────────────────────

    private fun requestFocus(callId: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(ringAttributes)
                // Listener ALWAYS set (§8.4): loss → stop the sound, keep state.
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    ) {
                        ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.focus", callId, "focus_loss_stop")
                        stopAudioOnly()
                    }
                }
                .build()
        focusRequest = request
        val granted = audioManager.requestAudioFocus(request)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.focus", callId, "focus_denied_playing_anyway")
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocusRequest(request)
        }
    }

    // ── vibration (§8.4 two-branch, R5) ──────────────────────────────────

    internal fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private fun startVibration(callId: String) {
        try {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0)
            val vibrator = vibrator()
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                // 26–32: the AudioAttributes overload is what authorizes
                // background vibration pre-33 (platform-verified, §8.4).
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, ringAttributes)
            }
            vibrating = true
        } catch (e: Exception) {
            ringLog.log(CoreLogLevel.ERROR, "ring", "RingtonePlayer.startVibration", callId, "vibrate failed", emptyMap(), e)
        }
    }

    private fun stopVibration() {
        if (!vibrating) return
        vibrating = false
        runCatching { vibrator().cancel() }
    }

    private fun stopAudioOnly() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
            }
            runCatching { player.release() }
        }
        mediaPlayer = null
        ringtone?.let { tone -> runCatching { tone.stop() } }
        ringtone = null
        abandonFocus()
    }
}
