package com.wibechat.ringkit.fcm

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mode 2 native FCM receiver (PLAN §7.3, v1.3.9). The full onReceive → RemoteMessage
 * → deliverPayload → ring path is device-verified (real FCM, §7.3 exit); these unit
 * tests pin the two things that are pure logic + declaration:
 *  1. the `rk.*` detection predicate (which pushes RingKit consumes vs. lets through), and
 *  2. the manifest security carve-out — that the sole exported component is exported
 *     ONLY behind the signature-level c2dm SEND permission and ships DISABLED.
 */
@RunWith(RobolectricTestRunner::class)
class RingKitMessagingReceiverTest {

    // --- §7.5 rk.* detection (mirrors the Dart RingKit.isRingMessage guard) ---

    @Test
    fun incoming_push_is_ringkit() {
        assertTrue(
            RingKitMessagingReceiver.isRingKitMessage(mapOf("rk.type" to "incoming", "rk.callId" to "c1")),
        )
    }

    @Test
    fun cancel_push_is_ringkit() {
        assertTrue(
            RingKitMessagingReceiver.isRingKitMessage(mapOf("rk.type" to "cancel", "rk.callId" to "c1")),
        )
    }

    @Test
    fun any_rk_key_counts_forward_compatible() {
        assertTrue(RingKitMessagingReceiver.isRingKitMessage(mapOf("rk.futureKey" to "x")))
    }

    @Test
    fun app_own_message_is_not_ringkit() {
        assertFalse(RingKitMessagingReceiver.isRingKitMessage(mapOf("chatId" to "42", "type" to "message")))
    }

    @Test
    fun empty_data_is_not_ringkit() {
        assertFalse(RingKitMessagingReceiver.isRingKitMessage(emptyMap()))
    }

    @Test
    fun key_merely_containing_rk_is_not_matched() {
        // startsWith("rk."), not contains — app keys like "work.rk"/"sparkId" must not
        // false-positive and get swallowed from the app's own handler.
        assertFalse(RingKitMessagingReceiver.isRingKitMessage(mapOf("work.rk" to "x", "sparkId" to "y")))
    }

    // --- Manifest carve-out to the never-export invariant (R25, CLAUDE rule 8) ---

    @Test
    @Suppress("DEPRECATION") // int-flags overload: the ComponentInfoFlags form is API 33+
    fun receiver_ships_disabled_exported_only_behind_signature_permission() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, RingKitMessagingReceiver::class.java),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        // Ships disabled — RingKit intercepts NO FCM until the app opts into Mode 2.
        assertFalse("receiver must ship android:enabled=false (opt-in)", info.enabled)
        // Exported: mandatory to receive Play Services' cross-process FCM broadcast.
        assertTrue("c2dm receiver must be exported", info.exported)
        // …but ONLY behind the signature-level c2dm SEND permission (GMS-only) — this is
        // what preserves R25's 'no third-party actor can drive it' intent despite export.
        assertEquals(
            "exported receiver MUST be gated by the signature-level c2dm.permission.SEND",
            "com.google.android.c2dm.permission.SEND",
            info.permission,
        )
    }
}
