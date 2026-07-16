package com.wibechat.ringkit.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.wibechat.ringkit.R
import com.wibechat.ringkit.core.CallActionReceiver
import com.wibechat.ringkit.core.CallRecord
import com.wibechat.ringkit.core.CoreCallInfo
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.CoreCallType
import com.wibechat.ringkit.core.CoreCancelReason
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreLockScreenBehavior
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreMissedCallOptions
import com.wibechat.ringkit.core.CoreNotificationVisibility
import com.wibechat.ringkit.core.RingKitIntents
import com.wibechat.ringkit.core.RingLog
import com.wibechat.ringkit.core.TimeSource

/** Versioned channel ids (§8.2, §6.4): immutable once created; suffix = escape hatch. */
object RingKitChannels {
    fun incoming(suffix: String) = "ringkit_incoming_v1$suffix"

    fun incomingAudible(suffix: String) = "ringkit_incoming_audible_v1$suffix"

    fun ongoing(suffix: String) = "ringkit_ongoing_v1$suffix"

    fun missed(suffix: String) = "ringkit_missed_v1$suffix"
}

/**
 * Builds and posts every RingKit notification (PLAN §8.2). One posting path:
 * [postIncoming] wraps the CallStyle IllegalArgumentException corner and falls
 * back to a non-CallStyle high-priority build (`callStyleRejected`). The
 * ringing-FGS path builds via [buildIncoming] and posts through
 * `startForeground` (validity via FGS attachment) — the RingingService applies
 * the same IAE fallback there.
 *
 * FSI attachment (§8.2, R18/R33): ALWAYS attached in `fullScreen` mode —
 * every API level, regardless of permission state (denied ⇒ the system strips
 * it, flags FLAG_FSI_REQUESTED_BUT_DENIED — which itself satisfies CallStyle
 * validity — and applies the sanctioned 60 s pinned sticky-HUN). The single
 * exception: the FGS-posted owner notification in `notificationOnly` mode.
 */
