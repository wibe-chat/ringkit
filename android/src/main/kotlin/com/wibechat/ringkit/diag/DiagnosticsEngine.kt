package com.wibechat.ringkit.diag

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.ui.RingKitChannels

/**
 * §11.3 diagnostics v1 — the support-load killer. Each check yields
 * `{id, severity, finding, remedy, deepLink?}`; the output is serializable and
 * issue templates require it. The engine reads REALITY (system services,
 * merged manifest, channel read-backs, store health) — never cached hopes.
 *
 * deepLink vocabulary (the app resolves via RingKit.permissions):
 * `notificationSettings` · `channel:<incoming|incomingAudible|ongoing|missed>`
 * · `fullScreenIntentSettings` · `batteryOptimizationSettings` ·
 * `oem:<autostart|lockScreenDisplay|battery>`.
 */
object DiagnosticsEngine {

    enum class Severity { INFO, WARNING, ERROR }

    data class Finding(
        val id: String,
        val severity: Severity,
        val finding: String,
        val remedy: String,
        val deepLink: String? = null,
    )

    fun run(context: Context, core: RingKitCore.Instance): List<Finding> {
        val findings = mutableListOf<Finding>()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val config = core.repository.currentConfig()
        val suffix = config.channelSuffix

        // ── notifications + channels (§8.11 rows 1–4) ────────────────────
        if (!notificationManager.areNotificationsEnabled()) {
            findings +=
                Finding(
                    "notificationsBlocked", Severity.ERROR,
                    "Notifications are blocked for this app — every incoming call will report failed(notificationsBlocked) and never ring.",
                    "Ask the user to enable notifications (Settings → Apps → notifications).",
                    "notificationSettings",
                )
        }
        val incoming = notificationManager.getNotificationChannel(RingKitChannels.incoming(suffix))
        when {
            incoming == null ->
                findings +=
                    Finding(
                        "incomingChannelMissing", Severity.INFO,
                        "The incoming-call channel does not exist yet (created lazily at first show/initialize).",
                        "No action needed — call initialize() or show a call once.",
                    )
            incoming.importance == NotificationManager.IMPORTANCE_NONE ->
                findings +=
                    Finding(
                        "incomingChannelBlocked", Severity.ERROR,
                        "The incoming-call channel is blocked — shows report failed(channelBlocked).",
                        "Re-enable the 'Incoming calls' notification channel.",
                        "channel:incoming",
                    )
            incoming.importance < NotificationManager.IMPORTANCE_HIGH ->
                findings +=
                    Finding(
                        "incomingChannelDemoted", Severity.WARNING,
                        "The incoming-call channel was demoted below HIGH — no OS heads-up (the FGS + ringtone still ring); shows report degraded(channelDemoted).",
                        "Raise the 'Incoming calls' channel importance back to urgent (users only — apps cannot re-raise it).",
                        "channel:incoming",
                    )
        }
        val audible = notificationManager.getNotificationChannel(RingKitChannels.incomingAudible(suffix))
        if (audible != null &&
            (audible.importance < NotificationManager.IMPORTANCE_DEFAULT || audible.sound == null)
        ) {
            findings +=
                Finding(
                    "audibleFallbackDegraded", Severity.WARNING,
                    "The audible-fallback channel is muted/demoted — the fgsDenied degraded path loses its system-rendered ring.",
                    "Restore the 'Incoming calls (fallback)' channel importance and sound.",
                    "channel:incomingAudible",
                )
        }

        // ── FSI permission (14+, reporting-only — §8.2) ──────────────────
        if (Build.VERSION.SDK_INT >= 34) {
            val canFsi =
                runCatching { notificationManager.canUseFullScreenIntent() }.getOrDefault(true)
            if (!canFsi) {
                findings +=
                    Finding(
                        "fsiDenied", Severity.WARNING,
                        "Full-screen-intent special access is denied — lock-screen calls degrade to the 60 s pinned heads-up (degraded(fsiDenied)).",
                        "Deep-link the user to the full-screen-intent settings toggle.",
                        "fullScreenIntentSettings",
                    )
            }
        }

        // ── ringer / DND right now (§11.3 — deliberate silence ≠ bug) ────
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT ->
                findings +=
                    Finding(
                        "ringerSilent", Severity.INFO,
                        "Ringer mode is SILENT right now — calls will show without sound or vibration (deliberate, not a bug).",
                        "Informational.",
                    )
            AudioManager.RINGER_MODE_VIBRATE ->
                findings +=
                    Finding(
                        "ringerVibrate", Severity.INFO,
                        "Ringer mode is VIBRATE right now — calls vibrate without sound.",
                        "Informational.",
                    )
        }
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            (audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0 ||
                audioManager.isStreamMute(AudioManager.STREAM_RING))
        ) {
            findings +=
                Finding(
                    "ringVolumeZero", Severity.WARNING,
                    "Ringer mode is NORMAL but the ring stream is muted or at zero volume — calls can vibrate without audible sound.",
                    "Raise the device ring volume in system sound settings.",
                )
        }
        val filter = notificationManager.currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) {
            findings +=
                Finding(
                    "dndActive", Severity.INFO,
                    "Do Not Disturb is active — on API 34+ per-caller exceptions are honored (matchesCallFilter); below, ring sound is conservatively suppressed.",
                    "Informational; users can exempt calls in DND settings.",
                )
        }

        // ── merged-manifest survival (§11.3 — each removal re-opens a failure) ─
        val mergedChecks =
            listOf(
                Triple(
                    "android.permission.USE_FULL_SCREEN_INTENT", "manifestFsiRemoved",
                    "an app-side tools:node=\"remove\" re-opens the CallStyle validity crash corner",
                ),
                Triple(
                    "android.permission.FOREGROUND_SERVICE", "manifestFgsRemoved",
                    "the ringing/ongoing services cannot start foreground",
                ),
                Triple(
                    "android.permission.FOREGROUND_SERVICE_PHONE_CALL", "manifestFgsPhoneCallRemoved",
                    "phoneCall-typed foreground starts throw on API 34+",
                ),
                Triple(
                    "android.permission.MANAGE_OWN_CALLS", "manifestManageOwnCallsRemoved",
                    "the phoneCall FGS-type prerequisite is unsatisfied",
                ),
            )
        for ((permission, id, consequence) in mergedChecks) {
            val present =
                runCatching {
                    val info =
                        context.packageManager.getPackageInfo(
                            context.packageName, PackageManager.GET_PERMISSIONS,
                        )
                    info.requestedPermissions?.contains(permission) == true
                }.getOrDefault(true)
            if (!present) {
                findings +=
                    Finding(
                        id, Severity.ERROR,
                        "$permission is missing from the merged manifest — $consequence.",
                        "Remove any tools:node=\"remove\" for this permission from the app manifest.",
                    )
            }
        }

        // ── battery optimization + OEM flags (§12) ───────────────────────
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            findings +=
                Finding(
                    "batteryOptimized", Severity.INFO,
                    "The app is subject to battery optimization — high-priority FCM normally still wakes it; aggressive OEMs are the risk.",
                    "Offer the battery-optimization exemption in onboarding for problem devices.",
                    "batteryOptimizationSettings",
                )
        }
        val oem = OemSupport.entryFor()
        if (oem != null) {
            findings +=
                Finding(
                    "oemRestrictions", Severity.WARNING,
                    "${Build.MANUFACTURER} device — known restrictions: ${oem.restrictions.joinToString()}.",
                    "Walk the user through the OEM screens (autostart/lock-screen/battery) in onboarding.",
                    "oem:autostart",
                )
        }

        // ── config resolvables (§11.3) ───────────────────────────────────
        val icon = config.notificationIcon
        if (!icon.isNullOrBlank() &&
            context.resources.getIdentifier(icon, "drawable", context.packageName) == 0
        ) {
            findings +=
                Finding(
                    "notificationIconUnresolvable", Severity.ERROR,
                    "notificationIcon '$icon' does not resolve to a drawable — the plugin glyph is used instead.",
                    "Fix the resource name or add a keep rule (R8 may have stripped it).",
                )
        }
        val ringtone = config.defaultRingtone
        if (ringtone != null && ringtone.startsWith("raw:")) {
            val name = ringtone.removePrefix("raw:")
            if (context.resources.getIdentifier(name, "raw", context.packageName) == 0) {
                findings +=
                    Finding(
                        "ringtoneUnresolvable", Severity.ERROR,
                        "defaultRingtone '$ringtone' does not resolve — the device default is used instead.",
                        "Fix the res/raw name and keep it from R8 via keep.xml (INTEGRATION.md).",
                    )
            }
        }

        // ── multi-process manifest (§8.9 — unsupported) ──────────────────
        val foreignProcess =
            runCatching {
                val info =
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS,
                    )
                val components: List<android.content.pm.ComponentInfo> =
                    (info.activities?.toList() ?: emptyList()) +
                        (info.services?.toList() ?: emptyList()) +
                        (info.receivers?.toList() ?: emptyList())
                components.any { it.processName != null && it.processName != context.packageName }
            }.getOrDefault(false)
        if (foreignProcess) {
            findings +=
                Finding(
                    "multiProcessManifest", Severity.WARNING,
                    "Components running in a non-default android:process were found — the RingKit store is single-process (best-effort FileLock only).",
                    "Keep all RingKit-touching components in the default process (§8.9).",
                )
        }

        // ── background handler + store health (§11.3) ────────────────────
        if (core.store.readBackgroundHandles() == null) {
            findings +=
                Finding(
                    "backgroundHandlerUnregistered", Severity.INFO,
                    "No background event handler is registered — decline/timeout/cancel while the app is dead will wait for the next launch (replay).",
                    "Call RingKit.setBackgroundEventHandler on every launch if killed-state side effects are wanted.",
                )
        }
        if (core.store.quarantinedCount > 0) {
            findings +=
                Finding(
                    "storeQuarantine", Severity.WARNING,
                    "${core.store.quarantinedCount} corrupt store file(s) were quarantined this process (lenient reads — nothing crashed).",
                    "Attach diagnose() output to a bug report if this recurs.",
                )
        }
        val dropped = core.repository.eventLog.droppedCount()
        if (dropped > 0) {
            findings +=
                Finding(
                    "droppedEvents", Severity.WARNING,
                    "$dropped unconsumed event(s) were dropped at the 200-event cap — the app is integrated but not consuming events.",
                    "Subscribe to RingKit.events (and ack via the facade) on every launch.",
                )
        }

        return findings
    }
}
