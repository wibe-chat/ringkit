package com.wibechat.ringkit.adapter

import com.wibechat.ringkit.core.RingKitCore
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

/**
 * The thin adapter (PLAN D4): observes the self-sufficient native core, wires
 * Pigeon APIs per engine, and tracks the designated delivery engine (§10.3).
 * All call behavior lives in core/ — this class holds NO call state, and
 * nothing anywhere depends on it being attached (R1).
 */
class RingkitPlugin : FlutterPlugin, ActivityAware {

    private var engineId: Int = 0
    private var core: RingKitCore.Instance? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val instance = RingKitCore.get(binding.applicationContext)
        core = instance
        engineId = System.identityHashCode(binding.binaryMessenger)

        EngineRegistry.register(engineId)
        RingKitHostApi.setUp(binding.binaryMessenger, RingKitHostApiImpl(instance, engineId))
        RingKitPermissionsApi.setUp(
            binding.binaryMessenger,
            RingKitPermissionsApiImpl(binding.applicationContext),
        )
        StreamCallEventsStreamHandler.register(binding.binaryMessenger, sinkHandler(engineId))

        // §8.10: reconciliation sweep at every native entry point.
        RingKitCore.sweepOnEntry(binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        RingKitHostApi.setUp(binding.binaryMessenger, null)
        RingKitPermissionsApi.setUp(binding.binaryMessenger, null)
        EngineRegistry.unregister(engineId)
        core = null
    }

    // ── ActivityAware: Activity attachment designates the engine (§10.3) ─

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        EngineRegistry.setActivityAttached(engineId, true)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        // Config change: attachment returns momentarily; designation keeps
        // falling back to most-recently-attached meanwhile — no action needed.
        EngineRegistry.setActivityAttached(engineId, false)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        EngineRegistry.setActivityAttached(engineId, true)
    }

    override fun onDetachedFromActivity() {
        EngineRegistry.setActivityAttached(engineId, false)
    }

    private fun sinkHandler(id: Int): StreamCallEventsStreamHandler =
        object : StreamCallEventsStreamHandler() {
            override fun onListen(p0: Any?, sink: PigeonEventSink<CallEventRecord>) {
                EngineRegistry.setSink(id, sink)
            }

            override fun onCancel(p0: Any?) {
                EngineRegistry.setSink(id, null)
            }
        }
}
