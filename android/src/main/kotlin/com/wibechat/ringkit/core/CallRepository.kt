package com.wibechat.ringkit.core

/**
 * The single source of truth (PLAN §5): implements the FULL §5.1 transition
 * table. The normative table lives as data at
 * `android/src/test/resources/spec/transition_table.json`; the generated
 * Robolectric suite drives every cell against this class — never change
 * behavior here without updating table + generated expectations in the same
 * commit (CLAUDE.md rule).
 *
 * Zero-side-effect rule (§5.1): a losing/rejected transition returns its
 * outcome with NO events, NO effects, NO artifact mutation — trace only.
 *
 * Effects are returned as DATA ([CoreEffect] tags); the M2 orchestrator maps
 * them to notifications/services/activities. This keeps core/ Flutter-free and
 * Robolectric-testable, and lets the table suite assert intent per cell.
 *
 * Threading: confined to the single writer executor (§8.9) by contract.
 */
class CallRepository internal constructor(
    private val store: CoreStore,
    private val time: TimeSource,
    private val trace: CoreTrace = CoreTrace.NoOp,
) {
    companion object {
        const val MAX_CALL_ID_LENGTH = 128
        const val MAX_EXTRA_BYTES = 1024
        const val UNKNOWN_CALLER_PLACEHOLDER = "Unknown caller"

        fun open(store: CoreStore, time: TimeSource, trace: CoreTrace = CoreTrace.NoOp): CallRepository =
            CallRepository(store, time, trace)
    }

    val eventLog: EventLog = EventLog(store, time)

    private val calls = LinkedHashMap<String, CallRecord>()
    private var tombstones = mutableListOf<Tombstone>()
    private var preTombstones = mutableListOf<PreTombstone>()
    private var registry = LinkedHashMap<String, Int>()
    private var config: CoreConfig

    init {
        store.readCalls().forEach { calls[it.callId] = it }
        val (tombs, pres) = store.readTombstones()
        tombstones = tombs.toMutableList()
        preTombstones = pres.toMutableList()
        registry = LinkedHashMap(store.readRegistry())
        config = store.readConfig()
    }

    // ── config (§8.10: versioned, last-writer-wins, clamped never thrown) ─

    fun currentConfig(): CoreConfig = config

    fun updateConfig(next: CoreConfig): CoreConfig {
        val clamped =
            next.copy(
                defaultTimeoutSeconds = CoreConfig.clampTimeout(next.defaultTimeoutSeconds),
                maxConcurrentRinging = CoreConfig.clampMaxRinging(next.maxConcurrentRinging),
                connectionTimeoutSeconds =
                    if (next.connectionTimeoutSeconds <= 0) CoreConfig().connectionTimeoutSeconds
                    else next.connectionTimeoutSeconds,
            )
        if (clamped != next) trace.trace("config", null, "invalid values clamped: $next")
        config = clamped
        store.writeConfig(clamped)
        return clamped
    }

    // ── dispatch ─────────────────────────────────────────────────────────

    fun dispatch(input: TransitionInput): TransitionResult =
        when (input) {
            is TransitionInput.Show -> show(input.call)
            is TransitionInput.Accept -> accept(input.callId, input.fromAction)
            is TransitionInput.Decline -> decline(input.callId)
            is TransitionInput.Timeout -> timeout(input.callId)
            is TransitionInput.Cancel -> cancel(input.callId, input.reason)
            is TransitionInput.End -> end(input.callId, input.reason)
            is TransitionInput.SetConnected -> setConnected(input.callId)
            is TransitionInput.StartOutgoing -> startOutgoing(input.call)
            is TransitionInput.UpdateInfo -> updateInfo(input.callId, input.update)
            is TransitionInput.EndAll -> endAll()
            is TransitionInput.WatchdogFire -> watchdogFire(input.callId)
            is TransitionInput.TaskRemoved -> taskRemoved(input.callId)
        }

    // ── §5.1 row: showIncomingCall ───────────────────────────────────────

    private fun show(call: CoreIncomingCall): TransitionResult {
        val invalid = validateId(call.id) ?: validateExtra(call.extra)
        if (invalid != null) return reject(TransitionOutcome.INVALID_PARAMS, call.id, invalid)

        when (val s = stateOf(call.id)) {
            is CallSlot.Live ->
                return when (s.record.state) {
                    CoreCallState.RINGING ->
                        reject(TransitionOutcome.ALREADY_SHOWN, call.id, "idempotent re-show (FCM re-delivery)")
                    else -> reject(TransitionOutcome.ALREADY_ACTIVE, call.id, "call is ${s.record.state}")
                }
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.ALREADY_TERMINAL, call.id, "tombstoned — never re-rings (R34/R41)")
            CallSlot.None -> Unit
        }

        val warnings = mutableListOf<String>()
        var name = call.callerName
        if (name.isBlank()) {
            // Blank Person name would invalidate CallStyle (§6.1).
            name = UNKNOWN_CALLER_PLACEHOLDER
            warnings += "blank callerName → placeholder"
        }
        var avatarUrl = call.avatarUrl
        if (avatarUrl != null && !avatarUrl.startsWith("https://") && !avatarUrl.startsWith("flutterAsset:")) {
            avatarUrl = null
            warnings += "avatarUrl scheme not https/flutterAsset → ignored"
        }

        val timeoutSeconds = CoreConfig.clampTimeout(call.timeoutSeconds ?: config.defaultTimeoutSeconds)
        val nowWall = time.wallMs()
        val nowElapsed = time.elapsedMs()
        val boot = time.bootCount()
        val info =
            CoreCallInfo(
                id = call.id,
                name = name,
                handle = call.callerHandle,
                avatarUrl = avatarUrl,
                type = call.type,
                direction = CoreCallDirection.INCOMING,
                extra = call.extra,
            )

        // Staleness guard (§5.2, R21): negative age clamps to 0; decision always traced.
        if (call.sentAtWallMs != null) {
            val age = (nowWall - call.sentAtWallMs).coerceAtLeast(0L)
            if (age > timeoutSeconds * 1000L) {
                trace.trace("staleness", call.id, "expired: age=${age}ms > ${timeoutSeconds}s — recording TIMED_OUT")
                return deadOnArrival(call, info, warnings)
            }
        } else {
            warnings += "sentAt absent — staleness guard skipped (diagnostics warning)"
        }

        // §5.2 multi-call cap — eviction is evaluated at admission. The oldest
        // waiting call (never the first-come ringer-owner) gets full TIMED_OUT
        // semantics; with cap 1 there is no waiting slot and the arrival itself
        // is the evictee (recorded TIMED_OUT directly, returns expired — G3).
        val evictions = mutableListOf<Eviction>()
        val ringingNow = calls.values.filter { it.state == CoreCallState.RINGING }
        if (ringingNow.size >= config.maxConcurrentRinging) {
            val waiting = ringingNow.drop(1)
            if (waiting.isEmpty()) {
                trace.trace("eviction", call.id, "cap=${config.maxConcurrentRinging}, owner-only — arrival recorded TIMED_OUT")
                return deadOnArrival(call, info, warnings)
            }
            // A config-lowered cap can leave more than one call of overage.
            var overage = ringingNow.size - config.maxConcurrentRinging + 1
            for (victim in waiting) {
                if (overage <= 0) break
                trace.trace("eviction", victim.callId, "over cap on admission of ${call.id} — oldest waiting evicted")
                val event = terminate(victim, CoreEventType.TIMED_OUT, TerminalType.TIMED_OUT)
                evictions +=
                    Eviction(
                        callId = victim.callId,
                        missedCall = victim.options.missedCall,
                        result =
                            TransitionResult(
                                outcome = TransitionOutcome.OK,
                                persistedEvents = listOf(event),
                                effects =
                                    buildSet {
                                        add(CoreEffect.STOP_RING)
                                        add(CoreEffect.TEARDOWN_UI)
                                        if (victim.options.missedCall.show) add(CoreEffect.MISSED_CALL)
                                    },
                            ),
                    )
                overage--
            }
        }

        allocateNotificationId(call.id)
        val record =
            CallRecord(
                info = info,
                state = CoreCallState.RINGING,
                options = call.options,
                timeoutSeconds = timeoutSeconds,
                shownAtWallMs = nowWall,
                shownAtElapsedMs = nowElapsed,
                bootCount = boot,
                ringDeadlineElapsedMs = nowElapsed + timeoutSeconds * 1000L,
                connectionDeadlineElapsedMs = null,
                surfacePosted = false,
            )
        calls[call.id] = record
        persistCalls()

        // CallRinging is live-only (§6.2): draws seq, never appended to the log.
        val ringingSeq = eventLog.drawSeq()
        val liveRinging = coreEvent(ringingSeq, info, CoreEventType.RINGING)
        return TransitionResult(
            outcome = TransitionOutcome.SHOWN,
            liveEvents = listOf(liveRinging),
            effects = setOf(CoreEffect.RING),
            warnings = warnings,
            evictions = evictions,
        )
    }

    /**
     * Shared dead-on-arrival shape (§5.1 show row): staleness guard + cap-1
     * eviction both record TIMED_OUT + missed-call + tombstone directly, never
     * ring, and return `expired`.
     */
    private fun deadOnArrival(
        call: CoreIncomingCall,
        info: CoreCallInfo,
        warnings: List<String>,
    ): TransitionResult {
        allocateNotificationId(call.id)
        val seq = eventLog.drawSeq()
        val event = coreEvent(seq, info, CoreEventType.TIMED_OUT)
        eventLog.append(event)
        writeTombstone(
            Tombstone(
                callId = call.id,
                terminalType = TerminalType.TIMED_OUT,
                terminalEventSeq = seq,
                callInfo = info,
                atElapsedMs = time.elapsedMs(),
                atWallMs = time.wallMs(),
                bootCount = time.bootCount(),
            ),
        )
        val effects =
            if (call.options.missedCall.show) setOf(CoreEffect.MISSED_CALL) else emptySet()
        return TransitionResult(
            outcome = TransitionOutcome.EXPIRED,
            persistedEvents = listOf(event),
            effects = effects,
            warnings = warnings,
        )
    }

    // ── §5.1 row: accept ─────────────────────────────────────────────────

    private fun accept(callId: String, fromAction: Boolean): TransitionResult {
        when (val s = stateOf(callId)) {
            CallSlot.None -> return reject(TransitionOutcome.NOT_FOUND, callId, "accept on unknown call")
            is CallSlot.PreTomb ->
                return reject(TransitionOutcome.ALREADY_TERMINAL, callId, "accept on pre-tombstone")
            is CallSlot.Tomb -> {
                // Accept raced a terminal transition (§4.3): no event, teardown-if-any,
                // action-source still launches the app with alreadyEnded.
                val winner = eventLog.latestTerminalFor(callId)
                var launch: LaunchAction? = null
                if (fromAction) {
                    val info = winner?.callInfo ?: s.tombstone.callInfo
                    val seq = winner?.seq ?: s.tombstone.terminalEventSeq
                    if (info != null && seq != null) {
                        launch = LaunchAction(
                            type = winner?.type ?: terminalEventType(s.tombstone.terminalType),
                            callInfo = info,
                            sequence = seq,
                            alreadyEnded = true,
                        )
                        store.writeLaunchAction(launch)
                    }
                }
                trace.trace("accept", callId, "already terminal — alreadyEnded launch=${launch != null}")
                return TransitionResult(
                    outcome = TransitionOutcome.ALREADY_TERMINAL,
                    effects =
                        if (fromAction) setOf(CoreEffect.TEARDOWN_UI, CoreEffect.LAUNCH_APP)
                        else setOf(CoreEffect.TEARDOWN_UI),
                    launchActionSet = launch,
                )
            }
            is CallSlot.Live -> {
                val record = s.record
                return when (record.state) {
                    CoreCallState.RINGING -> {
                        val now = time.elapsedMs()
                        val seq = eventLog.drawSeq()
                        val event = coreEvent(seq, record.info, CoreEventType.ACCEPTED)
                        eventLog.append(event)
                        calls[callId] =
                            record.copy(
                                state = CoreCallState.ACCEPTED,
                                ringDeadlineElapsedMs = null,
                                connectionDeadlineElapsedMs = now + config.connectionTimeoutSeconds * 1000L,
                            )
                        persistCalls()
                        var launch: LaunchAction? = null
                        if (fromAction) {
                            launch = LaunchAction(CoreEventType.ACCEPTED, record.info, seq, alreadyEnded = false)
                            store.writeLaunchAction(launch)
                        }
                        TransitionResult(
                            outcome = TransitionOutcome.OK,
                            persistedEvents = listOf(event),
                            effects =
                                buildSet {
                                    add(CoreEffect.STOP_RING)
                                    add(CoreEffect.DEMOTE_OTHERS)
                                    add(CoreEffect.CONNECTING_SURFACE)
                                    add(CoreEffect.ARM_WATCHDOG)
                                    if (fromAction) add(CoreEffect.LAUNCH_APP)
                                },
                            launchActionSet = launch,
                        )
                    }
                    CoreCallState.ACCEPTED ->
                        // Idempotent·0 (double-fire from notification + FSI): single event total.
                        reject(TransitionOutcome.OK, callId, "duplicate accept — idempotent, zero side effects")
                    CoreCallState.ACTIVE, CoreCallState.OUTGOING ->
                        reject(TransitionOutcome.WRONG_STATE, callId, "accept in ${record.state}")
                }
            }
        }
    }

    // ── §5.1 row: decline ────────────────────────────────────────────────

    private fun decline(callId: String): TransitionResult {
        when (val s = stateOf(callId)) {
            CallSlot.None -> return reject(TransitionOutcome.NOT_FOUND, callId, "decline on unknown call")
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.ALREADY_TERMINAL, callId, "stale decline (post-sweep PendingIntent)")
            is CallSlot.Live -> {
                val record = s.record
                if (record.state != CoreCallState.RINGING) {
                    return reject(TransitionOutcome.WRONG_STATE, callId, "decline in ${record.state}")
                }
                val event = terminate(record, CoreEventType.DECLINED, TerminalType.DECLINED)
                // Decline never launches the app and posts no missed-call (§4.3).
                return TransitionResult(
                    outcome = TransitionOutcome.OK,
                    persistedEvents = listOf(event),
                    effects = setOf(CoreEffect.STOP_RING, CoreEffect.TEARDOWN_UI),
                )
            }
        }
    }

    // ── §5.1 row: timeout ────────────────────────────────────────────────

    private fun timeout(callId: String): TransitionResult {
        when (val s = stateOf(callId)) {
            CallSlot.None -> return reject(TransitionOutcome.NO_OP, callId, "timeout for unknown call")
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.ALREADY_TERMINAL, callId, "timeout idempotent on terminal")
            is CallSlot.Live -> {
                val record = s.record
                if (record.state != CoreCallState.RINGING) {
                    // Stale timer raced a state change; watchdog owns non-RINGING states.
                    return reject(TransitionOutcome.NO_OP, callId, "timeout in ${record.state} — stale timer")
                }
                val event = terminate(record, CoreEventType.TIMED_OUT, TerminalType.TIMED_OUT)
                return TransitionResult(
                    outcome = TransitionOutcome.OK,
                    persistedEvents = listOf(event),
                    effects =
                        buildSet {
                            add(CoreEffect.STOP_RING)
                            add(CoreEffect.TEARDOWN_UI)
                            if (record.options.missedCall.show) add(CoreEffect.MISSED_CALL)
                        },
                )
            }
        }
    }

    // ── §5.1 row: cancel-push / cancelCall(id, reason) ──────────────────

    private fun cancel(callId: String, reason: CoreCancelReason): TransitionResult {
        when (val s = stateOf(callId)) {
            CallSlot.None -> {
                // Out-of-order FCM (§5.1, R41): write a pre-tombstone; the command
                // itself reports notFound.
                if (validateId(callId) == null) {
                    preTombstones.removeAll { it.callId == callId }
                    preTombstones.add(
                        PreTombstone(
                            callId = callId,
                            reason = reason,
                            atElapsedMs = time.elapsedMs(),
                            atWallMs = time.wallMs(),
                            bootCount = time.bootCount(),
                        ),
                    )
                    persistTombstones()
                    trace.trace("cancel", callId, "unknown id — pre-tombstone written ($reason)")
                    return TransitionResult(
                        outcome = TransitionOutcome.NOT_FOUND,
                        preTombstoneWritten = true,
                    )
                }
                return reject(TransitionOutcome.NOT_FOUND, callId, "cancel with invalid id")
            }
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.ALREADY_TERMINAL, callId, "cancel idempotent on terminal")
            is CallSlot.Live -> {
                val record = s.record
                return when (record.state) {
                    CoreCallState.RINGING -> {
                        val event =
                            terminate(record, CoreEventType.CANCELLED, TerminalType.CANCELLED, cancelReason = reason)
                        TransitionResult(
                            outcome = TransitionOutcome.OK,
                            persistedEvents = listOf(event),
                            effects =
                                buildSet {
                                    add(CoreEffect.STOP_RING)
                                    add(CoreEffect.TEARDOWN_UI)
                                    if (record.options.missedCall.show) add(CoreEffect.MISSED_CALL)
                                },
                        )
                    }
                    CoreCallState.ACCEPTED, CoreCallState.ACTIVE ->
                        // Local accept wins (§5.1) — ignored + traced, zero side effects.
                        reject(TransitionOutcome.WRONG_STATE, callId, "cancel vs local ${record.state} — accept wins")
                    CoreCallState.OUTGOING -> {
                        val event =
                            terminate(record, CoreEventType.ENDED, TerminalType.ENDED, endReason = CoreEndReason.REMOTE)
                        TransitionResult(
                            outcome = TransitionOutcome.OK,
                            persistedEvents = listOf(event),
                            effects = setOf(CoreEffect.TEARDOWN_UI, CoreEffect.STOP_ONGOING_SERVICE),
                        )
                    }
                }
            }
        }
    }

    // ── §5.1 rows: endCall(remote|local|failed) ──────────────────────────

    private fun end(callId: String, reason: CoreEndReason): TransitionResult {
        when (val s = stateOf(callId)) {
            // No pre-tombstone: hangups aren't cancel-intents (§5.1).
            CallSlot.None -> return reject(TransitionOutcome.NOT_FOUND, callId, "end on unknown call")
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.ALREADY_TERMINAL, callId, "end idempotent on terminal")
            is CallSlot.Live -> {
                val record = s.record
                if (record.state == CoreCallState.RINGING) {
                    // Documented mappings (§5.1): remote → CANCELLED(callerCancelled)
                    // (+ missed-call); local/failed → CANCELLED(localDismiss), no missed-call.
                    val cancelReason =
                        if (reason == CoreEndReason.REMOTE) CoreCancelReason.CALLER_CANCELLED
                        else CoreCancelReason.LOCAL_DISMISS
                    val event =
                        terminate(record, CoreEventType.CANCELLED, TerminalType.CANCELLED, cancelReason = cancelReason)
                    return TransitionResult(
                        outcome = TransitionOutcome.OK,
                        persistedEvents = listOf(event),
                        effects =
                            buildSet {
                                add(CoreEffect.STOP_RING)
                                add(CoreEffect.TEARDOWN_UI)
                                if (reason == CoreEndReason.REMOTE && record.options.missedCall.show) {
                                    add(CoreEffect.MISSED_CALL)
                                }
                            },
                    )
                }
                val event = terminate(record, CoreEventType.ENDED, TerminalType.ENDED, endReason = reason)
                return TransitionResult(
                    outcome = TransitionOutcome.OK,
                    persistedEvents = listOf(event),
                    effects = setOf(CoreEffect.TEARDOWN_UI, CoreEffect.STOP_ONGOING_SERVICE),
                )
            }
        }
    }

    // ── §5.1 row: setCallConnected ───────────────────────────────────────

    private fun setConnected(callId: String): TransitionResult {
        when (val s = stateOf(callId)) {
            CallSlot.None -> return reject(TransitionOutcome.NOT_FOUND, callId, "setCallConnected on unknown call")
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.ALREADY_TERMINAL, callId, "setCallConnected on terminal")
            is CallSlot.Live -> {
                val record = s.record
                return when (record.state) {
                    CoreCallState.RINGING ->
                        reject(TransitionOutcome.WRONG_STATE, callId, "setCallConnected in RINGING — use acceptCall first")
                    CoreCallState.ACCEPTED, CoreCallState.OUTGOING -> {
                        calls[callId] =
                            record.copy(
                                state = CoreCallState.ACTIVE,
                                connectionDeadlineElapsedMs = null,
                                connectedAtElapsedMs = time.elapsedMs(),
                                connectedAtWallMs = time.wallMs(),
                            )
                        persistCalls()
                        TransitionResult(
                            outcome = TransitionOutcome.OK,
                            effects = setOf(CoreEffect.CANCEL_WATCHDOG, CoreEffect.CHRONOMETER_START),
                        )
                    }
                    CoreCallState.ACTIVE ->
                        // Idempotent: chronometer base unchanged (§5.1).
                        reject(TransitionOutcome.OK, callId, "setCallConnected idempotent in ACTIVE")
                }
            }
        }
    }

    // ── §5.1 row: startOutgoingCall ──────────────────────────────────────

    private fun startOutgoing(call: CoreOutgoingCall): TransitionResult {
        val invalid = validateId(call.id) ?: validateExtra(call.extra)
        if (invalid != null) return reject(TransitionOutcome.INVALID_PARAMS, call.id, invalid)

        when (val s = stateOf(call.id)) {
            is CallSlot.Live ->
                return if (s.record.state == CoreCallState.OUTGOING) {
                    reject(TransitionOutcome.ALREADY_SHOWN, call.id, "outgoing already started")
                } else {
                    reject(TransitionOutcome.ALREADY_ACTIVE, call.id, "call is ${s.record.state}")
                }
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.ALREADY_TERMINAL, call.id, "outgoing on terminal id")
            CallSlot.None -> Unit
        }

        val warnings = mutableListOf<String>()
        var name = call.calleeName
        if (name.isBlank()) {
            name = UNKNOWN_CALLER_PLACEHOLDER
            warnings += "blank calleeName → placeholder"
        }
        val now = time.elapsedMs()
        allocateNotificationId(call.id)
        val info =
            CoreCallInfo(
                id = call.id,
                name = name,
                handle = call.calleeHandle,
                avatarUrl = call.avatarUrl,
                type = call.type,
                direction = CoreCallDirection.OUTGOING,
                extra = call.extra,
            )
        calls[call.id] =
            CallRecord(
                info = info,
                state = CoreCallState.OUTGOING,
                options = call.options,
                timeoutSeconds = config.defaultTimeoutSeconds,
                shownAtWallMs = time.wallMs(),
                shownAtElapsedMs = now,
                bootCount = time.bootCount(),
                ringDeadlineElapsedMs = null,
                connectionDeadlineElapsedMs = now + config.connectionTimeoutSeconds * 1000L,
                surfacePosted = false,
            )
        persistCalls()
        return TransitionResult(
            outcome = TransitionOutcome.SHOWN,
            effects = setOf(CoreEffect.CALLING_SURFACE, CoreEffect.ARM_WATCHDOG),
            warnings = warnings,
        )
    }

    // ── §5.1 row: updateCallInfo ─────────────────────────────────────────

    private fun updateInfo(callId: String, update: CoreCallInfoUpdate): TransitionResult {
        when (val s = stateOf(callId)) {
            CallSlot.None -> return reject(TransitionOutcome.NOT_FOUND, callId, "update on unknown call")
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.ALREADY_TERMINAL, callId, "update on terminal")
            is CallSlot.Live -> {
                val record = s.record
                val info = record.info
                calls[callId] =
                    record.copy(
                        info =
                            info.copy(
                                name = update.callerName?.takeIf { it.isNotBlank() } ?: info.name,
                                handle = update.callerHandle ?: info.handle,
                                avatarUrl = update.avatarUrl ?: info.avatarUrl,
                            ),
                        options =
                            if (update.avatarHeaders != null) {
                                record.options.copy(avatarHeaders = update.avatarHeaders)
                            } else {
                                record.options
                            },
                    )
                persistCalls()
                return TransitionResult(
                    outcome = TransitionOutcome.OK,
                    effects = setOf(CoreEffect.SILENT_RENOTIFY),
                )
            }
        }
    }

    // ── §5.1 row: endAllCalls ────────────────────────────────────────────

    private fun endAll(): TransitionResult {
        val live = calls.values.toList()
        if (live.isEmpty()) return TransitionResult(outcome = TransitionOutcome.OK)
        val events = mutableListOf<CoreEvent>()
        val effects = mutableSetOf<CoreEffect>()
        live.forEach { record ->
            if (record.state == CoreCallState.RINGING) {
                // → CANCELLED(localDismiss), NO missed-call (§5.1).
                events += terminate(record, CoreEventType.CANCELLED, TerminalType.CANCELLED, cancelReason = CoreCancelReason.LOCAL_DISMISS)
                effects += CoreEffect.STOP_RING
                effects += CoreEffect.TEARDOWN_UI
            } else {
                events += terminate(record, CoreEventType.ENDED, TerminalType.ENDED, endReason = CoreEndReason.LOCAL)
                effects += CoreEffect.TEARDOWN_UI
                effects += CoreEffect.STOP_ONGOING_SERVICE
            }
        }
        return TransitionResult(outcome = TransitionOutcome.OK, persistedEvents = events, effects = effects)
    }

    // ── §5.1 row: ACCEPTED/OUTGOING connection watchdog ─────────────────

    private fun watchdogFire(callId: String): TransitionResult {
        when (val s = stateOf(callId)) {
            CallSlot.None -> return reject(TransitionOutcome.NO_OP, callId, "watchdog for unknown call")
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.NO_OP, callId, "watchdog on terminal — late fire")
            is CallSlot.Live -> {
                val record = s.record
                return when (record.state) {
                    CoreCallState.ACCEPTED, CoreCallState.OUTGOING -> {
                        val event =
                            terminate(record, CoreEventType.ENDED, TerminalType.ENDED, endReason = CoreEndReason.FAILED)
                        TransitionResult(
                            outcome = TransitionOutcome.OK,
                            persistedEvents = listOf(event),
                            effects = setOf(CoreEffect.TEARDOWN_UI, CoreEffect.STOP_ONGOING_SERVICE),
                        )
                    }
                    // Late fire post-setCallConnected = no-op (§5.1); RINGING owns its own timer.
                    else -> reject(TransitionOutcome.NO_OP, callId, "watchdog in ${record.state} — no-op")
                }
            }
        }
    }

    // ── §5.1 row: OngoingCallService.onTaskRemoved ──────────────────────

    private fun taskRemoved(callId: String): TransitionResult {
        when (val s = stateOf(callId)) {
            CallSlot.None -> return reject(TransitionOutcome.NO_OP, callId, "taskRemoved for unknown call")
            is CallSlot.Tomb, is CallSlot.PreTomb ->
                return reject(TransitionOutcome.NO_OP, callId, "taskRemoved on terminal")
            is CallSlot.Live -> {
                val record = s.record
                return when (record.state) {
                    CoreCallState.ACCEPTED, CoreCallState.ACTIVE, CoreCallState.OUTGOING -> {
                        // An orphan "in call" notification is a lie (§8.1).
                        val event =
                            terminate(record, CoreEventType.ENDED, TerminalType.ENDED, endReason = CoreEndReason.FAILED)
                        TransitionResult(
                            outcome = TransitionOutcome.OK,
                            persistedEvents = listOf(event),
                            effects = setOf(CoreEffect.TEARDOWN_UI, CoreEffect.STOP_ONGOING_SERVICE),
                        )
                    }
                    else -> reject(TransitionOutcome.NO_OP, callId, "taskRemoved in ${record.state}")
                }
            }
        }
    }

    // ── §5.1 sweep row (§8.10) — the process-death safety net ───────────

    fun sweep(env: SweepEnv): SweepReport {
        val nowElapsed = time.elapsedMs()
        val nowWall = time.wallMs()
        val boot = time.bootCount()
        val results = mutableListOf<Pair<String, TransitionResult>>()

        calls.values.toList().forEach { record ->
            val differentBoot = record.bootCount != boot
            when (record.state) {
                CoreCallState.RINGING -> {
                    val pastDeadline =
                        !differentBoot &&
                            record.ringDeadlineElapsedMs != null &&
                            nowElapsed >= record.ringDeadlineElapsedMs
                    // Presence check applies ONLY to calls that actually posted a
                    // surface (§8.10); notification-less calls are deadline-only.
                    val notifGone = record.surfacePosted && !env.notificationPresent(record.callId)
                    if (differentBoot || pastDeadline || notifGone) {
                        trace.trace(
                            "sweep",
                            record.callId,
                            "RINGING swept: boot=$differentBoot deadline=$pastDeadline notifGone=$notifGone",
                        )
                        val event = terminate(record, CoreEventType.TIMED_OUT, TerminalType.TIMED_OUT)
                        results +=
                            record.callId to
                            TransitionResult(
                                outcome = TransitionOutcome.OK,
                                persistedEvents = listOf(event),
                                effects =
                                    buildSet {
                                        add(CoreEffect.STOP_RING)
                                        add(CoreEffect.TEARDOWN_UI)
                                        if (record.options.missedCall.show) add(CoreEffect.MISSED_CALL)
                                    },
                            )
                    }
                }
                CoreCallState.ACCEPTED, CoreCallState.OUTGOING -> {
                    val pastWatchdog =
                        !differentBoot &&
                            record.connectionDeadlineElapsedMs != null &&
                            nowElapsed >= record.connectionDeadlineElapsedMs
                    if (differentBoot || pastWatchdog) {
                        trace.trace(
                            "sweep",
                            record.callId,
                            "${record.state} swept: boot=$differentBoot watchdog=$pastWatchdog",
                        )
                        val event =
                            terminate(record, CoreEventType.ENDED, TerminalType.ENDED, endReason = CoreEndReason.FAILED)
                        results +=
                            record.callId to
                            TransitionResult(
                                outcome = TransitionOutcome.OK,
                                persistedEvents = listOf(event),
                                effects = setOf(CoreEffect.TEARDOWN_UI, CoreEffect.STOP_ONGOING_SERVICE),
                            )
                    }
                }
                CoreCallState.ACTIVE -> {
                    // manageOngoingCall:false → cleaned only by bootCount change or
                    // explicit endCall — stated honestly (§5.1).
                    val serviceGone = config.manageOngoingCall && !env.ongoingServiceAlive
                    if (differentBoot || serviceGone) {
                        trace.trace(
                            "sweep",
                            record.callId,
                            "ACTIVE swept: boot=$differentBoot serviceGone=$serviceGone",
                        )
                        val event =
                            terminate(record, CoreEventType.ENDED, TerminalType.ENDED, endReason = CoreEndReason.FAILED)
                        results +=
                            record.callId to
                            TransitionResult(
                                outcome = TransitionOutcome.OK,
                                persistedEvents = listOf(event),
                                effects = setOf(CoreEffect.TEARDOWN_UI, CoreEffect.STOP_ONGOING_SERVICE),
                            )
                    }
                }
            }
        }

        // Prune expired (pre-)tombstones + their registry entries (§5.1 "(none)" cell).
        val expiredTombs = tombstones.filter { isExpired(it.atElapsedMs, it.atWallMs, it.bootCount, nowElapsed, nowWall, boot) }
        val expiredPres = preTombstones.filter { isExpired(it.atElapsedMs, it.atWallMs, it.bootCount, nowElapsed, nowWall, boot) }
        if (expiredTombs.isNotEmpty() || expiredPres.isNotEmpty()) {
            tombstones.removeAll(expiredTombs.toSet())
            preTombstones.removeAll(expiredPres.toSet())
            persistTombstones()
        }
        var prunedRegistry = 0
        val retained = calls.keys + tombstones.map { it.callId }.toSet()
        val staleRegistryKeys = registry.keys.filterNot { it in retained }
        if (staleRegistryKeys.isNotEmpty()) {
            staleRegistryKeys.forEach { registry.remove(it) }
            prunedRegistry = staleRegistryKeys.size
            store.writeRegistry(registry)
        }

        return SweepReport(
            sweptCalls = results,
            prunedTombstones = expiredTombs.size,
            prunedPreTombstones = expiredPres.size,
            prunedRegistryEntries = prunedRegistry,
        )
    }

    // ── queries ──────────────────────────────────────────────────────────

    fun activeCalls(): List<CallRecord> = calls.values.toList()

    fun callFor(callId: String): CallRecord? = calls[callId]

    fun tombstoneFor(callId: String): Tombstone? {
        val t = tombstones.lastOrNull { it.callId == callId } ?: return null
        return if (isExpired(t.atElapsedMs, t.atWallMs, t.bootCount, time.elapsedMs(), time.wallMs(), time.bootCount())) null else t
    }

    fun preTombstoneFor(callId: String): PreTombstone? {
        val p = preTombstones.lastOrNull { it.callId == callId } ?: return null
        return if (isExpired(p.atElapsedMs, p.atWallMs, p.bootCount, time.elapsedMs(), time.wallMs(), time.bootCount())) null else p
    }

    fun notificationIdFor(callId: String): Int? = registry[callId]

    fun peekLaunchAction(): LaunchAction? = store.readLaunchAction()

    /** One-shot (§6.1): clears on read; persisted natively, never intent extras (R44). */
    fun consumeLaunchAction(): LaunchAction? {
        val action = store.readLaunchAction()
        if (action != null) store.writeLaunchAction(null)
        return action
    }

    /** Orchestrator marks the call after actually posting its surface (§8.10). */
    fun markSurfacePosted(callId: String) {
        val record = calls[callId] ?: return
        if (!record.surfacePosted) {
            calls[callId] = record.copy(surfacePosted = true)
            persistCalls()
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private sealed class CallSlot {
        data class Live(val record: CallRecord) : CallSlot()

        data class Tomb(val tombstone: Tombstone) : CallSlot()

        data class PreTomb(val preTombstone: PreTombstone) : CallSlot()

        object None : CallSlot()
    }

    private fun stateOf(callId: String): CallSlot {
        calls[callId]?.let { return CallSlot.Live(it) }
        tombstoneFor(callId)?.let { return CallSlot.Tomb(it) }
        preTombstoneFor(callId)?.let { return CallSlot.PreTomb(it) }
        return CallSlot.None
    }

    /** Terminal bookkeeping shared by every terminal path (§5.2 store hygiene). */
    private fun terminate(
        record: CallRecord,
        eventType: CoreEventType,
        terminalType: TerminalType,
        endReason: CoreEndReason? = null,
        cancelReason: CoreCancelReason? = null,
    ): CoreEvent {
        val seq = eventLog.drawSeq()
        val event =
            coreEvent(seq, record.info, eventType, endReason = endReason, cancelReason = cancelReason)
        eventLog.append(event)
        calls.remove(record.callId)
        persistCalls()
        writeTombstone(
            Tombstone(
                callId = record.callId,
                terminalType = terminalType,
                endReason = endReason,
                cancelReason = cancelReason,
                terminalEventSeq = seq,
                callInfo = record.info,
                atElapsedMs = time.elapsedMs(),
                atWallMs = time.wallMs(),
                bootCount = time.bootCount(),
            ),
        )
        return event
    }

    private fun coreEvent(
        seq: Long,
        info: CoreCallInfo,
        type: CoreEventType,
        endReason: CoreEndReason? = null,
        cancelReason: CoreCancelReason? = null,
    ): CoreEvent =
        CoreEvent(
            seq = seq,
            callId = info.id,
            type = type,
            callInfo = info,
            endReason = endReason,
            cancelReason = cancelReason,
            wallMs = time.wallMs(),
            elapsedMs = time.elapsedMs(),
            bootCount = time.bootCount(),
        )

    /** Rejected/losing transition: typed outcome, trace only, ZERO side effects (§5.1). */
    private fun reject(outcome: TransitionOutcome, callId: String, why: String): TransitionResult {
        trace.trace("reject", callId, "$outcome: $why")
        return TransitionResult(outcome = outcome)
    }

    private fun validateId(id: String): String? =
        when {
            id.isBlank() -> "blank callId"
            id.length > MAX_CALL_ID_LENGTH -> "callId over $MAX_CALL_ID_LENGTH chars"
            else -> null
        }

    private fun validateExtra(extra: Map<String, String>): String? {
        var bytes = 2 // {}
        extra.forEach { (k, v) ->
            bytes += k.toByteArray(Charsets.UTF_8).size + v.toByteArray(Charsets.UTF_8).size + 6
        }
        return if (bytes > MAX_EXTRA_BYTES) "extra over $MAX_EXTRA_BYTES bytes serialized" else null
    }

    /** Stable notification IDs from the persisted registry (§5.2) — never hashCode. */
    private fun allocateNotificationId(callId: String): Int {
        registry[callId]?.let { return it }
        val used = registry.values.toHashSet()
        var candidate = config.notificationIdBase
        while (candidate in used) candidate++
        registry[callId] = candidate
        store.writeRegistry(registry)
        return candidate
    }

    private fun writeTombstone(tombstone: Tombstone) {
        tombstones.removeAll { it.callId == tombstone.callId }
        // A terminal outcome supersedes any pre-tombstone for the id.
        preTombstones.removeAll { it.callId == tombstone.callId }
        tombstones.add(tombstone)
        persistTombstones()
    }

    /**
     * TTL check (§5.2, 10 min). Same boot → elapsed math; different boot →
     * elapsedRealtime is incomparable, fall back to wall-clock delta (the only
     * computable basis; tombstones are advisory blockers, not deadlines).
     */
    private fun isExpired(
        atElapsedMs: Long,
        atWallMs: Long,
        bootCount: Int,
        nowElapsed: Long,
        nowWall: Long,
        nowBoot: Int,
    ): Boolean =
        if (bootCount == nowBoot) {
            nowElapsed - atElapsedMs > CoreDurations.TOMBSTONE_TTL_MS
        } else {
            nowWall - atWallMs > CoreDurations.TOMBSTONE_TTL_MS
        }

    private fun terminalEventType(terminalType: TerminalType): CoreEventType =
        when (terminalType) {
            TerminalType.DECLINED -> CoreEventType.DECLINED
            TerminalType.TIMED_OUT -> CoreEventType.TIMED_OUT
            TerminalType.CANCELLED -> CoreEventType.CANCELLED
            TerminalType.ENDED -> CoreEventType.ENDED
        }

    private fun persistCalls() = store.writeCalls(calls.values.toList())

    private fun persistTombstones() = store.writeTombstones(tombstones.toList(), preTombstones.toList())
}

// ── dispatch surface ─────────────────────────────────────────────────────

sealed class TransitionInput {
    data class Show(val call: CoreIncomingCall) : TransitionInput()

    /** fromAction = notification/FSI button (sets launch action); false = programmatic (§4.3). */
    data class Accept(val callId: String, val fromAction: Boolean) : TransitionInput()

    data class Decline(val callId: String) : TransitionInput()

    /** Handler / alarm / deleteIntent sources converge here; idempotent by callId (§4.3). */
    data class Timeout(val callId: String) : TransitionInput()

    data class Cancel(val callId: String, val reason: CoreCancelReason) : TransitionInput()

    data class End(val callId: String, val reason: CoreEndReason) : TransitionInput()

    data class SetConnected(val callId: String) : TransitionInput()

    data class StartOutgoing(val call: CoreOutgoingCall) : TransitionInput()

    data class UpdateInfo(val callId: String, val update: CoreCallInfoUpdate) : TransitionInput()

    object EndAll : TransitionInput()

    data class WatchdogFire(val callId: String) : TransitionInput()

    data class TaskRemoved(val callId: String) : TransitionInput()
}

/** Union of §6.4 ShowCallResult + CallCommandResult codes at core level. */
enum class TransitionOutcome {
    SHOWN,
    OK,
    ALREADY_SHOWN,
    ALREADY_ACTIVE,
    ALREADY_TERMINAL,
    EXPIRED,
    NOT_FOUND,
    WRONG_STATE,
    INVALID_PARAMS,
    NO_OP,
}

/** Orchestrator effect tags (§4.3 flows) — data here, real actions in M2. */
enum class CoreEffect {
    RING,
    STOP_RING,
    TEARDOWN_UI,
    MISSED_CALL,
    CONNECTING_SURFACE,
    CALLING_SURFACE,
    CHRONOMETER_START,
    LAUNCH_APP,
    STOP_ONGOING_SERVICE,
    DEMOTE_OTHERS,
    SILENT_RENOTIFY,
    ARM_WATCHDOG,
    CANCEL_WATCHDOG,
}

data class TransitionResult(
    val outcome: TransitionOutcome,
    val persistedEvents: List<CoreEvent> = emptyList(),
    /** Live-only events (CallRinging) — never in the log (§6.2). */
    val liveEvents: List<CoreEvent> = emptyList(),
    val effects: Set<CoreEffect> = emptySet(),
    val warnings: List<String> = emptyList(),
    val launchActionSet: LaunchAction? = null,
    val preTombstoneWritten: Boolean = false,
    /** §5.2 cap evictions performed by this admission (side transitions of OTHER calls). */
    val evictions: List<Eviction> = emptyList(),
)

/**
 * A §5.2 cap eviction: the oldest waiting call terminated (full TIMED_OUT
 * semantics) to admit a new arrival. [missedCall] snapshots the victim's
 * options — its live record is gone by the time effects execute.
 */
data class Eviction(
    val callId: String,
    val missedCall: CoreMissedCallOptions,
    val result: TransitionResult,
)

/** Environment probes the sweep needs (§8.10). */
data class SweepEnv(
    val notificationPresent: (String) -> Boolean,
    val ongoingServiceAlive: Boolean,
)

data class SweepReport(
    val sweptCalls: List<Pair<String, TransitionResult>>,
    val prunedTombstones: Int,
    val prunedPreTombstones: Int,
    val prunedRegistryEntries: Int,
)
