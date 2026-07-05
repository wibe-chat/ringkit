package com.wibechat.ringkit.adapter

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.wibechat.ringkit.core.RingKitCore

/**
 * §11.2 — honest-minimal M1 implementation: real system reads and best-effort
 * settings intents. The full onboarding helpers (runtime notification request
 * with Activity result plumbing, OEM intent table) land in M2/M3; anything not
 * implementable yet reports its true state rather than a hopeful one.
 */
internal class RingKitPermissionsApiImpl(private val context: Context) : RingKitPermissionsApi {

    companion object {
        private const val REQUEST_CODE_NOTIFICATIONS = 42421
    }

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var activityBinding: io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding? = null
    private var pendingNotificationRequest: ((Boolean) -> Unit)? = null

    private val permissionListener =
        io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener { code, _, results ->
            if (code != REQUEST_CODE_NOTIFICATIONS) return@RequestPermissionsResultListener false
            val granted =
                results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
            pendingNotificationRequest?.invoke(granted)
            pendingNotificationRequest = null
            true
        }

    fun attachActivity(binding: io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding) {
        activityBinding = binding
        binding.addRequestPermissionsResultListener(permissionListener)
    }

    fun detachActivity() {
        activityBinding?.removeRequestPermissionsResultListener(permissionListener)
        // An in-flight prompt that loses its activity resolves honestly: report
        // the current state instead of hanging the Dart future.
        pendingNotificationRequest?.invoke(notificationManager.areNotificationsEnabled())
        pendingNotificationRequest = null
        activityBinding = null
    }

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
        // §11.2: POST_NOTIFICATIONS runtime prompt (13+; a no-op grant below).
        if (Build.VERSION.SDK_INT < 33 || notificationManager.areNotificationsEnabled()) {
            callback(Result.success(notificationManager.areNotificationsEnabled()))
            return
        }
        val activity = activityBinding?.activity
        if (activity == null || pendingNotificationRequest != null) {
            // No activity (background isolate) or a prompt already in flight:
            // report the true current state, never lie, never hang.
            callback(Result.success(notificationManager.areNotificationsEnabled()))
            return
        }
        pendingNotificationRequest = { granted -> callback(Result.success(granted)) }
        runCatching {
            activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQUEST_CODE_NOTIFICATIONS)
        }.onFailure {
            pendingNotificationRequest = null
            callback(Result.success(notificationManager.areNotificationsEnabled()))
        }
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
                // Versioned ids from the single owner (§8.2) + the configured suffix.
                val suffix = RingKitCore.get(context).repository.currentConfig().channelSuffix
                val channelId =
                    when (channel) {
                        RingKitChannel.INCOMING -> com.wibechat.ringkit.ui.RingKitChannels.incoming(suffix)
                        RingKitChannel.INCOMING_AUDIBLE -> com.wibechat.ringkit.ui.RingKitChannels.incomingAudible(suffix)
                        RingKitChannel.ONGOING -> com.wibechat.ringkit.ui.RingKitChannels.ongoing(suffix)
                        RingKitChannel.MISSED -> com.wibechat.ringkit.ui.RingKitChannels.missed(suffix)
                    }
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
        // §11.2: the direct dialog REQUIRES the app to declare
        // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS itself (Play-policy caveat —
        // the plugin never declares it). Undeclared/failed ⇒ report the true
        // current state, never a hopeful one.
        if (isIgnoringBatteryOpt()) {
            callback(Result.success(true))
            return
        }
        val declared =
            runCatching {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_PERMISSIONS,
                ).requestedPermissions?.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") == true
            }.getOrDefault(false)
        if (declared) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        // The dialog is asynchronous with no result surface for a plugin —
        // report the state as of now; apps re-query snapshot() on resume.
        callback(Result.success(isIgnoringBatteryOpt()))
    }

    override fun oemInfo(callback: (Result<OemInfo>) -> Unit) {
        callback(runCatching { oemInfoNow() })
    }

    override fun openOemSettings(screen: OemScreen, callback: (Result<Boolean>) -> Unit) {
        // §12 data-driven intent table (existence-checked; false = unresolved).
        val mapped =
            when (screen) {
                OemScreen.AUTOSTART -> com.wibechat.ringkit.diag.OemSupport.Screen.AUTOSTART
                OemScreen.LOCK_SCREEN_DISPLAY -> com.wibechat.ringkit.diag.OemSupport.Screen.LOCK_SCREEN_DISPLAY
                OemScreen.BATTERY -> com.wibechat.ringkit.diag.OemSupport.Screen.BATTERY
            }
        callback(runCatching { com.wibechat.ringkit.diag.OemSupport.openScreen(context, mapped) })
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun canUseFsi(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) notificationManager.canUseFullScreenIntent() else true

    private fun isIgnoringBatteryOpt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun incomingChannelImportance(): NotificationImportance {
        val suffix =
            runCatching { RingKitCore.get(context).repository.currentConfig().channelSuffix }.getOrDefault("")
        val channel =
            notificationManager.getNotificationChannel(com.wibechat.ringkit.ui.RingKitChannels.incoming(suffix))
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
            // §12 data-driven detection table.
            knownRestrictions = com.wibechat.ringkit.diag.OemSupport.knownRestrictions(),
        )

    private fun holdsPermission(permission: String): Boolean =
        context.checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
}
