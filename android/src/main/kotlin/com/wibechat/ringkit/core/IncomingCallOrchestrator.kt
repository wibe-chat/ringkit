package com.wibechat.ringkit.core

import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wibechat.ringkit.services.OngoingCallService
import com.wibechat.ringkit.services.RingingService
import com.wibechat.ringkit.ui.AvatarLoader
import com.wibechat.ringkit.ui.CallNotificationFactory
import com.wibechat.ringkit.ui.IncomingCallActivity

/**
 * The single entry for every call action (PLAN §4.1/§4.3): constructs from
 * appContext + persisted state only (D4 — nothing here depends on the plugin
 * being attached, R1), dispatches to the repository on the writer, evaluates
 * the §8.11 precondition matrix on show, and maps CoreEffect tags to real
 * notifications/services/activities. Rejected/losing transitions arrive with
 * zero effects and stay that way (§5.1).
 *
 * Threading: every public entry runs its work on the single writer executor;
 * `*OnWriter` variants are for callers already there (receiver/service paths).
 */
class IncomingCallOrchestrator(
    private val context: Context,
    private val core: RingKitCore.Instance,
    /** §10.2 rung 2 seam: trigger durable headless delivery (tests assert routing without a real host). */
    private val backgroundDeliveryStarter: (Context) -> Unit = { com.wibechat.ringkit.services.BackgroundDeliveryHost.deliver(it) },
    /**
     * §8.7 seam: the flag-gated telecom module. The default lazily creates
     * [TelecomBridge] via `TelecomAdapter.getOrCreate` — referenced ONLY inside
     * this lambda, so a telecom-OFF path never classloads telecom/core-telecom.
     * Returns null when core-telecom can't initialize (⇒ non-telecom fallback).
     * Robolectric injects a fake to assert ordering/§8.11-bypass/fallback/mirroring.
     */
    private val telecomProvider: (Context) -> TelecomBridge? = { com.wibechat.ringkit.telecom.TelecomAdapter.getOrCreate(it) },
    /** Seam: Robolectric can't make startForegroundService throw (§8.11 fgsDenied tests). Kept LAST so existing trailing-lambda call sites bind here. */
    private val serviceStarter: (Context, Intent) -> Unit = { ctx, intent -> ctx.startForegroundService(intent) },
) {
    private val appContext = context.applicationContext
    private val repository = core.repository
    private val ringLog = core.ringLog

    private fun factory(): CallNotificationFactory =
        CallNotificationFactory(
            appContext,
            repository.currentConfig(),
            core.time,
            ringLog,
            repository::notificationIdFor,
        )

    private fun avatarLoader(): AvatarLoader = core.avatarLoader

    // ── telecom (§8.7 — flag-gated module) ───────────────────────────────

    /**
     * Hardware/system-initiated transitions (BT/watch/wired answer, car "end")
     * re-enter core on the writer via the process-wide singleton — safe to
     * capture, outlives this transient orchestrator (R30, M5.3).
     */
    private fun telecomHooks(): TelecomHooks =
        TelecomHooks(
            onHardwareAnswer = { callId ->
                core.writer.post { hookOrchestrator().acceptFromActionOnWriter(callId) }
            },
            onHardwareDisconnect = { callId ->
                core.writer.post {
                    val orch = hookOrchestrator()
                    if (repository.callFor(callId)?.state == CoreCallState.RINGING) {
                        orch.declineOnWriter(callId)
                    } else {
                        orch.endOnWriter(callId, CoreEndReason.LOCAL)
                    }
                }
            },
            onSetActive = { callId -> trace("telecom", callId, "onSetActive (unhold)") },
            onSetInactive = { callId -> trace("telecom", callId, "onSetInactive (hold)") },
            onAudioRouteChanged = { callId, route ->
                core.writer.post { hookOrchestrator().emitAudioRouteChanged(callId, route) }
            },
        )

    /**
     * A fresh orchestrator for a hardware/endpoint callback re-entering on the
     * writer — carries THIS orchestrator's [telecomProvider] so the same bridge
     * (the process singleton in prod, the injected fake in tests) is reused.
     */
    private fun hookOrchestrator(): IncomingCallOrchestrator =
        IncomingCallOrchestrator(appContext, core, telecomProvider = telecomProvider)

    /**
     * §6.3: a live-only `AudioRouteChanged` (never appended/replayed — §6.2) to
     * the designated engine. Endpoint changes originate off the writer (the
     * adapter's collector), so this is posted back onto it.
     */
    fun emitAudioRouteChanged(callId: String, route: CoreAudioRoute) {
        val record = repository.callFor(callId) ?: return
        val event =
            CoreEvent(
                seq = repository.eventLog.drawSeq(),
                callId = callId,
                type = CoreEventType.AUDIO_ROUTE_CHANGED,
                callInfo = record.info,
                wallMs = core.time.wallMs(),
                elapsedMs = core.time.elapsedMs(),
                bootCount = core.time.bootCount(),
                audioRoute = route,
            )
        core.liveSink?.invoke(listOf(event), null)
    }

    /**
     * §6.1 `setAudioRoute` — telecom module only. `wrongState` (false) when
     * telecom is off or there is no active telecom call; otherwise the bridge's
     * honest ok/wrongState from `requestEndpointChange`. Never a stub.
     */
    fun setAudioRouteOnWriter(route: CoreAudioRoute): Boolean {
        if (!repository.currentConfig().telecomIntegration) return false
        val bridge = telecomProvider(appContext) ?: return false
        return bridge.setAudioRoute(route)
    }

    /**
     * §8.7 answer/setActive/disconnect mirroring: reflect a transition's new
     * state into Telecom. Only touches the bridge when telecom is enabled for
     * the call (a non-telecom process never classloads telecom/); no-op for a
     * callId the bridge doesn't know. Derives the action from the winning
     * persisted events (§5.1) + CHRONOMETER_START (setCallConnected → ACTIVE).
     */
    private fun mirrorTelecom(result: TransitionResult, pre: CallRecord?) {
        val record = pre ?: return
        val enabled = record.options.useTelecom ?: repository.currentConfig().telecomIntegration
        if (!enabled) return
        val bridge = telecomProvider(appContext) ?: return
        result.persistedEvents.forEach { ev ->
            when (ev.type) {
                CoreEventType.ACCEPTED -> bridge.onLocalAnswer(ev.callId)
                CoreEventType.DECLINED -> bridge.onLocalDisconnect(ev.callId, TelecomDisconnectCause.REJECTED)
                CoreEventType.TIMED_OUT -> bridge.onLocalDisconnect(ev.callId, TelecomDisconnectCause.MISSED)
                CoreEventType.CANCELLED -> bridge.onLocalDisconnect(ev.callId, causeForCancel(ev.cancelReason))
                CoreEventType.ENDED -> bridge.onLocalDisconnect(ev.callId, causeForEnd(ev.endReason))
                else -> {}
            }
        }
        if (CoreEffect.CHRONOMETER_START in result.effects) bridge.onLocalActive(record.callId)
    }

    private fun causeForCancel(reason: CoreCancelReason?): TelecomDisconnectCause =
        when (reason) {
            CoreCancelReason.LOCAL_DISMISS -> TelecomDisconnectCause.LOCAL
            else -> TelecomDisconnectCause.REMOTE
        }

    private fun causeForEnd(reason: CoreEndReason?): TelecomDisconnectCause =
        when (reason) {
            CoreEndReason.REMOTE -> TelecomDisconnectCause.REMOTE
            CoreEndReason.FAILED -> TelecomDisconnectCause.ERROR
            else -> TelecomDisconnectCause.LOCAL
        }

    // ── show (§8.11 evaluation happens HERE) ─────────────────────────────

    data class ShowOutcome(
        val result: TransitionResult,
        val failReason: CoreFailReason? = null,
        val degradations: Set<CoreDegradeReason> = emptySet(),
        val suppressReason: CoreSuppressReason? = null,
    )

    fun showOnWriter(call: CoreIncomingCall, fcmDowngraded: Boolean = false): ShowOutcome {
        val result = repository.dispatch(TransitionInput.Show(call))
        // §5.2 cap evictions ride the admission result: execute the victims'
        // teardown + missed-call surfaces BEFORE the new call's own effects.
        result.evictions.forEach { eviction ->
            executeEviction(eviction)
        }
        val outcome =
            when (result.outcome) {
                TransitionOutcome.SHOWN -> {
                    val o = executeShow(call, result, fcmDowngraded)
                    traceShow(call.id, o)
                    o
                }
                TransitionOutcome.EXPIRED -> {
                    // Dead on arrival (staleness guard / cap-1 eviction, §5.1):
                    // TIMED_OUT recorded directly; the only effect is the
                    // missed-call surface — never a ring.
                    if (CoreEffect.MISSED_CALL in result.effects) {
                        postMissedFor(call.id, repository.tombstoneFor(call.id), call.options.missedCall)
                    }
                    publish(call.id, result)
                    ShowOutcome(result)
                }
                TransitionOutcome.INVALID_PARAMS ->
                    ShowOutcome(result, failReason = CoreFailReason.INVALID_PARAMS)
                else -> ShowOutcome(result) // alreadyShown/alreadyActive/alreadyTerminal: reject·0
            }
        deliverLive(result, outcome.suppressReason)
        recordLastShow(outcome)
        return outcome
    }

    /** §11.3 breadcrumb: diagnose() reports the last show result verbatim. */
    private fun recordLastShow(outcome: ShowOutcome) {
        runCatching {
            val json = org.json.JSONObject()
            json.put("outcome", outcome.result.outcome.name)
            json.put("fail", outcome.failReason?.name)
            json.put("degradations", org.json.JSONArray(outcome.degradations.map { it.name }))
            core.store.writeLastShowResult(json.toString())
        }
    }

    /** §5.2 eviction effects: victim teardown + missed-call from its snapshot. */
    private fun executeEviction(eviction: Eviction) {
        val result = eviction.result
        avatarLoader().cancel(eviction.callId)
        core.watchdogs.cancel(eviction.callId)
        repository.notificationIdFor(eviction.callId)?.let { factory().cancel(it) }
        if (CoreEffect.MISSED_CALL in result.effects) {
            postMissedFor(eviction.callId, repository.tombstoneFor(eviction.callId), eviction.missedCall)
        }
        // §8.7: eviction (§5.2) bypasses executeEffects, so an evicted telecom
        // call would never disconnect — leaking its addCall coroutine (which is
        // suspended in core-telecom's `coroutineScope`, bytecode-verified). Mirror
        // the TIMED_OUT terminal here too. Config-gated to avoid classloading
        // telecom/ in a non-telecom process; no-op for a callId the bridge doesn't know.
        if (repository.currentConfig().telecomIntegration) {
            telecomProvider(appContext)?.onLocalDisconnect(eviction.callId, TelecomDisconnectCause.MISSED)
        }
        publish(eviction.callId, result)
    }

    /**
     * §8.11 (evaluated in row order; `failed` short-circuits, degradations
     * accumulate). Telecom rows are Phase 4 — matrix evaluates as telecom-OFF.
     */
    private fun executeShow(call: CoreIncomingCall, result: TransitionResult, fcmDowngraded: Boolean): ShowOutcome {
        val record = repository.callFor(call.id) ?: return ShowOutcome(result)
        val factory = factory()
        val readback = factory.ensureChannels()
        val degradations = mutableSetOf<CoreDegradeReason>()

        // §8.11 fcmDowngraded (Mode-2-only, R28): FCM delivered a high-priority push
        // at normal priority → the killed-state FGS-start exemption was silently lost.
        // A transport observation independent of this show's own outcome; recorded as a
        // degradation (surfaces in the [show] trace) + incident so diagnose() can report
        // "your high-priority pushes are being de-prioritized — fix server behavior".
        if (fcmDowngraded) {
            degradations += CoreDegradeReason.FCM_DOWNGRADED
            ringLog.incident(
                RingLog.IncidentType.FCM_DOWNGRADED, "Orchestrator.executeShow", call.id,
                setOf(CoreDegradeReason.FCM_DOWNGRADED.name),
                mapOf("note" to "high-priority push delivered at normal priority (R28)"),
            )
            trace("fcm", call.id, "fcmDowngraded: delivered NORMAL, requested HIGH")
        }

        // §8.7: telecom addCall BEFORE any notification post — that ordering IS
        // the notifications-blocked bypass (§8.11 row 2): NMS exempts CallStyle
        // only while Telecom already reports the app in a call. Lazy registration
        // happens inside the bridge; ANY failure ⇒ telecom-OFF and the matrix is
        // evaluated as OFF (which can flip row 2 back to failed). Never blocks ringing.
        val config = repository.currentConfig()
        val telecomEnabled = record.options.useTelecom ?: config.telecomIntegration
        val telecomActive =
            telecomEnabled && (telecomProvider(appContext)?.addCall(call, telecomHooks()) ?: false)
        if (telecomEnabled && !telecomActive) {
            // §8.7: registration/addCall failure ⇒ log + diagnostics + fall back
            // (never blocks ringing). diagnose() surfaces the incident.
            trace("telecom", call.id, "addCall failed/unavailable — §8.11 re-evaluated as telecom-OFF")
            ringLog.incident(
                RingLog.IncidentType.TELECOM_FALLBACK, "Orchestrator.executeShow", call.id,
                data = mapOf("note" to "telecom registration/addCall failed — fell back to non-telecom"),
            )
        } else if (telecomActive) {
            trace("telecom", call.id, "addCall ok — CallStyle notifications-blocked exemption active")
        }

        // Row 1/3: notifications blocked / incoming channel blocked → failed,
        // NO surface, NO sound (invisible ringing is banned); the call stays
        // persisted with its sweep-enforced deadline (§5.2 failed-shows rule).
        // §8.11 row 2: telecom-ON exempts the app-level notifications-disabled
        // block (NOT the channel IMPORTANCE_NONE gate below — no such row).
        if (!readback.notificationsEnabled && !telecomActive) {
            ringLog.incident(RingLog.IncidentType.SHOW_FAILED, "Orchestrator.executeShow", call.id, setOf("notificationsBlocked"))
            publish(call.id, result)
            return ShowOutcome(result, failReason = CoreFailReason.NOTIFICATIONS_BLOCKED, suppressReason = CoreSuppressReason.NOTIFICATIONS_BLOCKED)
        }
        if (readback.incomingImportance == NotificationManager.IMPORTANCE_NONE) {
            ringLog.incident(RingLog.IncidentType.SHOW_FAILED, "Orchestrator.executeShow", call.id, setOf("channelBlocked"))
            publish(call.id, result)
            return ShowOutcome(result, failReason = CoreFailReason.CHANNEL_BLOCKED, suppressReason = CoreSuppressReason.CHANNEL_BLOCKED)
        }

        // foregroundBehavior: silent — the app renders its own in-app UI; no
        // surface, no sound, deadline-only sweep (§6.3/§8.11); result = shown.
        // (config hoisted above for the telecom gate.)
        if (config.foregroundBehavior == CoreForegroundBehavior.SILENT && isAppForeground()) {
            trace("show", call.id, "foreground silent mode — no surface by design")
            publish(call.id, result)
            return ShowOutcome(result, suppressReason = CoreSuppressReason.FOREGROUND_SILENT_MODE)
        }

        // Row 4: channel demoted below HIGH — proceed; FGS + our ringtone still
        // ring, no OS heads-up; deep-link remedy in diagnostics (M3).
        if (readback.incomingImportance < NotificationManager.IMPORTANCE_HIGH) {
            degradations += CoreDegradeReason.CHANNEL_DEMOTED
        }

        // Row 5: FSI permission (14+, fullScreen mode) — reporting-only; the
        // FSI is ALWAYS attached and the system applies the sticky-HUN (R18/R33).
        val behavior = record.options.lockScreenBehavior
        if (behavior == CoreLockScreenBehavior.FULL_SCREEN && !canUseFullScreenIntent()) {
            degradations += CoreDegradeReason.FSI_DENIED
        }

        // §5.2: one ringer-owner, first-come. Surface ownership and audio are
        // distinct: the FGS posts the FIRST RINGING call's notification (this
        // arrival is `waitingSurface` iff another RINGING call precedes it);
        // audio-wise the arrival is WAITING-SILENCE if it either has a
        // preceding ringer OR any call is in an in-call state (in-call
        // demotion) — waiting-silence arrivals get the short cue, never the
        // full ringtone.
        val allCalls = repository.activeCalls()
        val waitingSurface =
            allCalls.firstOrNull { it.state == CoreCallState.RINGING }?.callId != call.id
        val waitingSilence =
            waitingSurface || allCalls.any { it.state != CoreCallState.RINGING }

        // Post + ring. Normal path: the ringing FGS posts the owner notification
        // via startForeground (both lock behaviors — §8.2). FGS denial → §8.11.
        val fgsStarted = startRingingService(call.id)
        if (fgsStarted) {
            if (waitingSurface) {
                // §5.2 waiting call: notification + short attention cue only.
                // The FGS posts only the OWNER's surface — a waiting call gets a
                // plain notify() with the FSI ALWAYS attached, every mode (§8.2
                // validity invariant holds without FGS attachment).
                val post = factory.postIncoming(record, attachFsi = true)
                if (post.callStyleRejected) degradations += CoreDegradeReason.CALL_STYLE_REJECTED
                if (post.posted) repository.markSurfacePosted(call.id)
            } else {
                repository.markSurfacePosted(call.id)
            }
            // In-call arrivals keep the FGS surface but ring as waiting-silence
            // (§5.2 demotion): the service's etiquette mutes the loop; the cue
            // is the sanctioned attention signal.
            if (waitingSilence) RingingService.waitingCue(appContext, core, call.id)
        } else {
            degradations += CoreDegradeReason.FGS_DENIED
            ringLog.incident(
                RingLog.IncidentType.FGS_DENIED, "Orchestrator.executeShow", call.id,
                degradations.map { it.name }.toSet(),
            )
            // Degraded direct post: CallStyle-valid via FSI (re-attached even in
            // notificationOnly mode — documented override, §8.2); the audible
            // fallback channel supplies the ring the dead process can't. §5.2
            // etiquette: only an audible ringer-owner may ring — a
            // waiting-silence arrival gets its FSI-attached notification + the
            // short cue, never the audible one-shot or the best-effort loop.
            val wantAudibleOneShot = !waitingSilence
            val audibleHealthy = readback.audibleImportance >= NotificationManager.IMPORTANCE_DEFAULT
            if (wantAudibleOneShot && !audibleHealthy) degradations += CoreDegradeReason.CHANNEL_DEMOTED
            val post =
                factory.postIncoming(record, attachFsi = true, onAudibleChannel = wantAudibleOneShot && audibleHealthy)
            if (post.callStyleRejected) degradations += CoreDegradeReason.CALL_STYLE_REJECTED
            if (post.posted) repository.markSurfacePosted(call.id)
            if (waitingSilence) {
                RingingService.waitingCue(appContext, core, call.id)
            } else {
                // In-process best-effort sound with the §8.3 honest lifetime.
                RingingService.bestEffortInProcessRing(appContext, core, call.id)
            }
        }

        // foregroundBehavior: fullScreen — the app WANTS the takeover even while
        // unlocked-in-use; the OS won't fire the FSI there, so launch directly
        // (the app is foreground ⇒ activity start is permitted). §4.2.
        if (config.foregroundBehavior == CoreForegroundBehavior.FULL_SCREEN && isAppForeground()) {
            runCatching {
                appContext.startActivity(
                    RingKitIntents.withCall(
                        Intent(appContext, IncomingCallActivity::class.java),
                        RingKitIntents.ACTION_SHOW_FULL_SCREEN,
                        call.id,
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { trace("show", call.id, "foreground fullScreen launch failed: $it") }
        }

        kickOffAvatar(record, factory)
        publish(call.id, result)
        return ShowOutcome(result, degradations = degradations)
    }

    // ── accept (§4.3 — one code path) ────────────────────────────────────

    data class AcceptOutcome(val result: TransitionResult, val launch: LaunchAction?)

    fun acceptFromActionOnWriter(callId: String): AcceptOutcome {
        val pre = repository.callFor(callId)
        val result = repository.dispatch(TransitionInput.Accept(callId, fromAction = true))
        executeEffects(callId, result, pre)
        deliverLive(result)
        return AcceptOutcome(result, result.launchActionSet)
    }

    fun acceptProgrammaticOnWriter(callId: String): TransitionResult =
        dispatchWithEffects(callId) { TransitionInput.Accept(callId, fromAction = false) }

    fun declineOnWriter(callId: String): TransitionResult =
        dispatchWithEffects(callId) { TransitionInput.Decline(callId) }

    fun timeoutOnWriter(callId: String): TransitionResult =
        dispatchWithEffects(callId) { TransitionInput.Timeout(callId) }

    fun cancelOnWriter(callId: String, reason: CoreCancelReason): TransitionResult =
        dispatchWithEffects(callId) { TransitionInput.Cancel(callId, reason) }

    fun endOnWriter(callId: String, reason: CoreEndReason): TransitionResult =
        dispatchWithEffects(callId) { TransitionInput.End(callId, reason) }

    fun endAllOnWriter(): TransitionResult {
        val pre = repository.activeCalls().associateBy { it.callId }
        val result = repository.dispatch(TransitionInput.EndAll)
        pre.keys.forEach { callId -> executeEffects(callId, result, pre[callId]) }
        deliverLive(result)
        return result
    }

    fun setConnectedOnWriter(callId: String): TransitionResult =
        dispatchWithEffects(callId) { TransitionInput.SetConnected(callId) }

    fun startOutgoingOnWriter(call: CoreOutgoingCall): TransitionResult =
        dispatchWithEffects(call.id) { TransitionInput.StartOutgoing(call) }

    fun updateInfoOnWriter(callId: String, update: CoreCallInfoUpdate): TransitionResult =
        dispatchWithEffects(callId) { TransitionInput.UpdateInfo(callId, update) }

    fun watchdogFireOnWriter(callId: String): TransitionResult {
        val pre = repository.callFor(callId)
        val result = repository.dispatch(TransitionInput.WatchdogFire(callId))
        if (result.outcome == TransitionOutcome.OK) {
            ringLog.incident(RingLog.IncidentType.WATCHDOG_FIRED, "Orchestrator.watchdogFire", callId)
        }
        executeEffects(callId, result, pre)
        deliverLive(result)
        return result
    }

    /** §5.1 task-removal row (OngoingCallService.onTaskRemoved → here). */
    fun taskRemovedOnWriter(callId: String): TransitionResult =
        dispatchWithEffects(callId) { TransitionInput.TaskRemoved(callId) }

    /** Pre-dispatch record capture: terminal transitions erase the live record,
     *  but missed-call options and identity must survive into effect execution. */
    private inline fun dispatchWithEffects(callId: String, input: () -> TransitionInput): TransitionResult {
        val pre = repository.callFor(callId)
        val result = repository.dispatch(input())
        executeEffects(callId, result, pre)
        deliverLive(result)
        return result
    }

    /** Missed-call tap (§4.3): auto-cancel FIRST, then event + launch action. */
    fun missedTappedOnWriter(callId: String): LaunchAction? {
        repository.notificationIdFor(callId)?.let {
            factory().cancel(CallNotificationFactory.missedIdFor(it))
        }
        val tombstone = repository.tombstoneFor(callId) ?: return null
        val info = tombstone.callInfo ?: return null
        val seq = repository.eventLog.drawSeq()
        val event =
            CoreEvent(
                seq = seq,
                callId = callId,
                type = CoreEventType.MISSED_CALL_TAPPED,
                callInfo = info,
                wallMs = core.time.wallMs(),
                elapsedMs = core.time.elapsedMs(),
                bootCount = core.time.bootCount(),
            )
        repository.eventLog.append(event)
        val launch = LaunchAction(CoreEventType.MISSED_CALL_TAPPED, info, seq, alreadyEnded = false)
        core.store.writeLaunchAction(launch)
        val result = TransitionResult(TransitionOutcome.OK, persistedEvents = listOf(event))
        publish(callId, result)
        deliverLive(result)
        return launch
    }

    // ── sweep (§8.10 — every native entry point routes here) ─────────────

    fun sweepNow(): SweepReport {
        // Reap headless leases that expired without a completion ack (R38): the
        // events return to the pending pool for replay; record one LEASE_EXPIRED
        // each. Runs at every native entry point via the sweep (§10.2, §8.10).
        repository.eventLog.reapExpiredLeases().forEach { event ->
            ringLog.incident(RingLog.IncidentType.LEASE_EXPIRED, "Orchestrator.sweepNow", event.callId)
        }
        val pre = repository.activeCalls().associateBy { it.callId }
        core.refreshSweepEnv()
        val report = repository.sweep(core.sweepEnv)
        report.sweptCalls.forEach { (callId, result) ->
            ringLog.incident(RingLog.IncidentType.SWEEP_GHOST, "Orchestrator.sweepNow", callId)
            executeEffects(callId, result, pre[callId])
            deliverLive(result)
        }
        rearmWatchdogs()
        return report
    }

    // ── effect execution (§4.3 flows; §5.1 zero-side-effect losers skip) ──

    private fun executeEffects(callId: String, result: TransitionResult, pre: CallRecord? = null) {
        if (result.effects.isEmpty()) {
            publishIfMutated(callId, result)
            return
        }
        val factory = factory()
        val notifId = repository.notificationIdFor(callId)
        val record = repository.callFor(callId)

        // Terminal bookkeeping first: UI teardown before replacement surfaces.
        if (CoreEffect.STOP_RING in result.effects || CoreEffect.TEARDOWN_UI in result.effects) {
            avatarLoader().cancel(callId)
            core.watchdogs.cancel(callId)
            if (notifId != null && CoreEffect.TEARDOWN_UI in result.effects) factory.cancel(notifId)
        }
        if (CoreEffect.STOP_ONGOING_SERVICE in result.effects) {
            // The OngoingCallService observes the bus (§8.6): the publish below
            // re-evaluates it — no in-call call left ⇒ it REMOVEs its surface
            // and stops itself (an FGS-attached notification ignores cancel()).
        }
        if (CoreEffect.MISSED_CALL in result.effects) {
            postMissedFor(callId, repository.tombstoneFor(callId), pre?.options?.missedCall)
        }
        // In-call surfaces (§8.2/§4.3): the OngoingCallService hosts them as
        // CallStyle-forOngoingCall (validity by FGS-attachment); opted out or
        // FGS-denied ⇒ same-content non-CallStyle notify() fallback (v1.3.4).
        val inCallSurface =
            CoreEffect.CONNECTING_SURFACE in result.effects ||
                CoreEffect.CALLING_SURFACE in result.effects ||
                CoreEffect.CHRONOMETER_START in result.effects
        if (inCallSurface && record != null) {
            postInCallSurface(record, factory)
        }
        if (CoreEffect.ARM_WATCHDOG in result.effects && record?.connectionDeadlineElapsedMs != null) {
            core.watchdogs.arm(callId, record.connectionDeadlineElapsedMs - core.time.elapsedMs()) {
                watchdogFireOnWriter(callId)
            }
        }
        if (CoreEffect.CANCEL_WATCHDOG in result.effects) {
            core.watchdogs.cancel(callId)
        }
        if (CoreEffect.SILENT_RENOTIFY in result.effects && record != null) {
            renotify(record, factory)
        }
        // LAUNCH_APP is executed by AcceptTrampolineActivity itself (§4.3): only
        // an activity context may launch from the lock screen / background.

        // §8.7: mirror accept/setActive/disconnect into Telecom (no-op off/unknown).
        mirrorTelecom(result, pre)

        publish(callId, result)
    }

    private fun renotify(record: CallRecord, factory: CallNotificationFactory) {
        when (record.state) {
            CoreCallState.RINGING -> {
                // Silent re-notify retains FGS attachment for the owner (§8.2).
                factory.postIncoming(
                    record,
                    attachFsi = record.options.lockScreenBehavior == CoreLockScreenBehavior.FULL_SCREEN,
                )
                kickOffAvatar(record, factory)
            }
            CoreCallState.ACCEPTED, CoreCallState.ACTIVE, CoreCallState.OUTGOING ->
                // Managed: the publish() after effects re-points the service
                // with the fresh info; ensure-start self-heals a dead service.
                postInCallSurface(record, factory)
        }
    }

    /** Managed → (ensure-)start the ongoing FGS; opt-out/denied → notify() fallback. */
    private fun postInCallSurface(record: CallRecord, factory: CallNotificationFactory) {
        if (!repository.currentConfig().manageOngoingCall) {
            factory.postOngoingFallback(record)
            return
        }
        try {
            val intent =
                RingKitIntents.withCall(
                    Intent(appContext, OngoingCallService::class.java),
                    RingKitIntents.ACTION_ONGOING,
                    record.callId,
                )
            serviceStarter(appContext, intent)
        } catch (e: Exception) {
            val denied =
                (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) ||
                    e is IllegalStateException || e is SecurityException
            if (!denied) throw e
            ringLog.incident(
                RingLog.IncidentType.FGS_DENIED, "Orchestrator.postInCallSurface", record.callId,
            )
            factory.postOngoingFallback(record)
        }
    }

    private fun kickOffAvatar(record: CallRecord, factory: CallNotificationFactory) {
        val url = record.info.avatarUrl ?: return
        avatarLoader().load(record.callId, url, record.options.avatarHeaders) { bitmap ->
            // Delivered on the loader thread — re-post via the writer so the
            // repository read (is the call still live?) is safely confined.
            core.writer.post {
                val live = repository.callFor(record.callId) ?: return@post
                if (live.state == CoreCallState.RINGING) {
                    factory().postIncoming(
                        live,
                        avatar = bitmap,
                        attachFsi = live.options.lockScreenBehavior == CoreLockScreenBehavior.FULL_SCREEN,
                    )
                }
            }
        }
    }

    private fun postMissedFor(callId: String, tombstone: Tombstone?, options: CoreMissedCallOptions?) {
        val info = tombstone?.callInfo ?: return
        val notifId = repository.notificationIdFor(callId) ?: return
        factory().postMissed(info, tombstone.cancelReason, options ?: CoreMissedCallOptions(), notifId)
    }

    // ── watchdog re-arm after process restart (§5.2) ─────────────────────

    private fun rearmWatchdogs() {
        repository.activeCalls().forEach { record ->
            val deadline = record.connectionDeadlineElapsedMs ?: return@forEach
            if (record.state == CoreCallState.ACCEPTED || record.state == CoreCallState.OUTGOING) {
                core.watchdogs.arm(record.callId, deadline - core.time.elapsedMs()) {
                    watchdogFireOnWriter(record.callId)
                }
            }
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private fun startRingingService(callId: String): Boolean =
        try {
            val intent =
                RingKitIntents.withCall(
                    Intent(appContext, RingingService::class.java),
                    RingKitIntents.ACTION_RING,
                    callId,
                )
            serviceStarter(appContext, intent)
            true
        } catch (e: Exception) {
            val denied =
                (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) ||
                    e is IllegalStateException || e is SecurityException
            if (!denied) throw e
            false
        }

    private fun isAppForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { notificationManager.canUseFullScreenIntent() }.getOrDefault(true)
    }

    /**
     * §10.2 rung 1, exactly once per transition: evictions' events (older seqs)
     * first, then the transition's own persisted + live events. The sink is the
     * adapter's designated-engine bridge; null (cold native process) leaves
     * persisted events in the log for replay.
     */
    private fun deliverLive(result: TransitionResult, suppress: CoreSuppressReason? = null) {
        val events =
            result.evictions.flatMap { it.result.persistedEvents } +
                result.persistedEvents + result.liveEvents
        if (events.isEmpty()) return
        val deliveredLive = core.liveSink?.invoke(events, suppress) ?: false
        if (deliveredLive) {
            // §11.3 breadcrumb — the last path an event took toward the app.
            runCatching { core.store.writeLastDeliveryPath("live") }
            return
        }
        // §10.2 rung 2: no live designated engine consumed them (dead app / no
        // engine yet). Route actionable events to the headless handler if one is
        // registered; otherwise they stay in the log for replay (rung 3).
        routeBackground(events)
    }

    /**
     * §10.2 rung 2: hand actionable events (decline/timeout/cancel) to the
     * durable background host. The host's runner does the leasing + engine boot;
     * this only decides whether to trigger it. A host/runner trigger failure is a
     * DELIVERY_FAILURE — the events then replay (R38).
     */
    private fun routeBackground(events: List<CoreEvent>) {
        if (events.none { it.type in EventLog.ACTIONABLE_TYPES }) return
        if (core.store.readBackgroundHandles() == null) return // no handler ⇒ replay (rung 3)
        try {
            backgroundDeliveryStarter(appContext)
        } catch (t: Throwable) {
            ringLog.incident(RingLog.IncidentType.DELIVERY_FAILURE, "Orchestrator.routeBackground", null, throwable = t)
        }
    }

    private fun publish(callId: String, result: TransitionResult) {
        val live = repository.callFor(callId)
        val eventType = (result.persistedEvents + result.liveEvents).lastOrNull { it.callId == callId }?.type
        core.bus.publish(CallBus.CallSignal(callId, live?.state, eventType))
    }

    private fun publishIfMutated(callId: String, result: TransitionResult) {
        if (result.persistedEvents.isNotEmpty() || result.liveEvents.isNotEmpty()) publish(callId, result)
    }

    private fun trace(tag: String, callId: String?, message: String) = core.trace.trace(tag, callId, message)

    private fun traceShow(callId: String, outcome: ShowOutcome) {
        trace(
            "show",
            callId,
            "outcome=${outcome.result.outcome} fail=${outcome.failReason} degradations=${outcome.degradations} suppress=${outcome.suppressReason}",
        )
    }
}
