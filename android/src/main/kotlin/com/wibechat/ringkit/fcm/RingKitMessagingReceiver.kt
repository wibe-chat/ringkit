package com.wibechat.ringkit.fcm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.RemoteMessage
import com.wibechat.ringkit.core.RingKitNative

/**
 * Mode 2 native FCM fast path (PLAN §7.3, v1.3.9). An **additive** `c2dm.intent.RECEIVE`
 * broadcast receiver: it runs *in parallel with* firebase_messaging's own
 * `FlutterFirebaseMessagingReceiver` (Play Services broadcasts to every registered
 * receiver) and **never registers for `MESSAGING_EVENT`** — so firebase_messaging keeps
 * winning that service and its whole pipeline (message delivery to Dart AND `onNewToken`
 * token refresh) stays 100 % intact, with nothing forwarded or intercepted.
 *
 * Chosen over a `FirebaseMessagingService` subclass because the winning `MESSAGING_EVENT`
 * service also owns `onNewToken`, and firebase_messaging's Dart token stream is fed solely
 * by *its* service's `onNewToken` (no receiver fallback — source-verified): stealing the
 * service would silently kill the app's token refresh (PLAN §7.3 "Why a receiver…").
 *
 * Ships `android:enabled="false"`; the app opts in with a one-line manifest override
 * (`tools:replace="android:enabled"`, §7.3). Necessarily `exported="true"` (the FCM
 * broadcast is cross-process from GMS) but gated by the signature-level
 * `com.google.android.c2dm.permission.SEND` — GMS-only, so no third-party actor can drive
 * it (the sole, documented carve-out to the "every component exported=false" invariant,
 * R25). `firebase-messaging` is `compileOnly` (only `RemoteMessage` is referenced).
 */
class RingKitMessagingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val message = RemoteMessage(extras)
        val data = message.data
        // Not a RingKit push ⇒ return immediately: firebase_messaging's own receiver
        // already delivered it to the app's onMessage/onBackgroundMessage handlers.
        if (!isRingKitMessage(data)) return

        // Boundary trace (Mode 2 native path observability — this receiver runs
        // before core may be warm; the ring-trace/RingLog picks up from deliverPayload).
        Log.i("RingKit", "[fcm] rk.* c2dm push received (Mode 2) → native deliver, keys=${data.keys}")

        // rk.* push: hand to the transport-agnostic native entry. goAsync()'s
        // PendingResult is the M2 lifetime anchor (PLAN §7.3, §10.2) — held across
        // deliverPayload's async show so the OS keeps this (possibly cold) process alive
        // and the high-priority-FCM background FGS-start exemption spans the ring.
        // getSentTime() is 0 when the server didn't stamp it → prefer rk.sentAt (null here).
        val pending = goAsync()
        RingKitNative.deliverPayload(
            context.applicationContext,
            data,
            serverSentAtMs = message.sentTime.takeIf { it > 0L },
            fcmDowngraded = isDowngraded(message.priority, message.originalPriority),
            onProcessed = { pending.finish() },
        )
    }

    companion object {
        /**
         * True iff the payload carries any `rk.*` key (§7.5) — i.e. it's a RingKit
         * `incoming`/`cancel` push handled natively, not one of the app's own messages.
         * Mirrors the Dart-side `RingKit.isRingMessage(data)` guard the Mode-2 app uses
         * to skip `rk.*` in its background handler (rk co-delivers via FlutterFire's
         * receiver and cannot be suppressed — §7.3).
         */
        internal fun isRingKitMessage(data: Map<String, String>): Boolean =
            data.keys.any { it.startsWith("rk.") }

        /**
         * True iff FCM downgraded this push — the server requested HIGH priority but it
         * was delivered at NORMAL (§8.11 `fcmDowngraded`, R28 de-prioritization), silently
         * forfeiting the killed-state FGS-start exemption. NOTE the FCM enum is inverted
         * (`PRIORITY_HIGH=1 < PRIORITY_NORMAL=2`), so a downgrade is a numerically *higher*
         * delivered priority — `delivered < original` would be backwards. UNKNOWN (0) on
         * either side is never a downgrade (ambiguous → don't false-flag).
         */
        internal fun isDowngraded(deliveredPriority: Int, originalPriority: Int): Boolean =
            originalPriority == RemoteMessage.PRIORITY_HIGH &&
                deliveredPriority == RemoteMessage.PRIORITY_NORMAL
    }
}
