package com.wibechat.ringkit.services

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wibechat.ringkit.R
import com.wibechat.ringkit.core.CallRecord
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingLog
import com.wibechat.ringkit.ui.CallNotificationFactory
import com.wibechat.ringkit.ui.RingKitChannels

/**
 * The in-call FGS (PLAN §8.1): hosts the ACCEPTED ("Connecting…"), ACTIVE
 * (chronometer) and OUTGOING ("Calling…") surfaces as CallStyle-`forOngoingCall`
 * notifications — validity by FGS-attachment (v1.3.4). Default ON; apps opt out
 * with `manageOngoingCall: false` (surfaces then post via notify(), §8.2).
 *
 * R40 (incumbent #841/#842): `startForeground` is the UNCONDITIONAL FIRST
 * statement of onStartCommand, rebuilding the notification from the repository
 * when the intent is null; START_NOT_STICKY + the sweep backstop — a restarted
 * service never runs un-foregrounded and never crashes on a null action.
 *
 * `onTaskRemoved` → every in-call call ends with `ENDED(failed)` + stop (an
 * orphan "in call" notification is a lie, §8.1).
 *
 * FGS type: `phoneCall` always; `microphone` is ADDED only when the app's
 * merged manifest declares it on this service AND RECORD_AUDIO is granted
 * (documented app-side pattern, §8.1).
 */
class OngoingCallService : Service() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var core: RingKitCore.Instance
    private lateinit var orchestrator: IncomingCallOrchestrator
    private val main = Handler(Looper.getMainLooper())
    private var busSubscription: AutoCloseable? = null

    /** Writer-confined. */
    private var foregroundCallId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        core = RingKitCore.get(applicationContext)
        orchestrator = IncomingCallOrchestrator(applicationContext, core)
        isRunning = true
        // §8.6: every repository mutation re-evaluates — state changes re-point
        // the surface (Connecting…→chronometer); terminal transitions stop us.
        busSubscription = core.bus.subscribe { reevaluateOnWriter() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // R40: unconditional first statement — bounded snapshot, then
        // startForeground before ANYTHING else can throw or return.
        startForegroundFromRepository()
        core.writer.post { reevaluateOnWriter() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        busSubscription?.let { runCatching { it.close() } }
        busSubscription = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** §5.1: task removal ends every in-call call with ENDED(failed) + stop. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        core.ringLog.log(
            CoreLogLevel.INFO, "svc", "OngoingCallService.onTaskRemoved", foregroundCallId, "task_removed",
        )
        core.writer.post {
            core.repository.activeCalls()
                .filter { it.state != CoreCallState.RINGING }
                .forEach { orchestrator.taskRemovedOnWriter(it.callId) }
            // The bus signals from the transitions re-evaluate + stop us.
        }
    }

    // ── foreground discipline (R16/R40) ──────────────────────────────────

    private class Snapshot(val call: CallRecord?, val notifId: Int?, val config: CoreConfig)

    private fun startForegroundFromRepository() {
        val snapshot =
            runCatching {
                core.writer.await(timeoutMs = 3_000L) {
                    val call = inCallCall()
                    Snapshot(
                        call = call,
                        notifId = call?.let { core.repository.notificationIdFor(it.callId) },
                        config = core.repository.currentConfig(),
                    )
                }
            }.getOrElse { Snapshot(null, null, CoreConfig()) }

        val call = snapshot.call
        val notifId = snapshot.notifId ?: (snapshot.config.notificationIdBase - 2)
        val notification =
            if (call != null) {
                foregroundCallId = call.callId
                buildSurface(call, snapshot.config)
            } else {
                placeholderNotification(snapshot.config)
            }

        try {
            doStartForeground(notifId, notification)
        } catch (e: Exception) {
            core.ringLog.incident(
                RingLog.IncidentType.FGS_DENIED, "OngoingCallService.startForegroundFromRepository",
                call?.callId, throwable = e,
            )
            stopSelf()
            return
        }

        if (call == null) {
            // Foreground-first satisfied; nothing to host (restart race).
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun doStartForeground(id: Int, notification: Notification) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, id, notification, foregroundTypes())
        } else {
            startForeground(id, notification)
        }
    }

    /**
     * §8.1: `phoneCall` always; `microphone` only when the merged manifest
     * declares it on THIS service and RECORD_AUDIO is actually granted.
     */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        runCatching {
            val info =
                packageManager.getServiceInfo(
                    ComponentName(this, OngoingCallService::class.java),
                    PackageManager.GET_META_DATA,
                )
            val micDeclared =
                info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
            val micGranted =
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (micDeclared && micGranted) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        return types
    }

    private fun buildSurface(call: CallRecord, config: CoreConfig): Notification {
        val factory =
            CallNotificationFactory(applicationContext, config, core.time, core.ringLog, core.repository::notificationIdFor)
        return try {
            factory.buildOngoing(call)
        } catch (e: IllegalArgumentException) {
            core.ringLog.incident(
                RingLog.IncidentType.CALLSTYLE_REJECTED, "OngoingCallService.buildSurface",
                call.callId, setOf("callStyleRejected"), throwable = e,
            )
            factory.buildOngoingFallback(call)
        }
    }

    private fun placeholderNotification(config: CoreConfig): Notification =
        NotificationCompat.Builder(this, RingKitChannels.ongoing(config.channelSuffix))
            .setSmallIcon(R.drawable.ringkit_ic_call)
            .setContentTitle(getString(R.string.ringkit_channel_ongoing))
            .setOngoing(true)
            .build()

    // ── surface ownership (writer-confined) ──────────────────────────────

    private fun reevaluateOnWriter() {
        val call = inCallCall()
        if (call == null) {
            // No in-call call left: REMOVE — cancel() is a no-op on an
            // FGS-attached notification (device-found, RingingService lesson).
            main.post {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        // Re-point on owner or state change (Connecting… → chronometer):
        // subsequent startForeground calls update the same notification.
        val config = core.repository.currentConfig()
        val notifId = core.repository.notificationIdFor(call.callId) ?: (config.notificationIdBase - 2)
        val notification = buildSurface(call, config)
        foregroundCallId = call.callId
        main.post {
            runCatching { doStartForeground(notifId, notification) }
                .onFailure {
                    core.ringLog.log(
                        CoreLogLevel.ERROR, "svc", "OngoingCallService.reevaluate", call.callId,
                        "re-point failed", throwable = it,
                    )
                }
        }
    }

    /** ACTIVE > ACCEPTED > OUTGOING; ties by arrival order (repository order). */
    private fun inCallCall(): CallRecord? {
        val inCall = core.repository.activeCalls().filter { it.state != CoreCallState.RINGING }
        return inCall.firstOrNull { it.state == CoreCallState.ACTIVE }
            ?: inCall.firstOrNull { it.state == CoreCallState.ACCEPTED }
            ?: inCall.firstOrNull()
    }
}
