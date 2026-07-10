package com.wibechat.ringkit.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.wibechat.ringkit.core.CoreAudioRoute
import com.wibechat.ringkit.core.CoreCallType
import com.wibechat.ringkit.core.CoreIncomingCall
import com.wibechat.ringkit.core.TelecomBridge
import com.wibechat.ringkit.core.TelecomDisconnectCause
import com.wibechat.ringkit.core.TelecomHooks
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only core-telecom-aware class (PLAN §8.7 / §6.3, D7). Implements
 * [TelecomBridge] against `androidx.core:core-telecom` 1.0.1: lazy
 * `registerAppWithTelecom`, per-call `addCall`, answer/setActive/disconnect
 * mirrored from the state machine, audio-endpoint observation
 * (`AudioRouteChanged`), and `setAudioRoute` via `requestEndpointChange`.
 *
 * **Threading model.** `addCall` is a *suspend* function that wraps its `block`
 * in `coroutineScope` (bytecode-verified) — it stays suspended until every child
 * launched in the [CallControlScope] completes. The orchestrator calls the
 * mirror methods **synchronously on the writer executor**, so a per-call command
 * [Channel] bridges the two: `block` launches a drain loop (invokes the suspend
 * scope drivers) plus endpoint collectors; the writer-thread mirror calls just
 * `trySend`. On disconnect the drain loop cancels the collectors so the
 * `coroutineScope` can complete and the call ends (else infinite collectors
 * would hang it — the M5.2 self-check lesson).
 *
 * [addCall] blocks the writer *briefly* (bounded by [READY_TIMEOUT_MS]) to honor
 * addCall-BEFORE-post ordering; failure/timeout ⇒ false ⇒ non-telecom fallback.
 *
 * Real behavior is device-verified (M5.3/M5.7) — Robolectric fakes [TelecomBridge]
 * since a real `CallsManager` needs a live system Telecom service.
 */
class TelecomAdapter internal constructor(context: Context) : TelecomBridge {

    private val appContext = context.applicationContext
    private val callsManager = CallsManager(appContext)
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var registered = false
    private val registerLock = Any()

    override val isRegistered: Boolean get() = registered

    private val calls = ConcurrentHashMap<String, CallHandle>()

    private sealed interface Cmd {
        object Answer : Cmd
        object Active : Cmd
        data class Disconnect(val cause: DisconnectCause) : Cmd
    }

    private class CallHandle(
        val control: CallControlScope,
        val commands: Channel<Cmd>,
        val callType: Int,
    ) {
        /** Latest availableEndpoints (for setAudioRoute matching). */
        @Volatile var available: List<CallEndpointCompat> = emptyList()
        /** True once answered/active — preferred target for setAudioRoute. */
        @Volatile var active: Boolean = false
    }

