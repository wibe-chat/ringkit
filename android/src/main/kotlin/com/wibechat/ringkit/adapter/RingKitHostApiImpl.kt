package com.wibechat.ringkit.adapter

import android.content.Context
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreDeliveryPath
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.ui.CallNotificationFactory
import java.io.File
import java.security.MessageDigest

/**
 * Pigeon host-API implementation (PLAN §6.1, §8.9): every method hops to the
 * single writer executor and replies through the async callback — the platform
 * thread is never blocked. One instance exists per attached engine; ack calls
 * from non-designated engines are ignored (§10.3 step 1).
 *
 * M2: commands route through the IncomingCallOrchestrator — repository
 * transition + §8.11 evaluation + real UI/service effects + live delivery.
 */
internal class RingKitHostApiImpl(
    private val context: Context,
    private val core: RingKitCore.Instance,
    private val engineId: Int,
) : RingKitHostApi {

    private val orchestrator = IncomingCallOrchestrator(context, core)

    private fun <T> onWriter(callback: (Result<T>) -> Unit, block: () -> T) {
        core.writer.submit(block) { result ->
            result.exceptionOrNull()?.let { t ->
                core.ringLog.incident(
                    com.wibechat.ringkit.core.RingLog.IncidentType.BOUNDARY_EXCEPTION,
                    "RingKitHostApiImpl", null, throwable = t,
                )
            }
            callback(result)
        }
    }

    // Live-path delivery happens INSIDE the orchestrator via core.liveSink
    // (§10.2 rung 1, source-agnostic — registered in RingkitPlugin): host-API
    // commands and native-origin transitions share one delivery point.

    override fun initialize(config: RingKitConfig, callback: (Result<Unit>) -> Unit) =
        onWriter(callback) {
            val resolved = extractFlutterAssetRingtone(PigeonMapper.toCore(config))
            val applied = core.repository.updateConfig(resolved)
            core.ringLog.level = applied.logLevel
            // §8.2: pre-create channels (the audible fallback must exist before
            // the first degraded show ever needs it).
            CallNotificationFactory(context, applied, core.time, core.ringLog, core.repository::notificationIdFor)
                .ensureChannels()
            Unit
        }

    override fun showIncomingCall(call: IncomingCall, callback: (Result<ShowCallResult>) -> Unit) =
        onWriter(callback) {
            val outcome = orchestrator.showOnWriter(PigeonMapper.toCore(call))
            PigeonMapper.showResult(outcome)
        }

    override fun acceptCall(callId: String, callback: (Result<CallCommandResult>) -> Unit) =
        onWriter(callback) {
            // Programmatic accept (§4.3): same native path, no launch action.
            val result = orchestrator.acceptProgrammaticOnWriter(callId)
            PigeonMapper.commandResult(result.outcome)
        }

    override fun declineCall(callId: String, callback: (Result<CallCommandResult>) -> Unit) =
        onWriter(callback) {
            val result = orchestrator.declineOnWriter(callId)
            PigeonMapper.commandResult(result.outcome)
        }

    override fun endCall(callId: String, reason: EndReason, callback: (Result<CallCommandResult>) -> Unit) =
        onWriter(callback) {
            val result = orchestrator.endOnWriter(callId, PigeonMapper.endReasonToCore(reason))
            PigeonMapper.commandResult(result.outcome)
        }

    override fun cancelCall(callId: String, reason: CancelReason, callback: (Result<CallCommandResult>) -> Unit) =
        onWriter(callback) {
            val result = orchestrator.cancelOnWriter(callId, PigeonMapper.cancelReasonToCore(reason))
            PigeonMapper.commandResult(result.outcome)
        }

    override fun endAllCalls(callback: (Result<Unit>) -> Unit) =
        onWriter(callback) {
            orchestrator.endAllOnWriter()
            Unit
        }

    override fun setCallConnected(callId: String, callback: (Result<CallCommandResult>) -> Unit) =
        onWriter(callback) {
            val result = orchestrator.setConnectedOnWriter(callId)
            PigeonMapper.commandResult(result.outcome)
        }

    override fun startOutgoingCall(call: OutgoingCall, callback: (Result<ShowCallResult>) -> Unit) =
        onWriter(callback) {
            val result = orchestrator.startOutgoingOnWriter(PigeonMapper.toCore(call))
            PigeonMapper.showResult(result.outcome)
        }

    override fun updateCallInfo(
        callId: String,
        update: CallInfoUpdate,
        callback: (Result<CallCommandResult>) -> Unit,
    ) =
        onWriter(callback) {
            val result = orchestrator.updateInfoOnWriter(callId, PigeonMapper.toCore(update))
            PigeonMapper.commandResult(result.outcome)
        }

    override fun setAudioRoute(route: AudioRoute, callback: (Result<CallCommandResult>) -> Unit) =
        onWriter(callback) {
            // Telecom module only (§6.1); flag-gated behavior lands in Phase 4.
            CallCommandResult.WRONG_STATE
        }

    override fun activeCalls(callback: (Result<List<ActiveCall>>) -> Unit) =
        onWriter(callback) {
            core.repository.activeCalls().map { PigeonMapper.activeCall(it) }
        }

    override fun takePendingEvents(callback: (Result<List<CallEventRecord>>) -> Unit) =
        onWriter(callback) {
            core.repository.eventLog.takePending().map {
                PigeonMapper.eventRecord(it, CoreDeliveryPath.REPLAYED)
            }
        }

    override fun ackEvents(upToSeq: Long, deliveredSequences: List<Long>, callback: (Result<Unit>) -> Unit) =
        onWriter(callback) {
            // §10.3: exactly one engine consumes — foreign acks can never
            // silently eat events the designated stream hasn't seen.
            if (EngineRegistry.isDesignated(engineId)) {
                core.repository.eventLog.ack(upToSeq, deliveredSequences)
            }
            Unit
        }

    override fun consumeLaunchAction(callback: (Result<CallLaunchAction?>) -> Unit) =
        onWriter(callback) {
            core.repository.consumeLaunchAction()?.let { PigeonMapper.launchAction(it) }
        }

    override fun registerBackgroundEventHandler(
        dispatcherHandle: Long,
        handlerHandle: Long,
        callback: (Result<Unit>) -> Unit,
    ) =
        onWriter(callback) {
            // Persisted as 64-bit longs (§10.4); the headless runner reads them in M3.
            core.store.writeBackgroundHandles(dispatcherHandle, handlerHandle)
        }

    override fun diagnose(callback: (Result<DiagnosticsReport>) -> Unit) =
        onWriter(callback) {
            // Diagnostics v1 lands in M3 (§11.3); this is the honest M2 subset.
            val log = core.repository.eventLog
            DiagnosticsReport(
                findings = emptyList(),
                eventLogHealth =
                    EventLogHealth(
                        sizeBytes = 0L,
                        unconsumedCount = log.unconsumedCount().toLong(),
                        droppedEvents = log.droppedCount().toLong(),
                        quarantinedEntries = core.store.quarantinedCount.toLong(),
                        activeLeases = log.activeLeaseCount().toLong(),
                    ),
                lastShowResult = null,
                lastDeliveryPath = null,
                ringTraces =
                    core.trace.buffer.entries.map { "[${it.tag}] ${it.callId ?: "-"}: ${it.message}" },
                generatedAtEpochMs = core.time.wallMs(),
            )
        }

    // ── flutterAsset ringtone extraction (§8.4: config-level only) ────────

    /**
     * `flutterAsset:` sources are APK-internal asset keys, not filesystem
     * paths — resolved via FlutterLoader at initialize() and EXTRACTED (copied)
     * to noBackupFilesDir so cold-native playback needs no Flutter (§8.4).
     */
    private fun extractFlutterAssetRingtone(config: CoreConfig): CoreConfig {
        val source = config.defaultRingtone ?: return config
        if (!source.startsWith("flutterAsset:")) return config
        return try {
            val assetPath = source.removePrefix("flutterAsset:")
            val lookupKey =
                io.flutter.FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(assetPath)
            val hash =
                MessageDigest.getInstance("SHA-256").digest(assetPath.toByteArray())
                    .joinToString("") { "%02x".format(it) }.take(16)
            val extension = assetPath.substringAfterLast('.', "dat")
            val outDir = File(File(context.noBackupFilesDir, "ringkit"), "assets")
            outDir.mkdirs()
            val outFile = File(outDir, "ringtone_$hash.$extension")
            if (!outFile.exists()) {
                context.assets.open(lookupKey).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            config.copy(defaultRingtone = "file://${outFile.absolutePath}")
        } catch (e: Exception) {
            core.ringLog.log(
                CoreLogLevel.ERROR, "config", "RingKitHostApiImpl.extractFlutterAssetRingtone", null,
                "flutterAsset ringtone extraction failed — using device default",
                mapOf("source" to source), e,
            )
            config.copy(defaultRingtone = null)
        }
    }
}
