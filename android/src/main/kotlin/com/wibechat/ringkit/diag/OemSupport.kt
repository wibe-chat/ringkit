package com.wibechat.ringkit.diag

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * §12 OEM playbook — detect, guide, degrade, document. The table is DATA:
 * manufacturer/brand match → known-restriction flags → settings intents
 * (existence-checked, graceful fallback; OEM intents change across versions).
 */
object OemSupport {

    enum class Screen { AUTOSTART, LOCK_SCREEN_DISPLAY, BATTERY }

    data class Entry(
        /** lowercase `contains` match against MANUFACTURER or BRAND. */
        val match: List<String>,
        val restrictions: List<String>,
        /** Ordered candidates per screen — first resolvable wins. */
        val screens: Map<Screen, List<Pair<String, String>>>,
    )

    /** §12 detection table (component candidates from the OEM playbook). */
    val TABLE: List<Entry> =
        listOf(
            Entry(
                match = listOf("xiaomi", "redmi", "poco"),
                restrictions = listOf("autostartPermission", "lockScreenDisplayToggle", "aggressiveBatterySaver"),
                screens =
                    mapOf(
                        Screen.AUTOSTART to
                            listOf(
                                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                            ),
                        Screen.LOCK_SCREEN_DISPLAY to
                            listOf(
                                "com.miui.securitycenter" to "com.miui.permcenter.permissions.PermissionsEditorActivity",
                            ),
                        Screen.BATTERY to
                            listOf(
                                "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                            ),
                    ),
            ),
            Entry(
                match = listOf("oppo", "realme"),
                restrictions = listOf("autostartPermission", "lockScreenDisplayToggle"),
                screens =
                    mapOf(
                        Screen.AUTOSTART to
                            listOf(
                                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                            ),
                        Screen.BATTERY to
                            listOf(
                                "com.coloros.safecenter" to "com.coloros.safecenter.appfrozen.activity.AppFrozenSettingsActivity",
                            ),
                    ),
            ),
            Entry(
                match = listOf("vivo", "iqoo"),
                restrictions = listOf("backgroundPopupPermission", "autostartPermission"),
                screens =
                    mapOf(
                        Screen.AUTOSTART to
                            listOf(
                                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                            ),
                    ),
            ),
            Entry(
                match = listOf("huawei", "honor"),
                restrictions = listOf("autostartPermission", "noGmsOnRecentDevices"),
                screens =
                    mapOf(
                        Screen.AUTOSTART to
                            listOf(
                                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                            ),
                    ),
            ),
            Entry(
                match = listOf("samsung"),
                restrictions = listOf("sleepingApps", "aggressiveDoze"),
                screens =
                    mapOf(
                        Screen.BATTERY to
                            listOf(
                                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
                            ),
                    ),
            ),
            Entry(
                match = listOf("oneplus"),
                restrictions = listOf("advancedBatteryOptimization"),
                screens =
                    mapOf(
                        Screen.AUTOSTART to
                            listOf(
                                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                            ),
                    ),
            ),
        )

    fun entryFor(manufacturer: String = Build.MANUFACTURER ?: "", brand: String = Build.BRAND ?: ""): Entry? {
        val hay = "${manufacturer.lowercase()} ${brand.lowercase()}"
        return TABLE.firstOrNull { entry -> entry.match.any { hay.contains(it) } }
    }

    fun knownRestrictions(): List<String> = entryFor()?.restrictions ?: emptyList()

    /** Best-effort §11.2 helper: first resolvable candidate opens; false if none. */
    fun openScreen(context: Context, screen: Screen): Boolean {
        val entry = entryFor() ?: return false
        val candidates = entry.screens[screen] ?: return false
        for ((pkg, cls) in candidates) {
            val intent =
                Intent().setClassName(pkg, cls)
                    .putExtra("package_name", context.packageName)
                    .putExtra("packageName", context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable =
                runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)
            if (resolvable) {
                val started = runCatching { context.startActivity(intent) }.isSuccess
                if (started) return true
            }
        }
        return false
    }
}
