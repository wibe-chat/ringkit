package com.wibechat.ringkit.telemetry

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.WeakHashMap

/** Visibility-only timer. No wake lock, background polling service or Flutter dependency. */
internal class TelemetryLifecycle(context: Context, private val sync: (Boolean) -> Unit, private val interval: () -> Long) :
    Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val app = context.applicationContext as? Application
    private val handler = Handler(Looper.getMainLooper())
    private val started = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    @Volatile private var closed = false
    private val tick = object : Runnable {
        override fun run() {
            if (closed || started.isEmpty()) return
            sync(false)
            handler.postDelayed(this, interval())
        }
    }
    init { app?.registerActivityLifecycleCallbacks(this) }
    override fun onActivityStarted(activity: Activity) {
        if (closed) return
        val wasEmpty = started.isEmpty()
        started.add(activity)
        if (wasEmpty) { sync(true); handler.postDelayed(tick, interval()) }
    }
    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        if (started.isEmpty()) handler.removeCallbacks(tick)
    }
    fun refreshCadence() {
        handler.post {
            if (!closed && started.isNotEmpty()) {
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, interval())
            }
        }
    }
    override fun close() {
        closed = true
        app?.unregisterActivityLifecycleCallbacks(this)
        handler.removeCallbacks(tick)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = onActivityStarted(activity)
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = onActivityStopped(activity)
}