class CallNotificationFactory(
    private val context: Context,
    private val config: CoreConfig,
    private val time: TimeSource,
    private val ringLog: RingLog,
    /** Stable §5.2 registry lookup — the orchestrator passes repository::notificationIdFor. */
    private val idProvider: (String) -> Int?,
    /** Seam: Robolectric's notify never throws — the IAE fallback tests inject. */
    private val notifier: ((Int, Notification) -> Unit)? = null,
) {
    companion object {
        /**
         * Missed-call notifications live in their own id band: the ringing FGS
         * detaches with STOP_FOREGROUND_REMOVE on its (call-id-keyed) foreground
         * notification, which would destroy a missed-call posted under the same
         * id moments earlier (found on-device, SM-G781B).
         */
        const val MISSED_ID_OFFSET = 100_000

        fun missedIdFor(notifId: Int): Int = notifId + MISSED_ID_OFFSET

        /** Loud-once cache for §8.2 small-icon resolution (unresolvable → glyph + error log). */
        @Volatile
        private var resolvedSmallIcon: Pair<String?, Int>? = null

        internal fun resetIconCacheForTests() {
            resolvedSmallIcon = null
        }
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ── channels (§8.2) ──────────────────────────────────────────────────

    /** Show-time channel read-back → §8.11 rows. */
    data class ChannelReadback(
        val notificationsEnabled: Boolean,
        val incomingImportance: Int,
        val audibleImportance: Int,
    )

    /**
     * Create-lazily + read back. Names refresh from config (allowed for
     * existing channels); importance/sound are immutable after creation —
     * user demotion is detected, reported, never programmatically re-raised.
     */
    fun ensureChannels(): ChannelReadback {
        val suffix = config.channelSuffix

        // Incoming: HIGH, SILENT at channel level — we own sound + vibration
        // (no double-vibration, no channel-cache staleness).
        val incoming =
            NotificationChannel(
                RingKitChannels.incoming(suffix),
                config.incomingChannelName,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }

        // Audible fallback: HIGH + the device default ringtone AS CHANNEL SOUND —
        // pre-created; used only by the §8.11 fgsDenied path (system renders
        // channel sound even if our process is frozen; plays once, not looped).
        val audible =
            NotificationChannel(
                RingKitChannels.incomingAudible(suffix),
                config.audibleChannelName,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
            }

        val ongoing =
            NotificationChannel(
                RingKitChannels.ongoing(suffix),
                config.ongoingChannelName,
                NotificationManager.IMPORTANCE_LOW,
            )

        val missed =
            NotificationChannel(
                RingKitChannels.missed(suffix),
                config.missedChannelName,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }

        notificationManager.createNotificationChannels(listOf(incoming, audible, ongoing, missed))

        fun importanceOf(id: String): Int =
            notificationManager.getNotificationChannel(id)?.importance ?: NotificationManager.IMPORTANCE_NONE

        return ChannelReadback(
            notificationsEnabled = notificationManager.areNotificationsEnabled(),
            incomingImportance = importanceOf(RingKitChannels.incoming(suffix)),
            audibleImportance = importanceOf(RingKitChannels.incomingAudible(suffix)),
        )
    }

    // ── incoming (§8.2) ──────────────────────────────────────────────────

    /**
     * The incoming CallStyle notification. [attachFsi] false ONLY for the
     * FGS-posted owner notification in `notificationOnly` mode (§8.2 validity
     * invariant). [onAudibleChannel] posts on the audible-fallback channel and
     * lets it alert (fgsDenied one-shot; §8.11).
     */
    fun buildIncoming(
        record: CallRecord,
        avatar: Bitmap? = null,
        attachFsi: Boolean = true,
        onAudibleChannel: Boolean = false,
    ): Notification {
        val info = record.info
        val notifId = requireNotNull(notificationIdHint(record.callId)) { "no registry id for ${record.callId}" }
        val channel =
            if (onAudibleChannel) RingKitChannels.incomingAudible(config.channelSuffix)
            else RingKitChannels.incoming(config.channelSuffix)

        // NB: CallStyle defines its own `apply(...)` member — Kotlin's scope
        // function is shadowed, so the style is configured imperatively.
        val style =
            NotificationCompat.CallStyle.forIncomingCall(
                person(info, avatar),
                declinePendingIntent(record.callId, notifId),
                acceptPendingIntent(record.callId, notifId),
            )
        style.setIsVideo(info.type == CoreCallType.VIDEO)
        record.options.colors.acceptArgb?.let { style.setAnswerButtonColorHint(it) }
        record.options.colors.declineArgb?.let { style.setDeclineButtonColorHint(it) }

        val builder =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(smallIcon())
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(!onAudibleChannel)
                .setWhen(record.shownAtWallMs)
                .setShowWhen(true)
                .setVisibility(visibility(record.options.lockScreenVisibility))
                .setContentTitle(info.name)
                .setContentText(incomingLabel(info.type))
                .setStyle(style)
                // Content tap = open the full-screen UI, user-initiated — NEVER
                // an implicit accept (§8.2, #361/#634). Works in both behaviors.
                .setContentIntent(fullScreenPendingIntent(record.callId, notifId, requestCodeSalt = 3))
                // Effective only in degraded no-FGS mode (AOSP exempts
                // FGS-attached notifications from timeout, §4.3/R32).
                .setTimeoutAfter(remainingRingMs(record))
                .setDeleteIntent(receiverPendingIntent(RingKitIntents.ACTION_TIMEOUT, record.callId, notifId, 4))

        record.options.colors.accentArgb?.let { builder.setColor(it) }

        if (attachFsi) {
            builder.setFullScreenIntent(fullScreenPendingIntent(record.callId, notifId, requestCodeSalt = 8), true)
        }
        return builder.build()
    }

    /**
     * §8.2 belt-and-braces: non-CallStyle high-priority fallback (same channel,
     * CATEGORY_CALL, accept/decline actions, colorized) for OEM CallStyle
     * rejections on 12–13 where the denied-flag mechanism doesn't exist.
     */
    fun buildIncomingFallback(record: CallRecord, attachFsi: Boolean = true): Notification {
        val info = record.info
        val notifId = requireNotNull(notificationIdHint(record.callId)) { "no registry id for ${record.callId}" }
        val builder =
            NotificationCompat.Builder(context, RingKitChannels.incoming(config.channelSuffix))
                .setSmallIcon(smallIcon())
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(record.shownAtWallMs)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(visibility(record.options.lockScreenVisibility))
                .setContentTitle(info.name)
                .setContentText(incomingLabel(info.type))
                .setColorized(true)
                .setColor(record.options.colors.accentArgb ?: 0xFF1B5E20.toInt())
                .addAction(0, context.getString(R.string.ringkit_action_decline), declinePendingIntent(record.callId, notifId))
                .addAction(0, context.getString(R.string.ringkit_action_accept), acceptPendingIntent(record.callId, notifId))
                .setContentIntent(fullScreenPendingIntent(record.callId, notifId, requestCodeSalt = 3))
                .setTimeoutAfter(remainingRingMs(record))
                .setDeleteIntent(receiverPendingIntent(RingKitIntents.ACTION_TIMEOUT, record.callId, notifId, 4))
        if (attachFsi) {
            builder.setFullScreenIntent(fullScreenPendingIntent(record.callId, notifId, requestCodeSalt = 8), true)
        }
        return builder.build()
    }

    data class PostOutcome(val posted: Boolean, val callStyleRejected: Boolean)

    /**
     * The single notify() posting path (§8.2): CallStyle first; on
     * IllegalArgumentException re-post the non-CallStyle fallback and report
     * `callStyleRejected`. (The FGS path posts via startForeground and applies
     * the same fallback in RingingService.)
     */
    fun postIncoming(
        record: CallRecord,
        avatar: Bitmap? = null,
        attachFsi: Boolean = true,
        onAudibleChannel: Boolean = false,
    ): PostOutcome {
        val notifId = notificationIdHint(record.callId) ?: return PostOutcome(posted = false, callStyleRejected = false)
        val post = notifier ?: { id, notification -> notificationManager.notify(id, notification) }
        return try {
            post(notifId, buildIncoming(record, avatar, attachFsi, onAudibleChannel))
            PostOutcome(posted = true, callStyleRejected = false)
        } catch (e: IllegalArgumentException) {
            ringLog.incident(
                RingLog.IncidentType.CALLSTYLE_REJECTED,
                "CallNotificationFactory.postIncoming",
                record.callId,
                degradations = setOf("callStyleRejected"),
                throwable = e,
            )
            return try {
                post(notifId, buildIncomingFallback(record, attachFsi))
                PostOutcome(posted = true, callStyleRejected = true)
            } catch (e2: Exception) {
                ringLog.log(
                    CoreLogLevel.ERROR, "notify", "CallNotificationFactory.postIncoming", record.callId,
                    "fallback post failed", throwable = e2,
                )
                PostOutcome(posted = false, callStyleRejected = true)
            }
        }
    }

    // ── in-call surfaces (§8.2: ACCEPTED "Connecting…" / ACTIVE chronometer /
    //    OUTGOING "Calling…"; hang-up → receiver) ──────────────────────────

    /**
     * CallStyle `forOngoingCall` build — posted ONLY via the
     * OngoingCallService's startForeground (validity by FGS-attachment,
     * PLAN v1.3.4). The service applies the same IAE fallback the ringing
     * path does.
     */
    fun buildOngoing(record: CallRecord, avatar: Bitmap? = null): Notification {
        val notifId = requireNotNull(notificationIdHint(record.callId)) { "no registry id for ${record.callId}" }
        val style =
            NotificationCompat.CallStyle.forOngoingCall(
                person(record.info, avatar),
                receiverPendingIntent(RingKitIntents.ACTION_HANG_UP, record.callId, notifId, 7),
            )
        style.setIsVideo(record.info.type == CoreCallType.VIDEO)
        val builder =
            NotificationCompat.Builder(context, RingKitChannels.ongoing(config.channelSuffix))
                .setSmallIcon(smallIcon())
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(visibility(record.options.lockScreenVisibility))
                .setContentTitle(record.info.name)
                .setStyle(style)
        record.options.colors.accentArgb?.let { builder.setColor(it) }
        // §8.3 A16 promoted/live-update ongoing (36+): opt-in request + status-bar
        // chip. Best-effort — the user can revoke POST_PROMOTED_NOTIFICATIONS via
        // the Live Updates toggle, so promotion is never load-bearing. NEVER
        // combined with setColorized (Live-Updates eligibility; never-regress).
        // Only this CallStyle build requests it: the non-CallStyle fallback below
        // lacks a promotable style, so a request there would be a silent lie.
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(context.getString(R.string.ringkit_chip_call))
        }
        applyOngoingStateContent(builder, record)
        return builder.build()
    }

    /**
     * Non-CallStyle in-call surface: the `manageOngoingCall: false` path (no
     * FGS ⇒ CallStyle validity unavailable, PLAN v1.3.4) and the service's IAE
     * fallback. Same state-based content, explicit hang-up action.
     */
    fun buildOngoingFallback(record: CallRecord): Notification {
        val notifId = requireNotNull(notificationIdHint(record.callId)) { "no registry id for ${record.callId}" }
        val builder =
            NotificationCompat.Builder(context, RingKitChannels.ongoing(config.channelSuffix))
                .setSmallIcon(smallIcon())
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentTitle(record.info.name)
                .setVisibility(visibility(record.options.lockScreenVisibility))
                .addAction(
                    0,
                    context.getString(R.string.ringkit_action_hang_up),
                    receiverPendingIntent(RingKitIntents.ACTION_HANG_UP, record.callId, notifId, 7),
                )
        applyOngoingStateContent(builder, record)
        return builder.build()
    }

    /** ACCEPTED → "Connecting…" · OUTGOING → "Calling…" · ACTIVE → chronometer (base = connect time). */
    private fun applyOngoingStateContent(builder: NotificationCompat.Builder, record: CallRecord) {
        when (record.state) {
            CoreCallState.ACTIVE -> {
                builder.setUsesChronometer(true)
                builder.setWhen(record.connectedAtWallMs ?: time.wallMs())
                builder.setShowWhen(true)
            }
            CoreCallState.OUTGOING -> builder.setContentText(context.getString(R.string.ringkit_calling))
            else -> builder.setContentText(context.getString(R.string.ringkit_connecting))
        }
    }

    fun postOngoingFallback(record: CallRecord) {
        val notifId = notificationIdHint(record.callId) ?: return
        runCatching { notificationManager.notify(notifId, buildOngoingFallback(record)) }
            .onFailure {
                ringLog.log(
                    CoreLogLevel.ERROR, "notify", "CallNotificationFactory.postOngoingFallback",
                    record.callId, "post failed", throwable = it,
                )
            }
    }

    // ── missed call (§8.2) ───────────────────────────────────────────────

    /**
     * Missed-call surface. Both tap targets (content + "Call back") route
     * through the trampoline, which auto-cancels this notification FIRST, then
     * emits MissedCallTapped + launch action (§4.3, #496/#562).
     */
    fun buildMissed(info: CoreCallInfo, reason: CoreCancelReason?, options: CoreMissedCallOptions, notifId: Int): Notification {
        val title = options.title ?: missedCopy(reason)
        val builder =
            NotificationCompat.Builder(context, RingKitChannels.missed(config.channelSuffix))
                .setSmallIcon(smallIcon())
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setAutoCancel(true)
                .setWhen(time.wallMs())
                .setShowWhen(true)
                .setContentTitle(title)
                .setContentText(options.subtitle ?: info.name)
                .setContentIntent(missedTapPendingIntent(info.id, notifId, 5))
        if (options.showCallbackAction) {
            builder.addAction(
                0,
                options.callbackText ?: context.getString(R.string.ringkit_action_call_back),
                missedTapPendingIntent(info.id, notifId, 6),
            )
        }
        return builder.build()
    }

    fun postMissed(info: CoreCallInfo, reason: CoreCancelReason?, options: CoreMissedCallOptions, notifId: Int) {
        // Own id band — never the call's id (the FGS REMOVE would eat it).
        val missedId = missedIdFor(notifId)
        runCatching { notificationManager.notify(missedId, buildMissed(info, reason, options, missedId)) }
            .onFailure {
                ringLog.log(
                    CoreLogLevel.ERROR, "notify", "CallNotificationFactory.postMissed",
                    info.id, "post failed", throwable = it,
                )
            }
    }

    fun cancel(notifId: Int) {
        runCatching { notificationManager.cancel(notifId) }
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun notificationIdHint(callId: String): Int? = idProvider(callId)

    private fun missedCopy(reason: CoreCancelReason?): String =
        when (reason) {
            CoreCancelReason.ANSWERED_ELSEWHERE -> context.getString(R.string.ringkit_missed_answered_elsewhere)
            CoreCancelReason.DECLINED_ELSEWHERE -> context.getString(R.string.ringkit_missed_declined_elsewhere)
            else -> context.getString(R.string.ringkit_missed_call)
        }

    private fun incomingLabel(type: CoreCallType): String =
        context.getString(
            if (type == CoreCallType.VIDEO) R.string.ringkit_incoming_video else R.string.ringkit_incoming_audio,
        )

    /** Person with tel:/custom URI for DND call-exception matching (§8.2). */
    private fun person(info: CoreCallInfo, avatar: Bitmap?): Person {
        val builder =
            Person.Builder()
                .setName(info.name)
                .setImportant(true)
                .setUri(info.handle?.let { "tel:$it" } ?: "ringkit:${info.id}")
        avatar?.let { builder.setIcon(IconCompat.createWithBitmap(it)) }
        return builder.build()
    }

    private fun visibility(v: CoreNotificationVisibility): Int =
        when (v) {
            CoreNotificationVisibility.PUBLIC -> NotificationCompat.VISIBILITY_PUBLIC
            CoreNotificationVisibility.PRIVATE -> NotificationCompat.VISIBILITY_PRIVATE
            CoreNotificationVisibility.SECRET -> NotificationCompat.VISIBILITY_SECRET
        }

    private fun remainingRingMs(record: CallRecord): Long {
        val deadline = record.ringDeadlineElapsedMs ?: return record.timeoutSeconds * 1000L
        return (deadline - time.elapsedMs()).coerceAtLeast(1_000L)
    }

    /**
     * §8.2 small icon: app override resolved once + cached; unresolvable is a
     * LOUD error (log + diagnostics finding in M3) falling back to the glyph.
     * Applied per-notification — never a global resource override (#600).
     */
    private fun smallIcon(): Int {
        val override = config.notificationIcon
        resolvedSmallIcon?.let { (cachedFor, id) -> if (cachedFor == override) return id }
        val resolved =
            if (override.isNullOrBlank()) {
                R.drawable.ringkit_ic_call
            } else {
                val id = context.resources.getIdentifier(override, "drawable", context.packageName)
                if (id != 0) {
                    id
                } else {
                    ringLog.log(
                        CoreLogLevel.ERROR, "config", "CallNotificationFactory.smallIcon", null,
                        "notificationIcon '$override' unresolvable — using plugin glyph",
                    )
                    R.drawable.ringkit_ic_call
                }
            }
        resolvedSmallIcon = override to resolved
        return resolved
    }

    // ── PendingIntents (§8.2): stable request codes derived from the registry id ──

    private fun pendingFlags(): Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** Accept → trampoline ACTIVITY PI (sanctioned from a notification action). */
    private fun acceptPendingIntent(callId: String, notifId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            notifId * 10 + 1,
            RingKitIntents.withCall(
                Intent(context, AcceptTrampolineActivity::class.java),
                RingKitIntents.ACTION_ACCEPT,
                callId,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingFlags(),
        )

    /** Decline → receiver PI; FLAG_RECEIVER_FOREGROUND on the intent (R39). */
    private fun declinePendingIntent(callId: String, notifId: Int): PendingIntent =
        receiverPendingIntent(RingKitIntents.ACTION_DECLINE, callId, notifId, 2)

    private fun receiverPendingIntent(action: String, callId: String, notifId: Int, salt: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notifId * 10 + salt,
            RingKitIntents.withCall(Intent(context, CallActionReceiver::class.java), action, callId)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            pendingFlags(),
        )

    private fun fullScreenPendingIntent(callId: String, notifId: Int, requestCodeSalt: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            notifId * 10 + requestCodeSalt,
            RingKitIntents.withCall(
                Intent(context, IncomingCallActivity::class.java),
                RingKitIntents.ACTION_SHOW_FULL_SCREEN,
                callId,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingFlags(),
        )

    private fun missedTapPendingIntent(callId: String, notifId: Int, salt: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            notifId * 10 + salt,
            RingKitIntents.withCall(
                Intent(context, AcceptTrampolineActivity::class.java),
                RingKitIntents.ACTION_MISSED_TAP,
                callId,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingFlags(),
        )
}
