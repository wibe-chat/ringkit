package com.wibechat.ringkit.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
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
 * The only core-telecom-aware class (PLAN §8.7, D7). Implements [TelecomBridge]
 * against `androidx.core:core-telecom` 1.0.1: lazy `registerAppWithTelecom` at
 * the first telecom-enabled show, per-call `addCall`, and answer/setActive/
 * disconnect mirrored from the state machine.
 *
 * **Threading model.** `core-telecom`'s `addCall` is a *suspend* function that
 * stays suspended for the whole lifetime of the call, and its `block` receives a
 * [CallControlScope] which is itself a `CoroutineScope` whose drivers
 * (`answer`/`setActive`/`disconnect`) are suspend. The orchestrator, however,
 * calls the [TelecomBridge] mirror methods **synchronously on the writer
 * executor**. We bridge the two with a per-call command [Channel]: inside the
 * `block` we launch a loop that drains commands and invokes the scope drivers;
 * the writer-thread mirror calls just `trySend` into the channel (non-blocking).
 * The call ends when the drain loop calls `disconnect`.
 *
 * [addCall] blocks the writer *briefly* (bounded by [READY_TIMEOUT_MS]) to honor
 * the addCall-BEFORE-post ordering: the caller must not post the notification
 * until Telecom reports the call, or the notifications-blocked bypass (§8.11
 * row 2) races and fails. On timeout/failure it returns false and the caller
 * falls back to non-telecom (never blocks ringing, §8.7).
 *
 * The fine callback semantics (hardware answer, hold/unhold) are device-verified
 * in M5.3/M5.7 — Robolectric fakes [TelecomBridge] since a real `CallsManager`
 * needs a live system Telecom service.
 */
class TelecomAdapter internal constructor(context: Context) : TelecomBridge {

    private val appContext = context.applicationContext
    private val callsManager = CallsManager(appContext)
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var registered = false
    private val registerLock = Any()

    override val isRegistered: Boolean get() = registered

    private val calls = ConcurrentHashMap<String, Channel<Cmd>>()

    /** Commands pushed from the writer thread into a call's suspend loop. */
    private sealed interface Cmd {
        object Answer : Cmd
        object Active : Cmd
        data class Disconnect(val cause: DisconnectCause) : Cmd
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

        // Completed from inside the addCall block once Telecom has the call.
        val ready = CompletableDeferred<Boolean>()
        adapterScope.launch {
            val commands = Channel<Cmd>(Channel.BUFFERED)
            try {
                // Positional args (the SDK's Kotlin param names are not part of
                // the verified public signature): attrs, onAnswer(callType),
                // onDisconnect(cause), onSetActive, onSetInactive, then block.
                callsManager.addCall(
                    attributes,
                    { _ -> hooks.onHardwareAnswer(call.id) },
                    { _ -> hooks.onHardwareDisconnect(call.id) },
                    { hooks.onSetActive(call.id) },
                    { hooks.onSetInactive(call.id) },
                ) {
                    // this: CallControlScope (a CoroutineScope). The call is now
                    // registered with Telecom — signal the waiting writer, then
                    // launch the drain loop that owns the call's lifetime.
                    calls[call.id] = commands
                    ready.complete(true)
                    launch { drainCommands(this@addCall, commands, callType) }
                }
            } catch (t: Throwable) {
                // Registration/addCall failure (incl. cold-process quirks, §8.7):
                // signal fallback; the caller re-evaluates §8.11 as telecom-OFF.
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

    /** Drains writer-pushed commands into the suspend scope drivers until disconnect. */
    private suspend fun drainCommands(control: CallControlScope, commands: Channel<Cmd>, callType: Int) {
        var active = false
        for (cmd in commands) {
            when (cmd) {
                Cmd.Answer -> if (!active) { control.answer(callType); active = true }
                Cmd.Active -> if (!active) { control.setActive(); active = true }
                is Cmd.Disconnect -> {
                    control.disconnect(cmd.cause)
                    return // returning ends the drain ⇒ the call session ends
                }
            }
        }
    }

    override fun onLocalAnswer(callId: String) {
        calls[callId]?.trySend(Cmd.Answer)
    }

    override fun onLocalActive(callId: String) {
        calls[callId]?.trySend(Cmd.Active)
    }

    override fun onLocalDisconnect(callId: String, cause: TelecomDisconnectCause) {
        calls[callId]?.trySend(Cmd.Disconnect(mapCause(cause)))
    }

    /** Registers once per process; any failure ⇒ false ⇒ non-telecom fallback. */
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

    private fun mapCause(cause: TelecomDisconnectCause): DisconnectCause =
        when (cause) {
            TelecomDisconnectCause.LOCAL -> DisconnectCause(DisconnectCause.LOCAL)
            TelecomDisconnectCause.REMOTE -> DisconnectCause(DisconnectCause.REMOTE)
            TelecomDisconnectCause.REJECTED -> DisconnectCause(DisconnectCause.REJECTED)
            TelecomDisconnectCause.MISSED -> DisconnectCause(DisconnectCause.MISSED)
            TelecomDisconnectCause.ERROR -> DisconnectCause(DisconnectCause.ERROR)
        }

    /** core-telecom requires a schema Uri address; map handles best-effort. */
    private fun handleToUri(handle: String?): Uri {
        val h = handle?.takeIf { it.isNotBlank() } ?: return Uri.fromParts("sip", "unknown", null)
        val scheme = if (h.all { it.isDigit() || it in "+-() " }) "tel" else "sip"
        return Uri.fromParts(scheme, h, null)
    }

    companion object {
        /** Bounded writer block enforcing addCall-before-post; ≪ the §8.7 5 s rule. */
        const val READY_TIMEOUT_MS = 2_000L

        @Volatile
        private var instance: TelecomBridge? = null

        /**
         * Process-wide lazy provider (default seam target for the orchestrator).
         * Returns null if `CallsManager` can't even be constructed (⇒ the caller
         * proceeds telecom-OFF). Only invoked on the telecom-enabled branch, so a
         * telecom-OFF path never classloads this class or core-telecom.
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
