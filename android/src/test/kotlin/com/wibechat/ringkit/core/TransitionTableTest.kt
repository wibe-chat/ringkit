package com.wibechat.ringkit.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GENERATED SUITE — one test per §5.1 cell case, driven by the normative data
 * file `spec/transition_table.json` (CLAUDE.md: never hand-edit behavior
 * without updating table + this suite's expectations in the same commit).
 *
 * The API sweep runs the full table on every Robolectric-supported level
 * (SCOPE-1 M0). Robolectric 4.15.1 caps at 35 — API 36 rides the emulator
 * matrix (M2+); bump this list when Robolectric ships 36.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [26, 28, 31, 33, 34, 35])
class TransitionTableTest(private val case: TableCase) {

    companion object {
        private const val CALL_ID = "call-1"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val spec = TestSupport.loadSpec("transition_table.json")
            val cells = spec.getJSONArray("cells")
            val out = ArrayList<Array<Any>>()
            for (i in 0 until cells.length()) {
                val cell = cells.getJSONObject(i)
                val cases = cell.getJSONArray("cases")
                for (j in 0 until cases.length()) {
                    val c = cases.getJSONObject(j)
                    out.add(
                        arrayOf(
                            TableCase(
                                input = cell.getString("input"),
                                state = cell.getString("state"),
                                name = c.getString("name"),
                                given = c.optJSONObject("given") ?: JSONObject(),
                                sweep = c.optJSONObject("sweep"),
                                expect = c.getJSONObject("expect"),
                            ),
                        ),
                    )
                }
            }
            return out
        }
    }

    data class TableCase(
        val input: String,
        val state: String,
        val name: String,
        val given: JSONObject,
        val sweep: JSONObject?,
        val expect: JSONObject,
    ) {
        override fun toString(): String = "${input}_x_${state}__$name"
    }

    private lateinit var time: FakeTime
    private lateinit var repo: CallRepository

    @Test
    fun cell() {
        time = FakeTime()
        val store = CoreStore.openAt(TestSupport.newTempDir())
        repo = CallRepository.open(store, time, RecordingTrace())

        if (case.sweep != null) {
            runSweepCase(case.sweep)
            return
        }

        arrangeState(case.state, case.given)
        val before = snapshot()
        val result = repo.dispatch(buildInput(case.input, case.given))
        assertExpectations(result, before)
    }

    // ── arrange ──────────────────────────────────────────────────────────

    private fun arrangeState(state: String, given: JSONObject) {
        val missedShow = given.optJSONObject("arrange")?.optBoolean("missedCallShow", true) ?: true
        when (state) {
            "none" -> Unit
            "RINGING" -> showFresh(missedShow)
            "ACCEPTED" -> {
                showFresh(missedShow)
                check(repo.dispatch(TransitionInput.Accept(CALL_ID, fromAction = false)).outcome == TransitionOutcome.OK)
            }
            "ACTIVE" -> {
                showFresh(missedShow)
                check(repo.dispatch(TransitionInput.Accept(CALL_ID, fromAction = false)).outcome == TransitionOutcome.OK)
                check(repo.dispatch(TransitionInput.SetConnected(CALL_ID)).outcome == TransitionOutcome.OK)
            }
            "OUTGOING" ->
                check(
                    repo.dispatch(
                        TransitionInput.StartOutgoing(CoreOutgoingCall(id = CALL_ID, calleeName = "Bob")),
                    ).outcome == TransitionOutcome.SHOWN,
                )
            "tombstone" ->
                when (case.given.optString("variant", "tombstone")) {
                    "preTombstone" -> {
                        val r = repo.dispatch(TransitionInput.Cancel(CALL_ID, CoreCancelReason.CALLER_CANCELLED))
                        check(r.preTombstoneWritten) { "pre-tombstone arrange failed" }
                    }
                    "expiredTombstone" -> {
                        showFresh(missedShow)
                        check(repo.dispatch(TransitionInput.Decline(CALL_ID)).outcome == TransitionOutcome.OK)
                        time.advanceMs(CoreDurations.TOMBSTONE_TTL_MS + 1)
                    }
                    else -> {
                        showFresh(missedShow)
                        check(repo.dispatch(TransitionInput.Decline(CALL_ID)).outcome == TransitionOutcome.OK)
                    }
                }
            else -> error("unknown state column: $state")
        }
    }

    private fun showFresh(missedShow: Boolean) {
        val r =
            repo.dispatch(
                TransitionInput.Show(TestSupport.incomingCall(id = CALL_ID, missedCallShow = missedShow)),
            )
        check(r.outcome == TransitionOutcome.SHOWN) { "arrange show failed: ${r.outcome}" }
    }

    // ── act ──────────────────────────────────────────────────────────────

    private fun buildInput(input: String, given: JSONObject): TransitionInput {
        val id =
            when (given.optString("idKind", "")) {
                "blank" -> "  "
                "overlength" -> "x".repeat(129)
                else -> CALL_ID
            }
        return when (input) {
            "show" -> {
                val timeout = 45
                val sentAt =
                    when {
                        given.optBoolean("stale") -> time.wallMs() - (timeout + 5) * 1000L
                        given.optBoolean("futureSentAt") -> time.wallMs() + 60_000L
                        else -> null
                    }
                val extra =
                    if (given.optBoolean("extraOverLimit")) {
                        mapOf("payload" to "x".repeat(2000))
                    } else {
                        emptyMap()
                    }
                TransitionInput.Show(
                    TestSupport.incomingCall(
                        id = id,
                        name = if (given.optBoolean("blankName")) "  " else "Alice",
                        sentAtWallMs = sentAt,
                        avatarUrl = if (given.optBoolean("badAvatarScheme")) "http://x.example/a.png" else null,
                        extra = extra,
                    ),
                )
            }
            "accept" -> TransitionInput.Accept(id, fromAction = given.optBoolean("fromAction", false))
            "decline" -> TransitionInput.Decline(id)
            "timeout" -> TransitionInput.Timeout(id)
            "cancel" ->
                TransitionInput.Cancel(id, CoreCancelReason.valueOf(given.optString("reason", "CALLER_CANCELLED")))
            "endRemote" -> TransitionInput.End(id, CoreEndReason.REMOTE)
            "endLocal" -> TransitionInput.End(id, CoreEndReason.LOCAL)
            "endFailed" -> TransitionInput.End(id, CoreEndReason.FAILED)
            "setConnected" -> TransitionInput.SetConnected(id)
            "startOutgoing" -> TransitionInput.StartOutgoing(CoreOutgoingCall(id = id, calleeName = "Bob"))
            "updateInfo" -> TransitionInput.UpdateInfo(id, CoreCallInfoUpdate(callerName = "Updated Name"))
            "endAll" -> TransitionInput.EndAll
            "watchdog" -> TransitionInput.WatchdogFire(id)
            "taskRemoved" -> TransitionInput.TaskRemoved(id)
            else -> error("unknown input: $input")
        }
    }

    // ── assert ───────────────────────────────────────────────────────────

    private data class Snapshot(
        val callState: CoreCallState?,
        val connectedAt: Long?,
        val logSeqs: List<Long>,
        val tombstonePresent: Boolean,
        val preTombstonePresent: Boolean,
        val launchAction: LaunchAction?,
        val winningTerminalSeq: Long?,
    )

    private fun snapshot(): Snapshot =
        Snapshot(
            callState = repo.callFor(CALL_ID)?.state,
            connectedAt = repo.callFor(CALL_ID)?.connectedAtElapsedMs,
            logSeqs = repo.eventLog.all().map { it.seq },
            tombstonePresent = repo.tombstoneFor(CALL_ID) != null,
            preTombstonePresent = repo.preTombstoneFor(CALL_ID) != null,
            launchAction = repo.peekLaunchAction(),
            winningTerminalSeq = repo.eventLog.latestTerminalFor(CALL_ID)?.seq,
        )

    private fun assertExpectations(result: TransitionResult, before: Snapshot) {
        val expect = case.expect
        assertEquals("outcome", expect.getString("outcome"), result.outcome.name)

        if (expect.optBoolean("zero")) {
            assertTrue("zero: no persisted events", result.persistedEvents.isEmpty())
            assertTrue("zero: no live events", result.liveEvents.isEmpty())
            assertTrue("zero: no effects", result.effects.isEmpty())
            assertEquals("zero: log untouched", before.logSeqs, repo.eventLog.all().map { it.seq })
            assertEquals("zero: state unchanged", before.callState, repo.callFor(CALL_ID)?.state)
            assertEquals("zero: tombstone unchanged", before.tombstonePresent, repo.tombstoneFor(CALL_ID) != null)
            assertEquals(
                "zero: pre-tombstone unchanged",
                before.preTombstonePresent,
                repo.preTombstoneFor(CALL_ID) != null,
            )
            assertEquals("zero: launch action unchanged", before.launchAction, repo.peekLaunchAction())
        }

        if (expect.has("callState")) {
            val want = if (expect.isNull("callState")) null else expect.getString("callState")
            assertEquals("callState", want, repo.callFor(CALL_ID)?.state?.name)
        }

        if (expect.has("events")) {
            val want = expect.getJSONArray("events")
            assertEquals("event count", want.length(), result.persistedEvents.size)
            for (i in 0 until want.length()) {
                val e = want.getJSONObject(i)
                val got = result.persistedEvents[i]
                assertEquals("event[$i] type", e.getString("type"), got.type.name)
                if (e.has("endReason")) assertEquals("event[$i] endReason", e.getString("endReason"), got.endReason?.name)
                if (e.has("cancelReason")) {
                    assertEquals("event[$i] cancelReason", e.getString("cancelReason"), got.cancelReason?.name)
                }
            }
        }

        if (expect.has("liveEvents")) {
            val want = expect.getJSONArray("liveEvents")
            assertEquals("live event count", want.length(), result.liveEvents.size)
            for (i in 0 until want.length()) {
                assertEquals(
                    "liveEvent[$i] type",
                    want.getJSONObject(i).getString("type"),
                    result.liveEvents[i].type.name,
                )
            }
        }

        if (expect.has("effects")) {
            val want = expect.getJSONArray("effects").let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
            assertEquals("effects", want, result.effects.map { it.name }.toSet())
        }

        if (expect.has("tombstone")) {
            assertEquals("tombstone present", expect.getBoolean("tombstone"), repo.tombstoneFor(CALL_ID) != null)
        }
        if (expect.has("preTombstone")) {
            assertEquals(
                "pre-tombstone present",
                expect.getBoolean("preTombstone"),
                repo.preTombstoneFor(CALL_ID) != null,
            )
        }

        if (expect.has("launchAction")) {
            if (expect.isNull("launchAction")) {
                assertNull("launch action must be absent", repo.peekLaunchAction())
            } else {
                val want = expect.getJSONObject("launchAction")
                val got = repo.peekLaunchAction()
                assertNotNull("launch action must be set", got)
                if (want.has("type")) assertEquals("launch type", want.getString("type"), got!!.type.name)
                if (want.has("alreadyEnded")) {
                    assertEquals("alreadyEnded", want.getBoolean("alreadyEnded"), got!!.alreadyEnded)
                }
                if (want.optBoolean("seqIsWinningTerminal")) {
                    assertEquals("launch seq = winning terminal seq", before.winningTerminalSeq, got!!.sequence)
                }
            }
        }

        expect.optJSONObject("recordAssert")?.let { ra ->
            val record = repo.callFor(CALL_ID)
            if (ra.has("name")) assertEquals("record name", ra.getString("name"), record!!.info.name)
            if (ra.optBoolean("avatarUrlNull")) assertNull("avatar ignored", record!!.info.avatarUrl)
            if (ra.has("infoName")) assertEquals("updated name", ra.getString("infoName"), record!!.info.name)
            if (ra.optBoolean("connectionDeadlineCleared")) {
                assertNull("watchdog deadline cleared", record!!.connectionDeadlineElapsedMs)
            }
            if (ra.optBoolean("connectedAtUnchanged")) {
                assertEquals("chronometer base unchanged", before.connectedAt, record!!.connectedAtElapsedMs)
            }
        }

        expect.optJSONObject("logAssert")?.optJSONObject("typeCount")?.let { tc ->
            val type = CoreEventType.valueOf(tc.getString("type"))
            assertEquals(
                "log count of $type",
                tc.getInt("count"),
                repo.eventLog.all().count { it.type == type },
            )
        }
    }

    // ── sweep cases ──────────────────────────────────────────────────────

    private fun runSweepCase(sweep: JSONObject) {
        if (sweep.optBoolean("manageOngoingCall", true).not()) {
            repo.updateConfig(repo.currentConfig().copy(manageOngoingCall = false))
        }

        when (case.state) {
            "none" -> {
                if (sweep.optBoolean("arrangeTombstone")) {
                    showFresh(true)
                    check(repo.dispatch(TransitionInput.Decline(CALL_ID)).outcome == TransitionOutcome.OK)
                }
                if (sweep.optBoolean("arrangePreTombstone")) {
                    repo.dispatch(TransitionInput.Cancel(CALL_ID, CoreCancelReason.CALLER_CANCELLED))
                }
            }
            else -> arrangeState(case.state, JSONObject())
        }

        if (sweep.optBoolean("surfacePosted")) repo.markSurfacePosted(CALL_ID)
        if (sweep.optBoolean("advancePastRingDeadline")) time.advanceMs(46_000L)
        if (sweep.optBoolean("advancePastWatchdog")) time.advanceMs(121_000L)
        if (sweep.optBoolean("advancePastTtl")) time.advanceMs(CoreDurations.TOMBSTONE_TTL_MS + 1)
        val bootDelta = sweep.optInt("bootCountDelta", 0)
        if (bootDelta > 0) time.reboot(bootDelta)

        val report =
            repo.sweep(
                SweepEnv(
                    notificationPresent = { sweep.optBoolean("notificationPresent", true) },
                    ongoingServiceAlive = sweep.optBoolean("serviceAlive", true),
                ),
            )

        val expect = case.expect
        assertEquals("swept count", expect.getInt("sweptCount"), report.sweptCalls.size)

        if (expect.has("callState")) {
            val want = if (expect.isNull("callState")) null else expect.getString("callState")
            assertEquals("callState after sweep", want, repo.callFor(CALL_ID)?.state?.name)
        }
        if (expect.has("sweptEvents")) {
            val want = expect.getJSONArray("sweptEvents")
            val got = report.sweptCalls.flatMap { it.second.persistedEvents }
            assertEquals("swept event count", want.length(), got.size)
            for (i in 0 until want.length()) {
                val e = want.getJSONObject(i)
                assertEquals("sweptEvent[$i] type", e.getString("type"), got[i].type.name)
                if (e.has("endReason")) assertEquals("sweptEvent[$i] endReason", e.getString("endReason"), got[i].endReason?.name)
            }
        }
        if (expect.has("sweptEffects")) {
            val want =
                expect.getJSONArray("sweptEffects").let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
            val got = report.sweptCalls.flatMap { it.second.effects }.map { it.name }.toSet()
            assertEquals("swept effects", want, got)
        }
        if (expect.has("tombstone")) {
            assertEquals("tombstone after sweep", expect.getBoolean("tombstone"), repo.tombstoneFor(CALL_ID) != null)
        }
        if (expect.has("preTombstone")) {
            assertEquals(
                "pre-tombstone after sweep",
                expect.getBoolean("preTombstone"),
                repo.preTombstoneFor(CALL_ID) != null,
            )
        }
        if (expect.has("registryEntry")) {
            assertEquals(
                "registry entry",
                expect.getBoolean("registryEntry"),
                repo.notificationIdFor(CALL_ID) != null,
            )
        }
        // Sweep must never re-ring (§5.1): no RINGING record may appear.
        assertTrue(
            "sweep never re-rings",
            repo.activeCalls().none { it.state == CoreCallState.RINGING && it.callId == CALL_ID } ||
                case.expect.optString("callState") == "RINGING",
        )
    }
}
