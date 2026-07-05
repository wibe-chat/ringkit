package com.wibechat.ringkit.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED → reconciliation sweep ONLY (PLAN §8.1,
 * §8.10). Never starts an FGS (Android 15 forbids `phoneCall` FGS from boot —
 * and the sweep needs none): ghost RINGING calls from before the reboot/update
 * are timed out with events + missed-call surfaces by the sweep itself.
 */
class SweepReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val core = RingKitCore.get(context)
        core.ringLog.log(
            CoreLogLevel.INFO, "sweep", "SweepReceiver.onReceive", null, "system_broadcast",
            mapOf("action" to action),
        )
        // Bounded work (<5 s receiver budget): one orchestrated sweep pass on
        // the writer — swept calls get their events + missed-call surfaces.
        val pending = goAsync()
        core.writer.post {
            try {
                IncomingCallOrchestrator(context, core).sweepNow()
            } finally {
                pending.finish()
            }
        }
    }
}
