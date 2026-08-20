package com.wibechat.ringkit.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
 * `AudioManager.mode` is NEVER touched (#416). Playback failures advance the
 * shared ladder; user volume/ringer policy is observed and never mutated.
 */
internal class RingtonePlayer(
    private val context: Context,
    private val ringLog: RingLog,
    private val backend: RingPlaybackBackend = AndroidRingPlaybackBackend(context),
    private val scheduler: RingPlaybackScheduler = HandlerRingPlaybackScheduler(),
    private val postToOwner: ((() -> Unit) -> Unit) = { it() },
) {
    data class RingOutcome(
        val audible: Boolean,
        val vibrating: Boolean,
        val engine: String?,
        val resolution: String,
    )

    private enum class Rung { PRIMARY, RINGTONE, BUNDLED }

    private var activeEngine: RingPlaybackEngine? = null
    private var activeAttempt = 0L
    private var generation = 0L
    private var activeCallId: String? = null
    private var activeSource: Uri? = null
    private var nextRung: Rung? = null
    private var stateListener: ((RingAudioState) -> Unit)? = null
    private var verification: RingPlaybackCancellation? = null
    private val attemptedRungs = mutableListOf<String>()
    private var lastFailure = "none"
    private var lastFocusResult = "notRequested"
    private var audioSnapshot: Map<String, String> = emptyMap()
    private var focusRequest: AudioFocusRequest? = null
    private var vibrating = false

    private val ringAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /** Start ring per policy; safe to call once per owner call (stop() first when re-pointing). */
    @Synchronized
    fun start(
        record: CallRecord,
        defaultRingtone: String?,
        anyInCallState: Boolean,
        onAudioState: ((RingAudioState) -> Unit)? = null,
    ): RingOutcome {
        val personUri = record.info.handle?.let { "tel:$it" }
        val policy =
            RingPolicyResolver.resolve(context, record.options.vibrate, personUri, anyInCallState)
        ringLog.log(
            CoreLogLevel.INFO, "ring", "RingtonePlayer.start", record.callId, "policy",
            mapOf("reason" to policy.reason, "sound" to policy.sound.toString(), "vibrate" to policy.vibrate.toString()) +
                captureAudioSnapshot(),
        )

        stateListener = onAudioState
        var engine: String? = null
        if (policy.sound) {
            beginAudioSession(record.callId, resolveSource(record.callId, record.options.ringtone ?: defaultRingtone))
            requestFocus(record.callId)
            engine = advance(Rung.PRIMARY)
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
    @Synchronized
    fun silence(callId: String?) {
        ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.silence", callId, "user_silenced")
        stopAudioOnly()
        stopVibration()
    }

    @Synchronized
    fun stop() {
        stopAudioOnly()
        stopVibration()
    }

    // ── engine ladder ────────────────────────────────────────────────────

    private fun beginAudioSession(callId: String, source: Uri) {
        generation++
        releaseActiveEngine()
        abandonFocus()
        activeCallId = callId
        activeSource = source
        nextRung = null
        attemptedRungs.clear()
        lastFailure = "none"
        lastFocusResult = "notRequested"
        audioSnapshot = captureAudioSnapshot()
    }

    private fun advance(first: Rung?): String? {
        var rung = first
        while (rung != null) {
            val currentRung = rung
            val callId = activeCallId ?: return null
            val source = activeSource ?: return null
            val currentGeneration = generation
            val attempt = ++activeAttempt
            var opened: RingPlaybackEngine? = null
            attemptedRungs += currentRung.name.lowercase()
            try {
                opened =
                    when (currentRung) {
                        Rung.PRIMARY ->
                            backend.openPrimary(source, ringAttributes) { what, extra ->
                                handleAsyncError(currentGeneration, attempt, what, extra)
                            }
                        Rung.RINGTONE -> {
                            if (Build.VERSION.SDK_INT < 28 || source.scheme != "content") null
                            else backend.openRingtone(source, ringAttributes)
                        }
                        Rung.BUNDLED ->
                            backend.openBundled(ringAttributes) { what, extra ->
                                handleAsyncError(currentGeneration, attempt, what, extra)
                            }
                    }
                if (opened == null) {
                    rung = currentRung.after()
                    continue
                }
                opened.start()
                releaseActiveEngine()
                activeEngine = opened
                activeAttempt = attempt
                nextRung = currentRung.after()
                emitState(RingAudioState.Playing(opened.name))
                schedulePlaybackVerification(currentGeneration, attempt, opened, nextRung)
                return activeEngine?.name
            } catch (error: Throwable) {
                runCatching { opened?.stop() }
                lastFailure = "sync:${currentRung.name.lowercase()}:${error.javaClass.simpleName}"
                ringLog.log(
                    CoreLogLevel.INFO,
                    "ring",
                    "RingtonePlayer.advance",
                    callId,
                    "${currentRung.name.lowercase()} rung failed",
                    mapOf("sourceKind" to sourceKind(source)),
                    error,
                )
                rung = currentRung.after()
            }
        }
        val callId = activeCallId
        releaseActiveEngine()
        emitState(RingAudioState.Failed("everyRungFailed"))
        ringLog.log(
            CoreLogLevel.ERROR,
            "ring",
            "RingtonePlayer.advance",
            callId,
            "every rung failed — silent ring",
        )
        ringLog.incident(
            RingLog.IncidentType.RING_AUDIO_FAILED,
            "RingtonePlayer.advance",
            callId,
            data =
                mapOf(
                    "sourceKind" to (activeSource?.let(::sourceKind) ?: "unknown"),
                    "attempted" to attemptedRungs.joinToString(","),
                    "lastFailure" to lastFailure,
                    "focusResult" to lastFocusResult,
                ) + audioSnapshot,
        )
        abandonFocus()
        return null
    }

    @Synchronized
    private fun handleAsyncError(expectedGeneration: Long, expectedAttempt: Long, what: Int, extra: Int) {
        if (generation != expectedGeneration || activeAttempt != expectedAttempt || activeEngine == null) return
        ringLog.log(
            CoreLogLevel.ERROR,
            "ring",
            "RingtonePlayer.advance",
            activeCallId,
            "playback async error",
            mapOf("engine" to activeEngine!!.name, "what" to "$what", "extra" to "$extra"),
        )
        lastFailure = "async:${activeEngine!!.name}:$what:$extra"
        val fallback = nextRung
        releaseActiveEngine()
        advance(fallback)
    }

    private fun schedulePlaybackVerification(
        expectedGeneration: Long,
        expectedAttempt: Long,
        engine: RingPlaybackEngine,
        fallback: Rung?,
    ) {
        verification?.cancel()
        verification =
            scheduler.schedule(PLAYBACK_VERIFY_DELAY_MS) {
                verifyPlayback(expectedGeneration, expectedAttempt, engine, fallback)
            }
    }

    @Synchronized
    private fun verifyPlayback(
        expectedGeneration: Long,
        expectedAttempt: Long,
        engine: RingPlaybackEngine,
        fallback: Rung?,
    ) {
        if (generation != expectedGeneration || activeAttempt != expectedAttempt || activeEngine !== engine) return
        if (runCatching { engine.isPlaying() }.getOrDefault(false)) return
        lastFailure = "notPlaying:${engine.name}"
        ringLog.log(
            CoreLogLevel.INFO,
            "ring",
            "RingtonePlayer.verifyPlayback",
            activeCallId,
            "${engine.name} did not enter playing state — advancing fallback",
        )
        releaseActiveEngine()
        advance(fallback)
    }

    private fun Rung.after(): Rung? =
        when (this) {
            Rung.PRIMARY -> Rung.RINGTONE
            Rung.RINGTONE -> Rung.BUNDLED
            Rung.BUNDLED -> null
        }

    private fun sourceKind(uri: Uri): String =
        when (uri.scheme) {
            "content" -> "content"
            "android.resource" -> "raw"
            "file" -> "file"
            else -> "other"
        }

    private fun emitState(state: RingAudioState) {
        val listener = stateListener ?: return
        postToOwner { listener(state) }
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
        val expectedGeneration = generation
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(ringAttributes)
                // Listener ALWAYS set (§8.4): loss → stop the sound, keep state.
                .setOnAudioFocusChangeListener { change ->
                    onAudioFocusChange(change, callId, expectedGeneration)
                }
                .build()
        focusRequest = request
        val granted = audioManager.requestAudioFocus(request)
        lastFocusResult =
            when (granted) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "failed"
                else -> "unknown:$granted"
            }
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            ringLog.log(
                CoreLogLevel.INFO,
                "ring",
                "RingtonePlayer.focus",
                callId,
                "focus_not_granted_playing_anyway",
                mapOf("focusResult" to lastFocusResult),
            )
        }
    }

    private fun captureAudioSnapshot(): Map<String, String> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode =
            when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_SILENT -> "silent"
                else -> "unknown:${audioManager.ringerMode}"
            }
        return mapOf(
            "ringerMode" to ringerMode,
            "ringVolume" to audioManager.getStreamVolume(AudioManager.STREAM_RING).toString(),
            "ringVolumeMax" to audioManager.getStreamMaxVolume(AudioManager.STREAM_RING).toString(),
            "ringMuted" to audioManager.isStreamMute(AudioManager.STREAM_RING).toString(),
        )
    }

    @Synchronized
    internal fun onAudioFocusChange(
        change: Int,
        callId: String,
        expectedGeneration: Long = generation,
    ) {
        if (generation != expectedGeneration || activeCallId != callId) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.focus", callId, "focus_loss_stop")
                stopAudioOnly()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.focus", callId, "focus_duck_keep_playing")
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

    @Synchronized
    private fun stopAudioOnly() {
        generation++
        releaseActiveEngine()
        activeCallId = null
        activeSource = null
        nextRung = null
        emitState(RingAudioState.Stopped)
        stateListener = null
        abandonFocus()
    }

    private fun releaseActiveEngine() {
        verification?.cancel()
        verification = null
        activeEngine?.let { runCatching { it.stop() } }
        activeEngine = null
    }

    private companion object {
        const val PLAYBACK_VERIFY_DELAY_MS = 300L
    }
}
