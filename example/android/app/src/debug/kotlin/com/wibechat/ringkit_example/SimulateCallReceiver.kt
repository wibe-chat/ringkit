package com.wibechat.ringkit_example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wibechat.ringkit.core.RingKitNative

/**
 * DEBUG-ONLY adb trigger (CLAUDE.md cookbook; PLAN §15 "adb-only local
 * trigger"): converts `--es` extras into an `rk.*` data map and hands it to
 * RingKitNative — the SAME Mode-2 parser path an FCM payload takes, minus the
 * transport. Exists only in debug builds; the plugin itself never exports
 * anything (§8.1).
 *
 *   adb shell am broadcast -a com.wibechat.ringkit_example.SIMULATE_CALL \
 *       --es name Alice [--es handle +1555...] [--es callId x] \
 *       [--es callType video] [--es timeoutSeconds 30] [--es sentAt <ms>] \
 *       [--es avatarUrl https://…] [--es lockScreenBehavior notificationOnly]
 *   adb shell am broadcast -a com.wibechat.ringkit_example.SIMULATE_CANCEL \
 *       --es callId x [--es reason answeredElsewhere]
 */
class SimulateCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val data = mutableMapOf<String, String>()
        when (intent.action) {
            "com.wibechat.ringkit_example.SIMULATE_CALL" -> {
                data["rk.type"] = "incoming"
                data["rk.callId"] =
                    intent.getStringExtra("callId") ?: "sim-${System.currentTimeMillis()}"
                data["rk.callerName"] = intent.getStringExtra("name") ?: "Sim Caller"
                data["rk.sentAt"] = intent.getStringExtra("sentAt") ?: System.currentTimeMillis().toString()
                passThrough(intent, data, "handle", "rk.callerHandle")
                passThrough(intent, data, "avatarUrl", "rk.avatarUrl")
                passThrough(intent, data, "callType", "rk.callType")
                passThrough(intent, data, "timeoutSeconds", "rk.timeoutSeconds")
                passThrough(intent, data, "ringtone", "rk.ringtone")
                passThrough(intent, data, "vibrate", "rk.vibrate")
                passThrough(intent, data, "lockScreenBehavior", "rk.lockScreenBehavior")
                passThrough(intent, data, "extra", "rk.extra")
            }
            "com.wibechat.ringkit_example.SIMULATE_CANCEL" -> {
                data["rk.type"] = "cancel"
                data["rk.callId"] = intent.getStringExtra("callId") ?: return
                passThrough(intent, data, "reason", "rk.reason")
            }
            else -> return
        }
        RingKitNative.deliverPayload(context, data)
    }

    private fun passThrough(intent: Intent, data: MutableMap<String, String>, extra: String, key: String) {
        intent.getStringExtra(extra)?.let { data[key] = it }
    }
}