    override fun addCall(call: CoreIncomingCall, hooks: TelecomHooks): Boolean {
        if (!ensureRegistered()) return false
        val callType =
            if (call.type == CoreCallType.VIDEO) {
                CallAttributesCompat.CALL_TYPE_VIDEO_CALL
            } else {
                CallAttributesCompat.CALL_TYPE_AUDIO_CALL
            }
        val attributes =
            CallAttributesCompat(
                call.callerName,
                handleToUri(call.callerHandle),
                CallAttributesCompat.DIRECTION_INCOMING,
                callType,
            )

        val ready = CompletableDeferred<Boolean>()
        adapterScope.launch {
            val commands = Channel<Cmd>(Channel.BUFFERED)
            try {
                callsManager.addCall(
                    attributes,
                    { _ -> hooks.onHardwareAnswer(call.id) },
                    { _ -> hooks.onHardwareDisconnect(call.id) },
                    { hooks.onSetActive(call.id) },
                    { hooks.onSetInactive(call.id) },
                ) {
                    // this: CallControlScope (a CoroutineScope) — the call is registered.
                    val control = this@addCall
                    val handle = CallHandle(control, commands, callType)
                    calls[call.id] = handle
                    ready.complete(true)

                    // §6.3: emit route changes; track availables for setAudioRoute.
                    val endpointJob =
                        launch {
                            control.currentCallEndpoint.collect { ep ->
                                hooks.onAudioRouteChanged(call.id, mapEndpoint(ep.type))
                            }
                        }
                    val availableJob =
                        launch { control.availableEndpoints.collect { handle.available = it } }

                    // Drain mirror commands until disconnect, then cancel the
                    // collectors so coroutineScope completes and the call ends.
                    launch {
                        drainCommands(handle)
                        endpointJob.cancel()
                        availableJob.cancel()
                    }
                }
            } catch (t: Throwable) {
                ready.complete(false)
            } finally {
                commands.close()
                calls.remove(call.id)
            }
        }
        return runBlocking {
            withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() }
        } ?: false
    }

    private suspend fun drainCommands(handle: CallHandle) {
        for (cmd in handle.commands) {
            when (cmd) {
                Cmd.Answer -> if (!handle.active) { handle.control.answer(handle.callType); handle.active = true }
                Cmd.Active -> if (!handle.active) { handle.control.setActive(); handle.active = true }
                is Cmd.Disconnect -> {
                    handle.control.disconnect(cmd.cause)
                    return
                }
            }
        }
    }

    override fun onLocalAnswer(callId: String) {
        calls[callId]?.commands?.trySend(Cmd.Answer)
    }

    override fun onLocalActive(callId: String) {
        calls[callId]?.commands?.trySend(Cmd.Active)
    }

    override fun onLocalDisconnect(callId: String, cause: TelecomDisconnectCause) {
        calls[callId]?.commands?.trySend(Cmd.Disconnect(mapCause(cause)))
    }

    override fun setAudioRoute(route: CoreAudioRoute): Boolean {
        // Prefer the active call; fall back to any registered telecom call.
        val handle = calls.values.firstOrNull { it.active } ?: calls.values.firstOrNull() ?: return false
        val targetType = coreRouteToEndpointType(route)
        val endpoint = handle.available.firstOrNull { it.type == targetType } ?: return false
        return try {
            // The actual route confirmation surfaces as a subsequent AudioRouteChanged
            // (currentCallEndpoint) — G3: the reported event matches reality, so we
            // only report ok when Telecom accepted the change. A rejected change
            // returns CallControlResult.Error (does NOT throw), and the request is
            // bounded so a hung call can't wedge the single writer thread forever.
            val result =
                runBlocking {
                    withTimeoutOrNull(ENDPOINT_CHANGE_TIMEOUT_MS) {
                        handle.control.requestEndpointChange(endpoint)
                    }
                }
            result is CallControlResult.Success
        } catch (t: Throwable) {
            false
        }
    }

    private fun ensureRegistered(): Boolean {
        if (registered) return true
        synchronized(registerLock) {
            if (registered) return true
            return try {
                callsManager.registerAppWithTelecom(
                    CallsManager.CAPABILITY_BASELINE or
                        CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING,
                )
                registered = true
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    private fun mapEndpoint(type: Int): CoreAudioRoute =
        when (type) {
            CallEndpointCompat.TYPE_EARPIECE -> CoreAudioRoute.EARPIECE
            CallEndpointCompat.TYPE_SPEAKER -> CoreAudioRoute.SPEAKER
            CallEndpointCompat.TYPE_BLUETOOTH -> CoreAudioRoute.BLUETOOTH
            CallEndpointCompat.TYPE_WIRED_HEADSET -> CoreAudioRoute.WIRED_HEADSET
            else -> CoreAudioRoute.UNKNOWN
        }

    private fun coreRouteToEndpointType(route: CoreAudioRoute): Int =
        when (route) {
            CoreAudioRoute.EARPIECE -> CallEndpointCompat.TYPE_EARPIECE
            CoreAudioRoute.SPEAKER -> CallEndpointCompat.TYPE_SPEAKER
            CoreAudioRoute.BLUETOOTH -> CallEndpointCompat.TYPE_BLUETOOTH
            CoreAudioRoute.WIRED_HEADSET -> CallEndpointCompat.TYPE_WIRED_HEADSET
            CoreAudioRoute.UNKNOWN -> CallEndpointCompat.TYPE_UNKNOWN
        }

    private fun mapCause(cause: TelecomDisconnectCause): DisconnectCause =
        when (cause) {
            TelecomDisconnectCause.LOCAL -> DisconnectCause(DisconnectCause.LOCAL)
            TelecomDisconnectCause.REMOTE -> DisconnectCause(DisconnectCause.REMOTE)
            TelecomDisconnectCause.REJECTED -> DisconnectCause(DisconnectCause.REJECTED)
            TelecomDisconnectCause.MISSED -> DisconnectCause(DisconnectCause.MISSED)
            TelecomDisconnectCause.ERROR -> DisconnectCause(DisconnectCause.ERROR)
        }

    private fun handleToUri(handle: String?): Uri {
        val h = handle?.takeIf { it.isNotBlank() } ?: return Uri.fromParts("sip", "unknown", null)
        val scheme = if (h.all { it.isDigit() || it in "+-() " }) "tel" else "sip"
        return Uri.fromParts(scheme, h, null)
    }

    companion object {
        /** Bounded writer block enforcing addCall-before-post; ≪ the §8.7 5 s rule. */
        const val READY_TIMEOUT_MS = 2_000L

        /** Bounds a `requestEndpointChange` so a hung call can't wedge the writer thread. */
        const val ENDPOINT_CHANGE_TIMEOUT_MS = 2_000L

        @Volatile
        private var instance: TelecomBridge? = null

        /**
         * Process-wide lazy provider (default seam target for the orchestrator).
         * Returns null if `CallsManager` can't even be constructed. Only invoked
         * on the telecom-enabled branch, so a telecom-OFF path never classloads
         * this class or core-telecom.
         */
        fun getOrCreate(context: Context): TelecomBridge? {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: runCatching { TelecomAdapter(context) }
                    .getOrNull()
                    ?.also { instance = it }
            }
        }
    }
}
