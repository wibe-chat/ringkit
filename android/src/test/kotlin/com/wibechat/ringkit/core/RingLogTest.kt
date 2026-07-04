package com.wibechat.ringkit.core

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * §11.5 capture-half tests: rotation/caps/crash-safety, severity classifier
 * fingerprints, §11.4 redaction-per-level, dropped counting. Plain JUnit —
 * RingLog is Android-free by construction (logcat tee injected).
 */
class RingLogTest {

    private fun newLog(
        dir: File = TestSupport.newTempDir(),
        time: FakeTime = FakeTime(),
        logcat: (CoreLogLevel, String) -> Unit = { _, _ -> },
    ): Triple<RingLog, File, FakeTime> = Triple(RingLog(dir, time, logcat), dir, time)

    private fun readAllRecords(dir: File): List<JSONObject> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("ringlog-") }
            .sortedBy { it.name }
            .flatMap { file -> file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) } }

    // ── record shape ─────────────────────────────────────────────────────

    @Test
    fun `record carries the §11_5 envelope fields`() {
        val (log, dir, time) = newLog()
        log.log(CoreLogLevel.INFO, "show", "Orchestrator.show", "c-1", "ring_started", mapOf("path" to "fgs"))
        assertTrue(log.awaitDrained())

        val record = readAllRecords(dir).single()
        assertEquals(1L, record.getLong("seq"))
        assertEquals(time.wall, record.getLong("wall"))
        assertEquals(time.elapsed, record.getLong("elapsed"))
        assertEquals(time.boot, record.getInt("boot"))
        assertEquals("INFO", record.getString("level"))
        assertEquals("show", record.getString("tag"))
        assertEquals("Orchestrator.show", record.getString("cp"))
        assertEquals("c-1", record.getString("callId"))
        assertEquals("ring_started", record.getString("event"))
        assertEquals("fgs", record.getJSONObject("data").getString("path"))
    }

    @Test
    fun `stack traces are captured and bounded`() {
        val (log, dir, _) = newLog()
        log.log(
            CoreLogLevel.ERROR, "svc", "RingingService.onStartCommand", null, "boom",
            throwable = IllegalStateException("test-marker"),
        )
        assertTrue(log.awaitDrained())
        val stack = readAllRecords(dir).single().getString("stack")
        assertTrue("test-marker" in stack)
        assertTrue(stack.length <= 8_192)
    }

    // ── §11.4 redaction ──────────────────────────────────────────────────

    @Test
    fun `PII keys are redacted at error and info levels`() {
        val (log, dir, _) = newLog()
        log.level = CoreLogLevel.INFO
        log.log(
            CoreLogLevel.INFO, "show", "cp", "c-1", "posted",
            mapOf("name" to "Alice Real", "handle" to "+1555", "avatarUrl" to "https://x/y.png", "other" to "kept"),
        )
        assertTrue(log.awaitDrained())
        val data = readAllRecords(dir).single().getJSONObject("data")
        assertEquals(RingLog.REDACTED, data.getString("name"))
        assertEquals(RingLog.REDACTED, data.getString("handle"))
        assertEquals(RingLog.REDACTED, data.getString("avatarUrl"))
        assertEquals("kept", data.getString("other"))
    }

    @Test
    fun `verbose level logs PII raw — explicit opt-in`() {
        val (log, dir, _) = newLog()
        log.level = CoreLogLevel.VERBOSE
        log.log(CoreLogLevel.VERBOSE, "show", "cp", "c-1", "posted", mapOf("name" to "Alice Real"))
        assertTrue(log.awaitDrained())
        assertEquals("Alice Real", readAllRecords(dir).single().getJSONObject("data").getString("name"))
    }

    @Test
    fun `apiKey is redacted at EVERY level including verbose`() {
        val (log, dir, _) = newLog()
        log.level = CoreLogLevel.VERBOSE
        log.log(CoreLogLevel.INFO, "telemetry", "cp", null, "config", mapOf("apiKey" to "sk-secret"))
        assertTrue(log.awaitDrained())
        assertEquals(RingLog.REDACTED, readAllRecords(dir).single().getJSONObject("data").getString("apiKey"))
    }

    // ── logcat tee gating ────────────────────────────────────────────────

    @Test
    fun `logcat tee respects the config level`() {
        val seen = mutableListOf<Pair<CoreLogLevel, String>>()
        val (log, _, _) = newLog(logcat = { l, m -> synchronized(seen) { seen.add(l to m) } })

        log.level = CoreLogLevel.ERROR
        log.log(CoreLogLevel.ERROR, "t", "cp", null, "e1")
        log.log(CoreLogLevel.INFO, "t", "cp", null, "i1") // above config level → no tee
        log.level = CoreLogLevel.NONE
        log.log(CoreLogLevel.ERROR, "t", "cp", null, "e2") // NONE → never tees
        assertTrue(log.awaitDrained())

        assertEquals(listOf(CoreLogLevel.ERROR), seen.map { it.first })
        assertTrue("e1" in seen.single().second)
    }

    @Test
    fun `sink captures even at level NONE — the black box is always on`() {
        val (log, dir, _) = newLog()
        log.level = CoreLogLevel.NONE
        log.log(CoreLogLevel.ERROR, "t", "cp", null, "captured-anyway")
        assertTrue(log.awaitDrained())
        assertEquals("captured-anyway", readAllRecords(dir).single().getString("event"))
    }

    // ── rotation & caps ──────────────────────────────────────────────────

    @Test
    fun `current file rotates past the per-file cap and total stays under 5MB`() {
        val (log, dir, time) = newLog()
        val filler = "x".repeat(2_000)
        // ~24 MB of records → forces many rotations and total-cap pruning.
        repeat(12_000) { i ->
            time.advanceMs(1)
            log.log(CoreLogLevel.INFO, "t", "cp", null, "e$i", mapOf("fill" to filler))
            // Periodic drain keeps the queue from overflowing; generous timeout —
            // fsync-per-batch under full-suite disk load can be slow.
            if (i % 500 == 0) assertTrue(log.awaitDrained(30_000))
        }
        assertTrue(log.awaitDrained(30_000))

        val logFiles = (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith("ringlog-") }
        val total = logFiles.sumOf { it.length() }
        assertTrue(total <= RingLog.MAX_TOTAL_BYTES, "total=$total exceeds cap")
        assertTrue(logFiles.size > 1, "expected rotated files")
        assertTrue(log.droppedLogs() > 0, "oldest-file drops must be counted")

        // Newest records survive; the earliest were pruned with the oldest files.
        val events = readAllRecords(dir).map { it.getString("event") }
        assertTrue("e11999" in events)
        assertFalse("e0" in events)
    }

    @Test
    fun `torn trailing line is skipped by readers — crash-safe append`() {
        val (log, dir, time) = newLog()
        log.log(CoreLogLevel.INFO, "t", "cp", "c-1", "whole")
        assertTrue(log.awaitDrained())
        val current = File(dir, "ringlog-current.jsonl")
        current.appendText("""{"seq":99,"wall":${time.wall},"torn""") // no newline, invalid JSON

        // Incident snapshot reads the buffer leniently — must not throw.
        log.incident(RingLog.IncidentType.SHOW_FAILED, "cp", "c-1")
        assertTrue(log.awaitDrained())
        val snapshot =
            (File(dir, "incidents").listFiles() ?: emptyArray()).single { it.name.endsWith(".jsonl") }
        val lines = snapshot.readLines().filter { it.isNotBlank() }
        assertTrue(lines.any { JSONObject(it).optString("event") == "whole" })
        assertTrue(lines.none { it.contains("torn") })
    }

    // ── incidents: fingerprint + dedup + window ──────────────────────────

    @Test
    fun `fingerprint is stable over type codepoint and degradation set — order-independent`() {
        val (log, _, _) = newLog()
        val a = log.fingerprint(RingLog.IncidentType.FGS_DENIED, "Orchestrator.ring", setOf("fgsDenied", "channelDemoted"))
        val b = log.fingerprint(RingLog.IncidentType.FGS_DENIED, "Orchestrator.ring", setOf("channelDemoted", "fgsDenied"))
        val c = log.fingerprint(RingLog.IncidentType.FGS_DENIED, "Orchestrator.other", setOf("fgsDenied", "channelDemoted"))
        val d = log.fingerprint(RingLog.IncidentType.SHOW_FAILED, "Orchestrator.ring", setOf("fgsDenied", "channelDemoted"))
        assertEquals(a, b)
        assertTrue(a != c && a != d)
        assertEquals(16, a.length)
    }

    @Test
    fun `incident snapshots once per fingerprint per day but always counts`() {
        val (log, dir, time) = newLog()
        log.incident(RingLog.IncidentType.WATCHDOG_FIRED, "cp", "c-1")
        assertTrue(log.awaitDrained())
        time.advanceMs(60_000)
        log.incident(RingLog.IncidentType.WATCHDOG_FIRED, "cp", "c-2") // same fingerprint (callId not in basis)
        assertTrue(log.awaitDrained())

        val incidents = (File(dir, "incidents").listFiles() ?: emptyArray()).filter { it.name.endsWith(".jsonl") }
        assertEquals(1, incidents.size, "second incident within 24h must not re-snapshot")

        val meta = JSONObject(File(dir, "meta.json").readText())
        val fp = log.fingerprint(RingLog.IncidentType.WATCHDOG_FIRED, "cp", emptySet())
        assertEquals(2, meta.getJSONObject("incidents").getJSONObject(fp).getInt("count"))

        // A day later the same fingerprint snapshots again.
        time.advanceMs(RingLog.SNAPSHOT_DEDUP_MS + 1)
        log.incident(RingLog.IncidentType.WATCHDOG_FIRED, "cp", "c-3")
        assertTrue(log.awaitDrained())
        val after = (File(dir, "incidents").listFiles() ?: emptyArray()).filter { it.name.endsWith(".jsonl") }
        assertEquals(2, after.size)
    }

    @Test
    fun `incident snapshot slices the past five minutes only`() {
        val (log, dir, time) = newLog()
        log.log(CoreLogLevel.INFO, "t", "cp", null, "too-old")
        assertTrue(log.awaitDrained())
        time.advanceMs(RingLog.SNAPSHOT_WINDOW_MS + 60_000)
        log.log(CoreLogLevel.INFO, "t", "cp", null, "in-window")
        log.incident(RingLog.IncidentType.SWEEP_GHOST, "cp", "c-1")
        assertTrue(log.awaitDrained())

        val snapshot =
            (File(dir, "incidents").listFiles() ?: emptyArray()).single { it.name.endsWith(".jsonl") }
        val events = snapshot.readLines().filter { it.isNotBlank() }.map { JSONObject(it).getString("event") }
        assertTrue("in-window" in events)
        assertTrue("incident:SWEEP_GHOST" in events)
        assertFalse("too-old" in events)
    }

    @Test
    fun `incident directory is pruned to the file cap`() {
        val (log, dir, time) = newLog()
        repeat(RingLog.MAX_INCIDENT_FILES + 5) { i ->
            // Distinct codepoints → distinct fingerprints → every one snapshots.
            log.incident(RingLog.IncidentType.PARSE_REJECTED, "cp-$i", null)
            time.advanceMs(1_000)
        }
        assertTrue(log.awaitDrained(30_000))
        val incidents = (File(dir, "incidents").listFiles() ?: emptyArray()).filter { it.name.endsWith(".jsonl") }
        assertEquals(RingLog.MAX_INCIDENT_FILES, incidents.size)
    }

    // ── never-block guarantee ────────────────────────────────────────────

    @Test
    fun `full queue drops and counts instead of blocking the caller`() {
        val (log, _, _) = newLog()
        // Flood far beyond QUEUE_CAPACITY in one burst: offers must never block
        // the caller — excess is dropped and counted, or absorbed if the writer
        // keeps up. Either way the burst returns promptly.
        val start = System.nanoTime()
        repeat(RingLog.QUEUE_CAPACITY * 4) { i ->
            log.log(CoreLogLevel.INFO, "t", "cp", null, "flood-$i")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 2_000, "caller was blocked for ${elapsedMs}ms")
        assertTrue(log.awaitDrained(30_000) || log.droppedLogs() > 0)
    }

    // ── CallBus ──────────────────────────────────────────────────────────

    @Test
    fun `bus delivers to all listeners and a throwing listener never blocks the rest`() {
        val bus = CallBus(ringLog = null)
        val seen = mutableListOf<String>()
        bus.subscribe { seen.add("first:${it.callId}") }
        bus.subscribe { throw IllegalStateException("listener bug") }
        val third = bus.subscribe { seen.add("third:${it.callId}:${it.terminal}") }

        bus.publish(CallBus.CallSignal("c-1", null, CoreEventType.DECLINED))
        assertEquals(listOf("first:c-1", "third:c-1:true"), seen)

        third.close()
        bus.publish(CallBus.CallSignal("c-2", CoreCallState.RINGING, null))
        assertEquals(listOf("first:c-1", "third:c-1:true", "first:c-2"), seen)
        assertEquals(2, bus.listenerCount())
    }

    @Test
    fun `bus signal terminal flag mirrors liveState nullability`() {
        assertTrue(CallBus.CallSignal("c", null, CoreEventType.TIMED_OUT).terminal)
        assertFalse(CallBus.CallSignal("c", CoreCallState.ACCEPTED, CoreEventType.ACCEPTED).terminal)
    }
}
