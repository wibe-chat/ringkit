package com.wibechat.ringkit.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * §10.2 rung 2 host selection — the ladder's single, cold-process-safe entry to
 * durably deliver pending background events while the app is dead. Lives in
 * `services/` (not the Flutter-aware `adapter/`) so the core orchestrator can
 * trigger it without depending on the adapter — it only starts a service /
 * enqueues work; the Flutter engine is booted later, inside the host.
 *
 * - API 34+ → an [EventDeliveryService] `shortService` FGS hosts the runner (no
 *   type permission; keeps the process perceptible for the ~3-min budget).
 * - API 26–33 → an **expedited** `OneTimeWorkRequest` — the only host that
 *   survives process death AND reboot below 34. `KEEP` dedups a push burst onto
 *   one run.
 *
 * FGS-start denial on 34+ falls back to the same worker. Both hosts need a
 * foreground notification (shortService; and pre-12 expedited-work quota).
 */
internal object BackgroundDeliveryHost {
    const val WORK_NAME = "ringkit.backgroundDelivery"

    /** Outside the call id band + the §8.2 missed-call offset. */
    const val NOTIFICATION_ID = 424_299
    private const val CHANNEL_ID = "ringkit_bg_delivery_v1"

    fun deliver(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 34) {
            val started =
                runCatching { app.startForegroundService(Intent(app, EventDeliveryService::class.java)) }.isSuccess
            if (!started) enqueueWork(app) // FGS denied → durable fallback
        } else {
            enqueueWork(app)
        }
    }

    private fun enqueueWork(context: Context) {
        runCatching {
            val request =
                OneTimeWorkRequestBuilder<BackgroundDeliveryWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    /** Minimal MIN-importance FGS notification (shortService + pre-12 quota need one). */
    fun notification(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Processing call action")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background processing", NotificationManager.IMPORTANCE_MIN),
            )
        }
    }
}
