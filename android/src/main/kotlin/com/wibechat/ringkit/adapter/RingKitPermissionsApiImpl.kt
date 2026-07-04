package com.wibechat.ringkit.adapter

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * §11.2 — honest-minimal M1 implementation: real system reads and best-effort
 * settings intents. The full onboarding helpers (runtime notification request
 * with Activity result plumbing, OEM intent table) land in M2/M3; anything not
 * implementable yet reports its true state rather than a hopeful one.
 */
internal class RingKitPermissionsApiImpl(private val context: Context) : RingKitPermissionsApi {

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun snapshot(callback: (Result<PermissionSnapshot>) -> Unit) {
        callback(
            runCatching {
                PermissionSnapshot(
                    notifications = notificationManager.areNotificationsEnabled(),
                    fullScreenIntent = canUseFsi(),
                    // Channels are created lazily at first show (§6.4); before that
                    // the importance is honestly "none".
                    incomingChannelImportance = incomingChannelImportance(),
                    ignoringBatteryOptimizations = isIgnoringBatteryOpt(),
                    oem = oemInfoNow(),
                    manageOwnCallsMerged = holdsPermission("android.permission.MANAGE_OWN_CALLS"),
                    fgsMerged = holdsPermission("android.permission.FOREGROUND_SERVICE"),
                    fgsPhoneCallMerged =
                        Build.VERSION.SDK_INT < 34 ||
                            holdsPermission("android.permission.FOREGROUND_SERVICE_PHONE_CALL"),
                    fsiPermissionMerged = holdsPermission("android.permission.USE_FULL_SCREEN_INTENT"),
                )
            },
        )
    }

    override fun requestNotifications(callback: (Result<Boolean>) -> Unit) {
        // The runtime prompt needs Activity result plumbing — pulled forward in
        // M2 (SCOPE-1). Until then: report the current state, never lie.
        callback(Result.success(notificationManager.areNotificationsEnabled()))
    }

    override fun canUseFullScreenIntent(callback: (Result<Boolean>) -> Unit) {
        callback(runCatching { canUseFsi() })
    }

    override fun openFullScreenIntentSettings(callback: (Result<Unit>) -> Unit) {
        callback(
            runCatching {
                if (Build.VERSION.SDK_INT >= 34) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }

    override fun openChannelSettings(channel: RingKitChannel, callback: (Result<Unit>) -> Unit) {
        callback(
            runCatching {
                // Channel ids are versioned (§8.2); the ui/ factory owns them from M2.
                val channelId = "ringkit_${channel.name.lowercase()}_v1"
                context.startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }

    override fun isIgnoringBatteryOptimizations(callback: (Result<Boolean>) -> Unit) {
        callback(runCatching { isIgnoringBatteryOpt() })
    }

    override fun openBatteryOptimizationSettings(callback: (Result<Unit>) -> Unit) {
        callback(
            runCatching {
                // The no-permission settings LIST (§11.2 default helper).
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }

    override fun requestIgnoreBatteryOptimizations(callback: (Result<Boolean>) -> Unit) {
        // Direct-dialog variant requires the APP to declare the permission
        // (§11.2); the M2 helper checks the merged manifest first.
        callback(Result.success(isIgnoringBatteryOpt()))
    }

    override fun oemInfo(callback: (Result<OemInfo>) -> Unit) {
        callback(runCatching { oemInfoNow() })
    }

    override fun openOemSettings(screen: OemScreen, callback: (Result<Boolean>) -> Unit) {
        // §12 intent table is data-driven and lands in M3; false = not resolved.
        callback(Result.success(false))
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun canUseFsi(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) notificationManager.canUseFullScreenIntent() else true

    private fun isIgnoringBatteryOpt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun incomingChannelImportance(): NotificationImportance {
        val channel = notificationManager.getNotificationChannel("ringkit_incoming_v1")
        return when (channel?.importance) {
            null, NotificationManager.IMPORTANCE_NONE -> NotificationImportance.NONE
            NotificationManager.IMPORTANCE_MIN -> NotificationImportance.MIN
            NotificationManager.IMPORTANCE_LOW -> NotificationImportance.LOW
            NotificationManager.IMPORTANCE_DEFAULT -> NotificationImportance.DEFAULT_IMPORTANCE
            else -> NotificationImportance.HIGH
        }
    }

    private fun oemInfoNow(): OemInfo =
        OemInfo(
            manufacturer = Build.MANUFACTURER ?: "",
            brand = Build.BRAND ?: "",
            // §12 detection table is M3; empty = no known restrictions recorded.
            knownRestrictions = emptyList(),
        )

    private fun holdsPermission(permission: String): Boolean =
        context.checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
}
