package com.wibechat.ringkit.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PendingIntent target for decline / timeout / hang-up actions (PLAN §8.1,
 * §8.9): explicit-intent only, `exported="false"`, every PI targeting it sets
 * FLAG_RECEIVER_FOREGROUND (R39). Persist-FIRST via goAsync + the writer
 * executor (bounded < 5 s); UI effects run inside the orchestrator; losers of
 * transition races perform ZERO side effects (§5.1).
 *
 * Constructs everything from appContext + persisted state (D4/R1): works in a
 * cold process with no Flutter engine and no plugin attached.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val callId = RingKitIntents.callIdOf(intent) ?: return
        val core = RingKitCore.get(context)
        core.ringLog.log(
            CoreLogLevel.INFO, "receiver", "CallActionReceiver.onReceive", callId, "action",
            mapOf("action" to action),
        )

        val pending = goAsync()
        core.writer.post {
            try {
                val orchestrator = IncomingCallOrchestrator(context, core)
                // §8.10: sweep at every native entry point, BEFORE the action —
                // a decline for a call the sweep must first time out resolves in
                // the right order (the loser rejects with zero side effects).
                orchestrator.sweepNow()
                when (action) {
                    RingKitIntents.ACTION_DECLINE -> orchestrator.declineOnWriter(callId)
                    RingKitIntents.ACTION_TIMEOUT -> orchestrator.timeoutOnWriter(callId)
                    RingKitIntents.ACTION_HANG_UP -> orchestrator.endOnWriter(callId, CoreEndReason.LOCAL)
                    else ->
                        core.ringLog.log(
                            CoreLogLevel.ERROR, "receiver", "CallActionReceiver.onReceive", callId,
                            "unknown action", mapOf("action" to action),
                        )
                }
            } catch (t: Throwable) {
                core.ringLog.incident(
                    RingLog.IncidentType.BOUNDARY_EXCEPTION, "CallActionReceiver.onReceive", callId,
                    throwable = t,
                )
            } finally {
                pending.finish()
            }
        }
    }
}
