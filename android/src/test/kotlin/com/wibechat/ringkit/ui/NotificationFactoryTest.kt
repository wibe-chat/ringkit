package com.wibechat.ringkit.ui

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.core.CallRecord
import com.wibechat.ringkit.core.CoreAccentColors
import com.wibechat.ringkit.core.CoreCallInfo
import com.wibechat.ringkit.core.CoreCallOptions
import com.wibechat.ringkit.core.CoreCallDirection
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.CoreCallType
import com.wibechat.ringkit.core.CoreCancelReason
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreLockScreenBehavior
import com.wibechat.ringkit.core.CoreMissedCallOptions
import com.wibechat.ringkit.core.CoreNotificationVisibility
import com.wibechat.ringkit.core.FakeTime
import com.wibechat.ringkit.core.RingKitIntents
import com.wibechat.ringkit.core.RingLog
import com.wibechat.ringkit.core.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * §8.2 factory: channels + read-back, CallStyle validity invariant incl.
 * always-FSI (r18/r33), notificationOnly single exception, IAE → non-CallStyle
 * fallback, PendingIntent flags incl. FLAG_RECEIVER_FOREGROUND (r39),
 * missed-call copy, small-icon resolution.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val time = FakeTime()

    private fun ringLog() = RingLog(TestSupport.newTempDir(), time) { _, _ -> }

    private fun factory(
        config: CoreConfig = CoreConfig(),
        notifier: ((Int, Notification) -> Unit)? = null,
    ) = CallNotificationFactory(context, config, time, ringLog(), { 424200 }, notifier)

    private fun record(
        lockScreenBehavior: CoreLockScreenBehavior = CoreLockScreenBehavior.FULL_SCREEN,
        type: CoreCallType = CoreCallType.AUDIO,
        colors: CoreAccentColors = CoreAccentColors(),
        state: CoreCallState = CoreCallState.RINGING,
        connectedAtWallMs: Long? = null,
    ): CallRecord =
        CallRecord(
            info =
                CoreCallInfo(
                    id = "call-1", name = "Alice", handle = "+15550100", avatarUrl = null,
                    type = type, direction = CoreCallDirection.INCOMING, extra = emptyMap(),
                ),
            state = state,
            options =
                CoreCallOptions(
                    colors = colors,
                    lockScreenVisibility = CoreNotificationVisibility.PUBLIC,
                    lockScreenBehavior = lockScreenBehavior,
                ),
            timeoutSeconds = 45,
            shownAtWallMs = time.wall,
            shownAtElapsedMs = time.elapsed,
            bootCount = time.boot,
            ringDeadlineElapsedMs = time.elapsed + 45_000L,
            connectionDeadlineElapsedMs = null,
            surfacePosted = false,
            connectedAtWallMs = connectedAtWallMs,
        )

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ── channels (§8.2) ──────────────────────────────────────────────────

    @Test
    fun channels_are_created_with_versioned_ids_and_correct_importance() {
        val readback = factory().ensureChannels()

        val incoming = notificationManager.getNotificationChannel("ringkit_incoming_v1")
        val audible = notificationManager.getNotificationChannel("ringkit_incoming_audible_v1")
        val ongoing = notificationManager.getNotificationChannel("ringkit_ongoing_v1")
        val missed = notificationManager.getNotificationChannel("ringkit_missed_v1")

        assertNotNull(incoming)
        assertNotNull(audible)
        assertNotNull(ongoing)
        assertNotNull(missed)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, incoming.importance)
        // Incoming is SILENT at channel level — we own sound + vibration (§8.2).
        assertNull(incoming.sound)
        assertFalse(incoming.shouldVibrate())
        // Audible fallback carries the device default ringtone as channel sound.
        assertNotNull(audible.sound)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, audible.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ongoing.importance)

        assertTrue(readback.notificationsEnabled)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, readback.incomingImportance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, readback.audibleImportance)
    }

    @Test
    fun channel_suffix_versions_every_id() {
        factory(CoreConfig(channelSuffix = ".x")).ensureChannels()
        assertNotNull(notificationManager.getNotificationChannel("ringkit_incoming_v1.x"))
        assertNotNull(notificationManager.getNotificationChannel("ringkit_incoming_audible_v1.x"))
    }

    // ── validity invariant (§8.2; CLAUDE.md never-regress #1) ────────────

    @Test
    fun r18_r33_fsi_is_always_attached_in_fullScreen_mode() {
        factory().ensureChannels()
        val notification = factory().buildIncoming(record(), attachFsi = true)
        assertNotNull("FSI PendingIntent must ALWAYS be attached", notification.fullScreenIntent)
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun r18_r33_notificationOnly_fgs_owner_is_the_single_fsi_exception() {
        factory().ensureChannels()
        val owner =
            factory().buildIncoming(record(CoreLockScreenBehavior.NOTIFICATION_ONLY), attachFsi = false)
        assertNull("FGS-posted owner in notificationOnly mode omits the FSI", owner.fullScreenIntent)

        // Every OTHER build — including notificationOnly posted via notify() —
        // carries the FSI (validity without FGS attachment).
        val nonOwner =
            factory().buildIncoming(record(CoreLockScreenBehavior.NOTIFICATION_ONLY), attachFsi = true)
        assertNotNull(nonOwner.fullScreenIntent)
    }

    @Test
    fun incoming_carries_content_tap_delete_intent_and_timeout() {
        factory().ensureChannels()
        val notification = factory().buildIncoming(record())
        assertNotNull("content tap opens IncomingCallActivity — never implicit accept", notification.contentIntent)
        assertNotNull("delete-PI routes TIMEOUT (degraded no-FGS mode)", notification.deleteIntent)
        assertTrue("setTimeoutAfter must carry the remaining ring window", notification.timeoutAfter > 0)
        val shadowContent = shadowOf(notification.contentIntent)
        assertEquals(
            IncomingCallActivity::class.java.name,
            shadowContent.savedIntent.component?.className,
        )
    }

    @Test
    fun r39_decline_and_delete_intents_set_flag_receiver_foreground() {
        factory().ensureChannels()
        val notification = factory().buildIncoming(record())
        val delete = shadowOf(notification.deleteIntent).savedIntent
        assertTrue(
            "R39: every receiver PI sets FLAG_RECEIVER_FOREGROUND",
            delete.flags and Intent.FLAG_RECEIVER_FOREGROUND != 0,
        )
        assertEquals(RingKitIntents.ACTION_TIMEOUT, delete.action)
        assertEquals("com.wibechat.ringkit.core.CallActionReceiver", delete.component?.className)
    }

    @Test
    fun call_style_actions_route_decline_to_receiver_and_accept_to_trampoline() {
        factory().ensureChannels()
        val notification = factory().buildIncoming(record())
        // Compat CallStyle renders decline/answer as actions on SDK < 31 and
        // keeps them in the actions array on 31+ too.
        val actions = notification.actions
        assertNotNull(actions)
        val intents = actions.map { shadowOf(it.actionIntent).savedIntent }
        assertTrue(
            "decline action → CallActionReceiver with FLAG_RECEIVER_FOREGROUND (r39)",
            intents.any {
                it.action == RingKitIntents.ACTION_DECLINE &&
                    it.component?.className == "com.wibechat.ringkit.core.CallActionReceiver" &&
                    it.flags and Intent.FLAG_RECEIVER_FOREGROUND != 0
            },
        )
        assertTrue(
            "accept action → AcceptTrampolineActivity (activity PI)",
            intents.any {
                it.action == RingKitIntents.ACTION_ACCEPT &&
                    it.component?.className == AcceptTrampolineActivity::class.java.name
            },
        )
    }

    @Test
    fun ia_exception_falls_back_to_non_callstyle_with_actions() {
        factory().ensureChannels()
        var posted: Notification? = null
        var callStyleAttempts = 0
        val throwingOnCallStyle: (Int, Notification) -> Unit = { _, notification ->
            val template = notification.extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
            if (template.contains("CallStyle")) {
                callStyleAttempts++
                throw IllegalArgumentException("simulated OEM CallStyle rejection")
            }
            posted = notification
        }
        val outcome = factory(notifier = throwingOnCallStyle).postIncoming(record())

        assertTrue(outcome.posted)
        assertTrue("must report callStyleRejected (§8.11)", outcome.callStyleRejected)
        assertTrue(callStyleAttempts >= 1)
        val fallback = requireNotNull(posted)
        assertNotNull("fallback keeps the FSI (validity)", fallback.fullScreenIntent)
        assertEquals(Notification.CATEGORY_CALL, fallback.category)
        assertEquals("fallback carries accept+decline actions", 2, fallback.actions?.size)
    }

    // ── missed call (§8.2/§8.10) ─────────────────────────────────────────

    @Test
    fun missed_call_copy_maps_cancel_reasons() {
        factory().ensureChannels()
        val info = record().info
        fun titleFor(reason: CoreCancelReason?): String {
            val notification = factory().buildMissed(info, reason, CoreMissedCallOptions(), 424200)
            return notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
        }
        assertEquals("Missed call", titleFor(null))
        assertEquals("Missed call", titleFor(CoreCancelReason.CALLER_CANCELLED))
        assertEquals("Answered on another device", titleFor(CoreCancelReason.ANSWERED_ELSEWHERE))
        assertEquals("Declined on another device", titleFor(CoreCancelReason.DECLINED_ELSEWHERE))
    }

    @Test
    fun missed_call_tap_targets_both_route_through_trampoline() {
        factory().ensureChannels()
        val notification =
            factory().buildMissed(record().info, null, CoreMissedCallOptions(showCallbackAction = true), 424200)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val content = shadowOf(notification.contentIntent).savedIntent
        assertEquals(RingKitIntents.ACTION_MISSED_TAP, content.action)
        assertEquals(AcceptTrampolineActivity::class.java.name, content.component?.className)
        val callback = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(RingKitIntents.ACTION_MISSED_TAP, callback.action)
        assertEquals(AcceptTrampolineActivity::class.java.name, callback.component?.className)
    }

    @Test
    fun missed_call_custom_copy_and_callback_opt_out() {
        factory().ensureChannels()
        val custom =
            factory().buildMissed(
                record().info,
                CoreCancelReason.ANSWERED_ELSEWHERE,
                CoreMissedCallOptions(
                    title = "You missed Alice",
                    subtitle = "Tap to call back",
                    showCallbackAction = false,
                ),
                424200,
            )
        assertEquals(
            "custom title beats the reason copy",
            "You missed Alice", custom.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals("Tap to call back", custom.extras.getString(Notification.EXTRA_TEXT))
        assertTrue("showCallbackAction=false ⇒ no actions", custom.actions.isNullOrEmpty())

        val labelled =
            factory().buildMissed(
                record().info, null,
                CoreMissedCallOptions(callbackText = "Ring again"), 424200,
            )
        assertEquals("Ring again", labelled.actions.single().title)
    }

    // ── in-call surfaces (§8.2; PLAN v1.3.4) ─────────────────────────────

    @Test
    fun ongoing_fallback_is_non_callstyle_with_receiver_hang_up() {
        factory().ensureChannels()
        val notification = factory().buildOngoingFallback(record(state = CoreCallState.ACCEPTED))
        val template = notification.extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        assertFalse("opt-out surface must NOT be CallStyle (no FGS ⇒ no validity)", template.contains("CallStyle"))
        assertEquals("Connecting…", notification.extras.getString(Notification.EXTRA_TEXT))
        val hangUp = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(RingKitIntents.ACTION_HANG_UP, hangUp.action)
        assertTrue(hangUp.flags and Intent.FLAG_RECEIVER_FOREGROUND != 0)
    }

    @Test
    fun ongoing_surfaces_carry_state_content() {
        factory().ensureChannels()
        val outgoing = factory().buildOngoingFallback(record(state = CoreCallState.OUTGOING))
        assertEquals("Calling…", outgoing.extras.getString(Notification.EXTRA_TEXT))

        val active = factory().buildOngoingFallback(record(state = CoreCallState.ACTIVE, connectedAtWallMs = 1_751_700_777_000L))
        assertTrue(active.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("chronometer base = connect time", 1_751_700_777_000L, active.`when`)
    }

    @Test
    fun ongoing_callstyle_build_has_no_fsi() {
        factory().ensureChannels()
        val notification = factory().buildOngoing(record(state = CoreCallState.ACCEPTED))
        assertNull("ongoing surface never carries an FSI", notification.fullScreenIntent)
        assertEquals("Connecting…", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    // ── A16 promoted ongoing (§8.3; M5.6) ────────────────────────────────
    // Robolectric caps at SDK 35 (4.15.1: "API level 36 is not available"), so
    // the unit suite proves the ≤35 side of the version gate + the colorized
    // guardrail; the API-36 promoted render (request + chip, dumpsys-visible)
    // is the emulator leg — evidence/m5/promoted-ongoing.md.

    @Test
    @Config(sdk = [35])
    fun promoted_ongoing_below_36_is_not_requested_and_never_colorized() {
        factory().ensureChannels()
        val notification = factory().buildOngoing(record(state = CoreCallState.ACTIVE, connectedAtWallMs = 1L))
        assertFalse(
            "≤35 keeps the pre-A16 surface — no promotion request",
            androidx.core.app.NotificationCompat.isRequestPromotedOngoing(notification),
        )
        assertNull(androidx.core.app.NotificationCompat.getShortCriticalText(notification))
        // Never-regress guardrail (§8.3/D2): the ongoing build must never carry
        // colorized — promotion and colorized are mutually exclusive on 36, and
        // the guarantee is that this surface NEVER sets it at any level.
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_COLORIZED, false))
    }

    // ── small icon (§8.2) ────────────────────────────────────────────────

    @Test
    fun unresolvable_icon_override_falls_back_to_plugin_glyph() {
        CallNotificationFactory.resetIconCacheForTests()
        factory(CoreConfig(notificationIcon = "does_not_exist")).ensureChannels()
        val notification =
            factory(CoreConfig(notificationIcon = "does_not_exist")).buildIncoming(record())
        assertEquals(com.wibechat.ringkit.R.drawable.ringkit_ic_call, notification.smallIcon.resId)
        CallNotificationFactory.resetIconCacheForTests()
    }
}
