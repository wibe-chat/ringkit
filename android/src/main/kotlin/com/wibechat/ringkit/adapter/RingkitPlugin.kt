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
    private var permissionsImpl: RingKitPermissionsApiImpl? = null
    private var headless: Boolean = false

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val instance = RingKitCore.get(binding.applicationContext)
        core = instance
        engineId = System.identityHashCode(binding.binaryMessenger)
        // §10.2 rung 2: the runner flags the headless engine it just booted so it
        // is registered non-designated — a background isolate must never become
        // the designated delivery engine (§10.3).
        headless = BackgroundEngineRunner.consumeExpectingHeadless()
        EngineRegistry.register(engineId, headless = headless)

        // Commands are isolate-safe and available in every engine, incl. headless
        // (§6.1 — a background handler may declineCall/endCall).
        RingKitHostApi.setUp(
            binding.binaryMessenger,
            RingKitHostApiImpl(binding.applicationContext, instance, engineId),
        )

        if (headless) {
            // §10.2 rung 2: the dispatcher's private channel drives the EventLog
            // lease protocol directly. No live stream, no liveSink (never
            // designated), no permissions surface (no UI in a headless isolate).
            RingKitBackgroundApi.setUp(
                binding.binaryMessenger,
                RingKitBackgroundApiImpl(instance, onFinish = { BackgroundDeliveryCoordinator.signalFinished() }),
            )
        } else {
            // §10.2 rung 1 bridge: the orchestrator emits EVERY transition's events
            // (Dart-initiated AND native-origin — receiver declines, service
            // timeouts, sweep kills, evictions) through this single sink; the
            // registry resolves the designated engine at emission time, so one
            // registration serves all engine churn.
            instance.liveSink = { events, suppress ->
                EngineRegistry.emitToDesignated(
                    events.map { PigeonMapper.eventRecord(it, com.wibechat.ringkit.core.CoreDeliveryPath.LIVE, suppress) },
                )
            }
            val permissions = RingKitPermissionsApiImpl(binding.applicationContext)
            permissionsImpl = permissions
            RingKitPermissionsApi.setUp(binding.binaryMessenger, permissions)
            StreamCallEventsStreamHandler.register(binding.binaryMessenger, sinkHandler(engineId))
        }

        // §8.10: reconciliation sweep at every native entry point.
        RingKitCore.sweepOnEntry(binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        RingKitHostApi.setUp(binding.binaryMessenger, null)
        if (headless) {
            RingKitBackgroundApi.setUp(binding.binaryMessenger, null)
        } else {
            RingKitPermissionsApi.setUp(binding.binaryMessenger, null)
        }
        EngineRegistry.unregister(engineId)
        permissionsImpl = null
        core = null
    }

    // ── ActivityAware: Activity attachment designates the engine (§10.3)
    //    and provides the runtime-permission surface (§11.2) ──────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        EngineRegistry.setActivityAttached(engineId, true)
        permissionsImpl?.attachActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        // Config change: attachment returns momentarily; designation keeps
        // falling back to most-recently-attached meanwhile — no action needed.
        EngineRegistry.setActivityAttached(engineId, false)
        permissionsImpl?.detachActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        EngineRegistry.setActivityAttached(engineId, true)
        permissionsImpl?.attachActivity(binding)
    }

    override fun onDetachedFromActivity() {
        EngineRegistry.setActivityAttached(engineId, false)
        permissionsImpl?.detachActivity()
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
