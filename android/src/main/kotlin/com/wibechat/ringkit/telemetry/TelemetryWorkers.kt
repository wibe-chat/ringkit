package com.wibechat.ringkit.telemetry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wibechat.ringkit.core.RingKitCore
import java.util.concurrent.TimeUnit

/**
 * §11.5 worker scheduling seam — the engine steers scheduling through this
 * interface so tests can record instead of touching WorkManager.
 */
internal interface TelemetryScheduler {
    fun ensureHeartbeat()

    fun scheduleIncidentUpload()

    fun schedulePull()

    fun scheduleUserReport()

    fun scheduleKillPoll(delayed: Boolean)

    fun cancelAllWork()
}

/**
 * Real WorkManager scheduling (PLAN §13.1: androidx.work in adapter/ +
 * telemetry/ ONLY). All requests: CONNECTED constraint, exponential backoff,
 * shared tag for the kill switch. Incidents are EXPEDITED one-shots (tier 1);
 * the heartbeat is the single daily periodic slot (tier 2).
 */
internal class WorkTelemetryScheduler(private val context: Context) : TelemetryScheduler {

    companion object {
        const val TAG = "ringkit-telemetry"
        const val WORK_INCIDENT = "ringkit.telemetry.incident"
        const val WORK_HEARTBEAT = "ringkit.telemetry.heartbeat"
        const val WORK_PULL = "ringkit.telemetry.pull"
        const val WORK_USER_REPORT = "ringkit.telemetry.userReport"
        const val WORK_KNOB_POLL = "ringkit.telemetry.knobPoll"
    }

    private val connected =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun wm(): WorkManager = WorkManager.getInstance(context)

    override fun ensureHeartbeat() {
        runCatching {
            val request =
                PeriodicWorkRequestBuilder<HeartbeatWorker>(24, TimeUnit.HOURS)
                    .setConstraints(connected)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
            wm().enqueueUniquePeriodicWork(WORK_HEARTBEAT, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    override fun scheduleIncidentUpload() {
        runCatching {
            val request =
                OneTimeWorkRequestBuilder<IncidentUploadWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(connected)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
            wm().enqueueUniqueWork(WORK_INCIDENT, ExistingWorkPolicy.KEEP, request)
        }
    }

    override fun schedulePull() {
        runCatching {
            val request =
                OneTimeWorkRequestBuilder<PullWorker>()
                    .setConstraints(connected)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
            wm().enqueueUniqueWork(WORK_PULL, ExistingWorkPolicy.KEEP, request)
        }
    }

    override fun scheduleUserReport() {
        runCatching {
            val request =
                OneTimeWorkRequestBuilder<UserReportWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(connected)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
            // REPLACE: an explicit tester action supersedes a stuck earlier one.
            wm().enqueueUniqueWork(WORK_USER_REPORT, ExistingWorkPolicy.REPLACE, request)
        }
    }

    override fun scheduleKillPoll(delayed: Boolean) {
        runCatching {
            val request =
                OneTimeWorkRequestBuilder<KnobPollWorker>()
                    .setConstraints(connected)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .apply { if (delayed) setInitialDelay(24, TimeUnit.HOURS) }
                    .addTag(TAG)
                    .build()
            wm().enqueueUniqueWork(WORK_KNOB_POLL, ExistingWorkPolicy.REPLACE, request)
        }
    }

    override fun cancelAllWork() {
        runCatching { wm().cancelAllWorkByTag(TAG) }
    }
}

/**
 * Shared worker shell: resolves the process session; a fired worker in a
 * process whose config went null cancels the stale schedule and exits (the
 * inert path itself never touches WorkManager — rule 14).
 */
internal abstract class TelemetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        /** Backoff cap; reconciliation (activation/heartbeat rescans) re-enqueues. */
        const val MAX_ATTEMPTS = 8

        private const val CHANNEL_ID = "ringkit_telemetry_v1"
        private const val NOTIFICATION_ID = 424_298
    }

    abstract fun run(engine: TelemetryEngine): TelemetryRun

    final override suspend fun doWork(): Result {
        RingKitCore.get(applicationContext) // constructs core ⇒ Telemetry.activate ran
        val session = Telemetry.session
        if (session == null) {
            runCatching { WorkManager.getInstance(applicationContext).cancelAllWorkByTag(WorkTelemetryScheduler.TAG) }
            return Result.success()
        }
        val outcome = runCatching { run(session.engine()) }.getOrElse { TelemetryRun.RETRY }
        return when (outcome) {
            TelemetryRun.OK, TelemetryRun.DEFERRED -> Result.success()
            TelemetryRun.RETRY ->
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    /** Pre-Android-12 expedited quota needs a (MIN-importance) FGS notification. */
    final override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(applicationContext))

    private fun notification(context: Context): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Diagnostics upload", NotificationManager.IMPORTANCE_MIN),
            )
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Uploading diagnostics")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}

internal class IncidentUploadWorker(context: Context, params: WorkerParameters) :
    TelemetryWorker(context, params) {
    override fun run(engine: TelemetryEngine): TelemetryRun = engine.runIncidents()
}

internal class HeartbeatWorker(context: Context, params: WorkerParameters) :
    TelemetryWorker(context, params) {
    override fun run(engine: TelemetryEngine): TelemetryRun {
        val outcome = engine.runHeartbeat()
        // Reconciliation sweep (R32 philosophy): stragglers a died process never
        // enqueued get picked up by the daily slot.
        if (outcome == TelemetryRun.OK && engine.pendingIncidentFiles().isNotEmpty()) {
            Telemetry.session?.scheduler?.scheduleIncidentUpload()
        }
        return outcome
    }
}

internal class PullWorker(context: Context, params: WorkerParameters) :
    TelemetryWorker(context, params) {
    override fun run(engine: TelemetryEngine): TelemetryRun {
        // §11.5 "prefer unmetered": give Wi-Fi a couple of backoff rounds, then
        // ship anyway — an operator-initiated pull must not stall forever.
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm?.isActiveNetworkMetered == true && runAttemptCount < 2) return TelemetryRun.RETRY
        return engine.runPull()
    }
}

internal class UserReportWorker(context: Context, params: WorkerParameters) :
    TelemetryWorker(context, params) {
    override fun run(engine: TelemetryEngine): TelemetryRun = engine.runUserReport()
}

internal class KnobPollWorker(context: Context, params: WorkerParameters) :
    TelemetryWorker(context, params) {
    override fun run(engine: TelemetryEngine): TelemetryRun = engine.runKnobPoll()
}
