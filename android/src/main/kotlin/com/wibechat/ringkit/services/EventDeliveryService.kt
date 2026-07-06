package com.wibechat.ringkit.services

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.wibechat.ringkit.adapter.BackgroundDeliveryCoordinator
import com.wibechat.ringkit.adapter.BackgroundEngineRunner
import com.wibechat.ringkit.core.RingKitCore

/**
 * §8.1/§10.2 — API 34+ headless-delivery host: a `shortService` FGS (no type
 * permission) that keeps the process perceptible while the background isolate
 * runs the app's handler.
 *
 * `startForeground` is the UNCONDITIONAL first statement (R16); START_NOT_STICKY
 * (a restarted delivery with a null intent is meaningless — the durable
 * WorkManager fallback / replay cover it). Stops when the dispatcher finishes,
 * when nothing was leased to deliver, or on the shortService timeout; while
 * running it renews the leases so a multi-event batch never expires (§10.2).
 */
class EventDeliveryService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var renew: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // R16: foreground FIRST, before any work — nothing precedes this.
        ServiceCompat.startForeground(
            this,
            BackgroundDeliveryHost.NOTIFICATION_ID,
            BackgroundDeliveryHost.notification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0,
        )
        val core = RingKitCore.get(this)
        BackgroundDeliveryCoordinator.awaitFinish { stop() }
        BackgroundEngineRunner(this, core).deliverPending { prep ->
            if (prep == BackgroundEngineRunner.Prep.LEASED) {
                scheduleRenew(core)
            } else {
                stop() // unregistered / nothing pending / stale ⇒ no handler run coming
            }
        }
        return START_NOT_STICKY
    }

    private fun scheduleRenew(core: RingKitCore.Instance) {
        val r =
            Runnable {
                BackgroundDeliveryCoordinator.renewActiveLeases(core)
                scheduleRenew(core)
            }
        renew = r
        main.postDelayed(r, RENEW_INTERVAL_MS)
    }

    private fun stop() {
        main.post {
            renew?.let { main.removeCallbacks(it) }
            renew = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** shortService ~3-min budget hit (34+): release; un-acked leases replay. */
    override fun onTimeout(startId: Int) {
        stop()
    }

    override fun onDestroy() {
        renew?.let { main.removeCallbacks(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val RENEW_INTERVAL_MS = 15_000L
    }
}
