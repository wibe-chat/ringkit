package com.wibechat.ringkit.core

import android.content.Intent
import android.net.Uri

/**
 * The intent contract between notifications/PendingIntents and components
 * (PLAN §8.1/§8.2). Every intent is EXPLICIT (component-addressed — nothing is
 * exported) and carries a per-call data URI so PendingIntents for different
 * calls never collide into one another (FLAG_UPDATE_CURRENT matches on
 * Intent#filterEquals, which includes the data URI).
 */
object RingKitIntents {

    const val ACTION_RING = "com.wibechat.ringkit.RING"
    const val ACTION_SILENCE = "com.wibechat.ringkit.SILENCE"
    const val ACTION_REEVALUATE = "com.wibechat.ringkit.REEVALUATE"
    const val ACTION_ACCEPT = "com.wibechat.ringkit.ACCEPT"
    const val ACTION_DECLINE = "com.wibechat.ringkit.DECLINE"
    const val ACTION_TIMEOUT = "com.wibechat.ringkit.TIMEOUT"
    const val ACTION_HANG_UP = "com.wibechat.ringkit.HANG_UP"
    const val ACTION_MISSED_TAP = "com.wibechat.ringkit.MISSED_TAP"
    const val ACTION_SHOW_FULL_SCREEN = "com.wibechat.ringkit.SHOW_FULL_SCREEN"

    const val EXTRA_CALL_ID = "ringkit.callId"

    /** Stable per-call identity for Intent#filterEquals disambiguation. */
    fun callUri(callId: String): Uri = Uri.parse("ringkit://call/${Uri.encode(callId)}")

    fun withCall(intent: Intent, action: String, callId: String): Intent =
        intent
            .setAction(action)
            .setData(callUri(callId))
            .putExtra(EXTRA_CALL_ID, callId)

    fun callIdOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA_CALL_ID)
}
