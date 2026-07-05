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
    /** Seam: Robolectric can't make startForegroundService throw (§8.11 fgsDenied tests). */
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

    // ── show (§8.11 evaluation happens HERE) ─────────────────────────────

    data class ShowOutcome(
        val result: TransitionResult,
        val failReason: CoreFailReason? = null,
        val degradations: Set<CoreDegradeReason> = emptySet(),
        val suppressReason: CoreSuppressReason? = null,
    )

    fun showOnWriter(call: CoreIncomingCall): ShowOutcome {
        val result = repository.dispatch(TransitionInput.Show(call))
        // §5.2 cap evictions ride the admission result: execute the victims'
        // teardown + missed-call surfaces BEFORE the new call's own effects.
        result.evictions.forEach { eviction ->
            executeEviction(eviction)
        }
        val outcome =
            when (result.outcome) {
                TransitionOutcome.SHOWN -> {
                    val o = executeShow(call, result)
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
        return outcome
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
        publish(eviction.callId, result)
    }

    /**
     * §8.11 (evaluated in row order; `failed` short-circuits, degradations
     * accumulate). Telecom rows are Phase 4 — matrix evaluates as telecom-OFF.
     */
    private fun executeShow(call: CoreIncomingCall, result: TransitionResult): ShowOutcome {
        val record = repository.callFor(call.id) ?: return ShowOutcome(result)
        val factory = factory()
        val readback = factory.ensureChannels()
        val degradations = mutableSetOf<CoreDegradeReason>()

        // Row 1/3: notifications blocked / incoming channel blocked → failed,
        // NO surface, NO sound (invisible ringing is banned); the call stays
        // persisted with its sweep-enforced deadline (§5.2 failed-shows rule).
        if (!readback.notificationsEnabled) {
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
        val config = repository.currentConfig()
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
        core.liveSink?.invoke(events, suppress)
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
