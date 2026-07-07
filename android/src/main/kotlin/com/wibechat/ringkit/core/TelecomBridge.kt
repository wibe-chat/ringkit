package com.wibechat.ringkit.core

/**
 * Seam for the flag-gated telecom module (PLAN §8.7, D7). `core/` drives Telecom
 * exclusively through this interface; `telecom/TelecomAdapter` implements it
 * against `androidx.core:core-telecom`.
 *
 * The interface is deliberately **core-telecom-symbol-free** (uses only core
 * types + primitives) for three reasons:
 *  1. a telecom-OFF path never classloads `TelecomAdapter` (⇒ never classloads
 *     core-telecom) — the orchestrator only touches the provider on the
 *     telecom-enabled branch;
 *  2. Robolectric can fake it — a real `CallsManager` needs a live system
 *     Telecom service, so the *wiring* (ordering, §8.11 bypass, fallback,
 *     mirroring) is unit-tested against a fake and the real adapter is
 *     device-verified;
 *  3. it keeps the federation seam (§13.2) clean.
 *
 * **Contract:** every method is best-effort and runs on the writer executor.
 * [addCall] returning false (registration/addCall failure) ⇒ the caller falls
 * back to non-telecom behavior and re-evaluates §8.11 as telecom-OFF (§8.7).
 * The mirror hooks no-op for an unknown callId.
 */
interface TelecomBridge {

    /**
     * Lazily `registerAppWithTelecom` (first telecom-enabled show) then `addCall`
     * for this RINGING call — invoked **before** the notification is posted so
     * that, by post time, Telecom already reports the app in a call and NMS
     * exempts the CallStyle notification from any notifications-disabled block
     * (§8.11 row 2, the notifications-blocked bypass; D7 bonus).
     *
     * Returns true iff the call is now registered with Telecom. Any failure
     * (registration denied, addCall threw/timed out, cold-process quirk) returns
     * false — the call is NOT known to Telecom and the caller must fall back.
     *
     * [hooks] route system/hardware-initiated transitions (BT/watch/wired answer,
     * car disconnect — §8.7, R30) back into the standard native action paths.
     */
    fun addCall(call: CoreIncomingCall, hooks: TelecomHooks): Boolean

    /** Mirror a local accept (→ ACCEPTED) into Telecom (answer). No-op if unknown. */
    fun onLocalAnswer(callId: String)

    /** Mirror `setCallConnected` (→ ACTIVE) into Telecom (setActive). No-op if unknown. */
    fun onLocalActive(callId: String)

    /** Mirror a terminal transition into Telecom `disconnect(cause)`; drops the call. */
    fun onLocalDisconnect(callId: String, cause: TelecomDisconnectCause)

    /** True once `registerAppWithTelecom` has succeeded this process (diagnostics). */
    val isRegistered: Boolean
}

/**
 * System/hardware-initiated call transitions routed back into core (§8.7, R30).
 * The orchestrator supplies lambdas capturing only process-wide references
 * (appContext + [RingKitCore] singleton), so they safely outlive the transient
 * orchestrator that created them and re-enter on the writer executor.
 */
class TelecomHooks(
    /** BT/watch/wired-headset answer → the standard Accept path (M5.3). */
    val onHardwareAnswer: (callId: String) -> Unit,
    /** Hardware/system disconnect (e.g. car "end") → the standard decline/end path. */
    val onHardwareDisconnect: (callId: String) -> Unit,
    /** Telecom requested inactive (hold) — trace-only in v1. */
    val onSetInactive: (callId: String) -> Unit = {},
    /** Telecom requested active (unhold) — trace-only in v1. */
    val onSetActive: (callId: String) -> Unit = {},
)

/**
 * SDK-free disconnect cause; the adapter maps it to `android.telecom.DisconnectCause`.
 * Derived from the winning terminal event (§5.1): DECLINED→REJECTED, TIMED_OUT→MISSED,
 * CANCELLED→REMOTE, ENDED(local)→LOCAL, ENDED(remote)→REMOTE, ENDED(failed)→ERROR.
 */
enum class TelecomDisconnectCause { LOCAL, REMOTE, REJECTED, MISSED, ERROR }
