package com.wibechat.ringkit.core

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Plugin-wide time discipline (PLAN §8.9): durations, deadlines and ordering use
 * elapsedRealtime + bootCount; wall clock is display/serialization only.
 *
 * Injectable so tests can simulate reboots and deadline passage without shadows.
 */
interface TimeSource {
    /** SystemClock.elapsedRealtime — monotonic within a boot. */
    fun elapsedMs(): Long

    /** System.currentTimeMillis — display/serialization only. */
    fun wallMs(): Long

    /** Settings.Global.BOOT_COUNT — stamps every persisted deadline. */
    fun bootCount(): Int
}

class RealTimeSource(private val context: Context) : TimeSource {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    override fun wallMs(): Long = System.currentTimeMillis()

    override fun bootCount(): Int =
        try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (_: Settings.SettingNotFoundException) {
            // Extremely defensive: BOOT_COUNT exists since API 24 (minSdk 26).
            0
        }
}
