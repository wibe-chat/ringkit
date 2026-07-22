package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.TestSupport
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * §11.5 uploader bookkeeping: §8.10 persistence rules (install fence, atomic
 * writes, lenient reads), the daily budget ledger, once-per-fingerprint-per-day
 * dedup markers and the bounded heartbeat day-buckets.
 */
class TelemetryStateTest {

    private fun newState(dir: File = TestSupport.newTempDir(), installId: String = "install-1") =
        Triple(TelemetryState.open(dir, installId), dir, installId)

    @Test
    fun `token and knobs round-trip across instances`() {
        val (state, dir, _) = newState()
        state.deviceToken = "tok-9"
        state.applyKnobs(
            logLevel = CoreLogLevel.VERBOSE, budgetKb = 50, sampled = true,
            killed = false, pullPending = true, deviceToken = null,
        )

        val reopened = TelemetryState.open(dir, "install-1")
        assertEquals("tok-9", reopened.deviceToken)
        assertEquals(CoreLogLevel.VERBOSE, reopened.knobLogLevel)
        assertEquals(50, reopened.knobBudgetKb)
        assertTrue(reopened.sampled)
        assertFalse(reopened.killed)
        assertTrue(reopened.pullPending)
    }

    @Test
    fun `applyKnobs with a token keeps it — register response path`() {
        val (state, _, _) = newState()
        state.applyKnobs(
            logLevel = null, budgetKb = null, sampled = false,
            killed = false, pullPending = false, deviceToken = "tok-reg",
        )
        assertEquals("tok-reg", state.deviceToken)
        assertNull(state.knobLogLevel, "no server override stored")
    }

    @Test
    fun `install fence discards state from another install`() {
        val (state, dir, _) = newState()
        state.deviceToken = "tok-old-install"

        val fenced = TelemetryState.open(dir, "install-2")
        assertNull(fenced.deviceToken, "stale token would collide server-side (§8.10 fence)")
    }

    @Test
    fun `corrupt state file reads as empty and never throws`() {
        val dir = TestSupport.newTempDir()
        File(dir, TelemetryState.FILE_NAME).writeText("{not json!!")
        val state = TelemetryState.open(dir, "install-1")
        assertNull(state.deviceToken)
        state.deviceToken = "tok-recovered" // and it can write again
        assertEquals("tok-recovered", TelemetryState.open(dir, "install-1").deviceToken)
    }

    // ── budget ledger ────────────────────────────────────────────────────

    @Test
    fun `budget accumulates within a day and resets on rollover`() {
        val (state, _, _) = newState()
        state.addBytes("2026-07-22", 100)
        state.addBytes("2026-07-22", 150)
        assertEquals(250, state.bytesToday("2026-07-22"))
        assertEquals(0, state.bytesToday("2026-07-23"), "new day starts at zero")
        state.addBytes("2026-07-23", 10)
        assertEquals(10, state.bytesToday("2026-07-23"), "rollover replaced the ledger day")
    }

    @Test
    fun `exhaustBudget pins the day to the cap — 413 semantics`() {
        val (state, _, _) = newState()
        state.exhaustBudget("2026-07-22", 200 * 1024)
        assertEquals(200 * 1024, state.bytesToday("2026-07-22"))
        assertEquals(0, state.bytesToday("2026-07-23"))
    }

    // ── incident dedup markers ───────────────────────────────────────────

    @Test
    fun `shipped incident markers dedup by file and by fingerprint-day`() {
        val (state, _, _) = newState()
        state.markIncidentShipped("fp1", "fp1-1000.jsonl", "2026-07-22")
        assertTrue(state.isFileShipped("fp1-1000.jsonl"))
        assertFalse(state.isFileShipped("fp1-2000.jsonl"))
        assertEquals("2026-07-22", state.fingerprintShippedOn("fp1"))
        assertNull(state.fingerprintShippedOn("fp2"))
    }

    @Test
    fun `pruneShippedIncidents drops markers whose file is gone`() {
        val (state, _, _) = newState()
        state.markIncidentShipped("fp1", "fp1-1000.jsonl", "2026-07-22")
        state.markIncidentShipped("fp2", "fp2-1000.jsonl", "2026-07-22")
        state.pruneShippedIncidents(setOf("fp2-1000.jsonl"))
        assertNull(state.fingerprintShippedOn("fp1"))
        assertEquals("2026-07-22", state.fingerprintShippedOn("fp2"))
    }

    // ── heartbeat counters ───────────────────────────────────────────────

    @Test
    fun `counters bucket by day and prune beyond the bound`() {
        val (state, _, _) = newState()
        state.incrementCounter("2026-07-22", "shows")
        state.incrementCounter("2026-07-22", "shows")
        state.incrementCounter("2026-07-22", "accepts")
        assertEquals(mapOf("shows" to 2, "accepts" to 1), state.countersFor("2026-07-22"))

        // 8 more days — the oldest bucket must fall off (bound = 7).
        (1..8).forEach { state.incrementCounter("2026-08-0$it", "shows") }
        assertTrue(state.counterDays().size <= TelemetryState.MAX_COUNTER_DAYS)
        assertTrue("2026-07-22" !in state.counterDays())
    }

    @Test
    fun `removeCounterDay drops only that bucket`() {
        val (state, _, _) = newState()
        state.incrementCounter("2026-07-21", "shows")
        state.incrementCounter("2026-07-22", "shows")
        state.removeCounterDay("2026-07-21")
        assertEquals(listOf("2026-07-22"), state.counterDays())
    }

    @Test
    fun `reported fingerprints round-trip`() {
        val (state, _, _) = newState()
        state.setReportedFingerprints(mapOf("fp1" to 4, "fp2" to 1))
        assertEquals(mapOf("fp1" to 4, "fp2" to 1), state.reportedFingerprints())
    }
}
