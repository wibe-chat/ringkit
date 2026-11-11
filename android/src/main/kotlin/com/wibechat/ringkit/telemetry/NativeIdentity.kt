package com.wibechat.ringkit.telemetry

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.content.pm.PackageInfoCompat
import com.wibechat.ringkit.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Packaging provenance, not a claim about user identity or Flutter build mode. */
internal object NativeIdentity {
    const val PROTOCOL = 2
    fun sdk(): JSONObject = JSONObject()
        .put("plugin_version", BuildConfig.RINGKIT_VERSION)
        .put("sdk_build_id", BuildConfig.RINGKIT_BUILD_ID)
        .put("telemetry_protocol", PROTOCOL)
        .put("capabilities", JSONArray(listOf("audio_failure", "build_identity", "diagnostic_session", "correlated_pull")))

    fun capture(context: Context): JSONObject = sdk().apply {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        put("app_version", info?.versionName ?: "unknown")
        put("package_name", context.packageName)
        put("host_version_code", info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: JSONObject.NULL)
        put("host_debuggable", info?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 } ?: JSONObject.NULL)
    }
}
