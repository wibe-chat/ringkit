package com.wibechat.ringkit.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingKitIntents
import com.wibechat.ringkit.core.RingLog

/**
 * Invisible accept/missed-tap trampoline (PLAN §4.3/§8.1): translucent,
 * `showWhenLocked` + `turnScreenOn`, null-intent-hardened (Stream's
 * process-death lesson). Calls `requestDismissKeyguard` BEFORE forwarding so
 * the user isn't left staring at the keyguard, and forwards the launch intent
 * SYNCHRONOUSLY in onCreate — the BAL visible-window exemption dies at
 * finish().
 *
 * Accept lands here from BOTH the notification action and the FSI activity's
 * button — one code path (§4.3). Accept on an already-terminal call still
 * launches the app; the persisted launch action carries `alreadyEnded`.
 */
class AcceptTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        val callId = RingKitIntents.callIdOf(intent)
        val action = intent?.action
        if (callId == null || action == null) {
            // Null/stale intent after process death: nothing to do, no crash.
            finishQuietly()
            return
        }

        val core = RingKitCore.get(applicationContext)
        core.ringLog.log(
            CoreLogLevel.INFO, "trampoline", "AcceptTrampolineActivity.onCreate", callId,
            "trampoline", mapOf("action" to action),
        )

        // Persist-first on the writer, BOUNDED — the transition is small file
        // I/O; if it can't complete in time we still forward (the launch action
        // is written by the transition; the app's startup consume is ordered
        // behind it on the same writer).
        runCatching {
            core.writer.await(timeoutMs = 3_000L) {
                val orchestrator = IncomingCallOrchestrator(applicationContext, core)
                orchestrator.sweepNow()
                when (action) {
                    RingKitIntents.ACTION_ACCEPT -> orchestrator.acceptFromActionOnWriter(callId)
                    RingKitIntents.ACTION_MISSED_TAP -> orchestrator.missedTappedOnWriter(callId)
                    else -> Unit
                }
            }
        }.onFailure {
            core.ringLog.incident(
                RingLog.IncidentType.BOUNDARY_EXCEPTION, "AcceptTrampolineActivity.onCreate", callId,
                throwable = it,
            )
        }

        // §4.3: dismiss the keyguard BEFORE forwarding. INTEGRATION.md requires
        // the app's call screen to setShowWhenLocked(true) or accept-from-lock
        // bounces through authentication.
        runCatching {
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguard.requestDismissKeyguard(this, null)
        }

        // Forward SYNCHRONOUSLY in onCreate (BAL exemption dies at finish()).
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            runCatching { startActivity(launch) }
                .onFailure {
                    core.ringLog.log(
                        CoreLogLevel.ERROR, "trampoline", "AcceptTrampolineActivity.onCreate", callId,
                        "launch failed", throwable = it,
                    )
                }
        }
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
        // No transition animation for an invisible activity.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
