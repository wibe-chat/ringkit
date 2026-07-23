package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.RingLog
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * §11.5 tiered protocol, client side (M2): token bootstrap + 401 re-register,
 * once-per-fingerprint-per-day dedup, budget accounting incl. 413, atomic knob
 * application (level/kill/pull/budget), idempotent retry payloads, upload-time
 * redaction, pull chunking, heartbeat day-buckets + fingerprint deltas.
 * Wire contract: docs/TELEMETRY.md.
 */
class TelemetryEngineTest {

    private val dayMs = 24L * 60L * 60L * 1000L

    // ── token bootstrap ──────────────────────────────────────────────────

    @Test
    fun `first upload registers with the apiKey then ships with the token`() {
        val h = EngineHarness()
        assertEquals(TelemetryRun.OK, h.engine().runHeartbeat())

        assertEquals(listOf("register", "heartbeat"), h.transport.kinds())
        assertEquals("key-secret", h.transport.sent[0].bearer, "register uses the apiKey")
        assertEquals("tok-1", h.transport.sent[1].bearer, "uploads use the device token")
        assertEquals("tok-1", h.state.deviceToken)
        assertEquals(
            "https://fleet.example/functions/v1/checkin",
            h.transport.sent[0].url,
            "endpoint grammar per docs/TELEMETRY.md",
        )
    }

    @Test
    fun `register device block carries the applicationId for multi-app segregation`() {
        val h = EngineHarness()
        assertEquals(TelemetryRun.OK, h.engine().runHeartbeat())
        val register = h.transport.sent.first { it.payload.optString("kind") == "register" }.payload
        assertEquals("com.example.host", register.getJSONObject("device").getString("package_name"))
        // Also present on the heartbeat's device descriptor.
        val heartbeat = h.transport.sent.first { it.payload.optString("kind") == "heartbeat" }.payload
        assertEquals("com.example.host", heartbeat.getJSONObject("device").getString("package_name"))
    }

    @Test
    fun `401 re-registers once and retries — then backs off`() {
        val h = EngineHarness()
        h.state.deviceToken = "tok-stale"
        h.writeIncident()
        h.transport.queue += CheckinResult.Http(401, null) // incident w/ stale token
        // then default: register ok, incident ok
        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        assertEquals(listOf("incident", "register", "incident"), h.transport.kinds())
        assertEquals("tok-1", h.transport.sent[2].bearer)

        // Persistent 401 (register succeeds but token still rejected) → RETRY.
        val h2 = EngineHarness()
        h2.state.deviceToken = "tok-stale"
        h2.writeIncident()
        h2.transport.queue += CheckinResult.Http(401, null)
        h2.transport.queue += FakeTransport.ok("register")
        h2.transport.queue += CheckinResult.Http(401, null)
        assertEquals(TelemetryRun.RETRY, h2.engine().runIncidents())
    }

    // ── tier 1: incidents ────────────────────────────────────────────────

    @Test
    fun `incident upload carries the wire contract fields from the snapshot`() {
        val h = EngineHarness()
        val fp = h.writeIncident()

        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        val incident = h.transport.sent.last().payload
        assertEquals("incident", incident.getString("kind"))
        assertEquals("install-1", incident.getString("install_id"))
        assertEquals(h.time.boot, incident.getInt("boot_count"))
        val block = incident.getJSONObject("incident")
        assertEquals(fp, block.getString("fingerprint"))
        assertEquals("FGS_DENIED", block.getString("incident_type"))
        assertEquals("Orchestrator.executeShow", block.getString("codepoint"))
        assertEquals("fgsDenied", block.getJSONArray("degradations").getString(0))
        assertEquals("c-1", block.getString("call_id"))
        assertEquals(h.time.wall, block.getLong("device_ts_ms"), "device_ts = snapshot wall ms")
        val records = gunzipToString(Base64.getDecoder().decode(block.getString("records_gzip_b64")))
        assertTrue("incident:FGS_DENIED" in records, "snapshot records ship as gzipped JSONL")
    }

    @Test
    fun `shipped incident never re-ships — and the same fingerprint dedups for the calendar day`() {
        val h = EngineHarness()
        val fp = h.writeIncident()
        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        val shipped = h.transport.sent.size

        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        assertEquals(shipped, h.transport.sent.size, "no new request for a shipped file")

        // A second snapshot file for the SAME fingerprint on the same UTC day
        // (RingLog's rolling 24 h window can produce one) stays deduped …
        File(h.logsDir, "incidents/$fp-${h.time.wall + 60_000}.jsonl")
            .writeText(File(h.logsDir, "incidents").listFiles()!!.first().readText())
        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        assertEquals(shipped, h.transport.sent.size, "once per fingerprint per device per day")

        // … and ships once the UTC day rolls over.
        h.time.advanceMs(dayMs)
        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        assertEquals(shipped + 1, h.transport.sent.size, "next calendar day ships again")
    }

    @Test
    fun `two pre-existing files of one fingerprint ship only once in a batch`() {
        // A prior process death (killed-state harness) can leave >1 snapshot
        // file for the SAME fingerprint before capture-side dedup meta persists.
        // The first upload batch must still ship at most once (device-found on
        // SM-G781B: both shipped → two storage objects for one fingerprint).
        val h = EngineHarness()
        h.state.deviceToken = "tok-1"
        val incidentsDir = File(h.logsDir, "incidents").apply { mkdirs() }
        val line = """{"seq":1,"wall":${h.time.wall},"level":"ERROR","tag":"incident",""" +
            """"cp":"Orchestrator.executeShow","event":"incident:FGS_DENIED",""" +
            """"data":{"fingerprint":"deadbeefdeadbeef","degradations":"fgsDenied"}}"""
        File(incidentsDir, "deadbeefdeadbeef-${h.time.wall}.jsonl").writeText(line + "\n")
        File(incidentsDir, "deadbeefdeadbeef-${h.time.wall + 500}.jsonl").writeText(line + "\n")

        assertEquals(2, h.ringLog.incidentFiles().size, "two files on disk")
        assertEquals(1, h.engine().pendingIncidentFiles().size, "collapsed to one per fingerprint")
        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        assertEquals(
            1,
            h.transport.sent.count { it.payload.optString("kind") == "incident" },
            "once per fingerprint per device per day even with duplicate files",
        )
    }

    @Test
    fun `retry payloads are idempotent — same fingerprint and device_ts`() {
        val h = EngineHarness()
        h.state.deviceToken = "tok-1"
        h.writeIncident()
        h.transport.queue += CheckinResult.Http(500, null)
        assertEquals(TelemetryRun.RETRY, h.engine().runIncidents())

        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        val first = h.transport.sent.first { it.payload.optString("kind") == "incident" }.payload
        val second = h.transport.sent.last { it.payload.optString("kind") == "incident" }.payload
        assertEquals(
            first.getJSONObject("incident").getString("fingerprint"),
            second.getJSONObject("incident").getString("fingerprint"),
        )
        assertEquals(
            first.getJSONObject("incident").getLong("device_ts_ms"),
            second.getJSONObject("incident").getLong("device_ts_ms"),
            "server-side idempotency key (install, fingerprint, device_ts) must be stable",
        )
    }

    // ── budget accounting ────────────────────────────────────────────────

    @Test
    fun `client-side budget defers gated uploads and resets at the UTC day`() {
        val h = EngineHarness(budgetKb = 1) // 1024 bytes/day
        h.state.deviceToken = "tok-1"
        h.writeIncident()
        h.state.addBytes(TelemetryEngine.dayUtc(h.time.wall), 1_000) // nearly exhausted

        assertEquals(TelemetryRun.DEFERRED, h.engine().runIncidents())
        assertEquals(0, h.transport.sent.size, "no request once the budget is gone")

        h.time.advanceMs(dayMs)
        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        assertEquals(1, h.transport.sent.size, "files stayed on device and shipped next day")
    }

    @Test
    fun `successful gated uploads count their gzipped bytes`() {
        val h = EngineHarness()
        h.state.deviceToken = "tok-1"
        h.writeIncident()
        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        assertEquals(
            h.transport.sent.single().gzippedSize,
            h.state.bytesToday(TelemetryEngine.dayUtc(h.time.wall)),
        )
    }

    @Test
    fun `server 413 exhausts the day and still applies the knobs it carries`() {
        val h = EngineHarness()
        h.state.deviceToken = "tok-1"
        h.writeIncident()
        h.transport.queue +=
            CheckinResult.Http(413, FakeTransport.okBody().put("log_level", "error").put("budget_kb", 5))

        assertEquals(TelemetryRun.DEFERRED, h.engine().runIncidents())
        assertEquals(5, h.state.knobBudgetKb, "413 body knobs applied")
        assertEquals(CoreLogLevel.ERROR, h.ringLog.level)
        assertEquals(5 * 1024, h.state.bytesToday(TelemetryEngine.dayUtc(h.time.wall)), "day pinned to cap")

        assertEquals(TelemetryRun.DEFERRED, h.engine().runIncidents())
        assertEquals(1, h.transport.sent.size, "no further traffic until rollover")
    }

    @Test
    fun `pulls are budget-exempt`() {
        val h = EngineHarness(budgetKb = 1)
        h.state.deviceToken = "tok-1"
        h.state.applyKnobs(null, null, false, false, pullPending = true, deviceToken = null)
        h.state.exhaustBudget(TelemetryEngine.dayUtc(h.time.wall), 1024)
        h.ringLog.log(CoreLogLevel.INFO, "ring", "cp", "c-1", "hello")
        h.ringLog.awaitDrained()

        assertEquals(TelemetryRun.OK, h.engine().runPull())
        assertEquals(listOf("pull"), h.transport.kinds())
        assertEquals(1024, h.state.bytesToday(TelemetryEngine.dayUtc(h.time.wall)), "ledger untouched")
        assertFalse(h.state.pullPending, "flag cleared after the final part")
    }

    // ── knob application ─────────────────────────────────────────────────

    @Test
    fun `knobs apply atomically — level steers the sink, pull schedules, kill cancels`() {
        val h = EngineHarness()
        h.appLevel = CoreLogLevel.ERROR
        val engine = h.engine()

        engine.applyKnobs(
            JSONObject()
                .put("log_level", "verbose").put("pull_buffer", true)
                .put("sampled", true).put("budget_kb", 64).put("kill", false),
        )
        assertEquals(CoreLogLevel.VERBOSE, h.ringLog.level, "server override wins")
        assertEquals(64, h.state.knobBudgetKb)
        assertTrue(h.state.sampled)
        assertTrue(h.state.pullPending)
        assertTrue("pull" in h.scheduler.calls, "pull_buffer schedules the pull worker")

        engine.applyKnobs(JSONObject().put("kill", true))
        assertTrue(h.state.killed)
        assertTrue("cancelAll" in h.scheduler.calls, "kill cancels every worker")
        assertTrue("killPoll:delayed" in h.scheduler.calls, "un-kill channel stays armed")
        assertEquals(CoreLogLevel.ERROR, h.ringLog.level, "no override ⇒ back to app config level")

        h.scheduler.calls.clear()
        engine.applyKnobs(JSONObject().put("kill", false))
        assertFalse(h.state.killed)
        assertTrue("heartbeat" in h.scheduler.calls, "revive re-ensures the daily beat")
    }

    @Test
    fun `killed state sends nothing — and the knob poll is the recovery channel`() {
        val h = EngineHarness()
        h.state.deviceToken = "tok-1"
        h.state.applyKnobs(null, null, false, killed = true, pullPending = false, deviceToken = null)
        h.writeIncident()

        assertEquals(TelemetryRun.DEFERRED, h.engine().runIncidents())
        assertEquals(TelemetryRun.DEFERRED, h.engine().runHeartbeat())
        assertEquals(0, h.transport.sent.size, "zero data traffic while killed")

        // The tiny daily register poll is allowed and applies fresh knobs.
        h.transport.defaultResponse = { CheckinResult.Http(200, FakeTransport.okBody("register")) }
        assertEquals(TelemetryRun.OK, h.engine().runKnobPoll())
        assertEquals(listOf("register"), h.transport.kinds())
        assertFalse(h.state.killed, "kill=false revives")
        assertTrue("heartbeat" in h.scheduler.calls)

        // Same-day second poll is throttled.
        val polls = h.transport.sent.size
        assertEquals(TelemetryRun.OK, h.engine().runKnobPoll())
        assertEquals(polls, h.transport.sent.size, "once per day")
    }

    // ── tier 2: heartbeat ────────────────────────────────────────────────

    @Test
    fun `heartbeat ships day buckets oldest-first and keeps today accumulating`() {
        val h = EngineHarness()
        h.state.deviceToken = "tok-1"
        val today = TelemetryEngine.dayUtc(h.time.wall)
        val yesterday = TelemetryEngine.dayUtc(h.time.wall - dayMs)
        h.state.incrementCounter(yesterday, "shows", 5)
        h.state.incrementCounter(today, "shows", 2)
        h.state.incrementCounter(today, "deg.fsiDenied", 1)

        assertEquals(TelemetryRun.OK, h.engine().runHeartbeat())
        val beats = h.transport.sent.filter { it.payload.optString("kind") == "heartbeat" }
        assertEquals(listOf(yesterday, today), beats.map { it.payload.getJSONObject("heartbeat").getString("day") })
        assertEquals(5, beats[0].payload.getJSONObject("heartbeat").getJSONObject("counters").getInt("shows"))
        assertEquals(2, beats[1].payload.getJSONObject("heartbeat").getJSONObject("counters").getInt("shows"))
        assertEquals(1, beats[1].payload.getJSONObject("heartbeat").getJSONObject("counters").getInt("deg.fsiDenied"))
        assertTrue(beats[1].payload.has("device"), "device descriptor refreshes with the beat")
        assertEquals("bench-A", beats[1].payload.getJSONObject("device").getString("label"))

        assertEquals(listOf(today), h.state.counterDays(), "yesterday flushed; today keeps accumulating")
    }

    @Test
    fun `heartbeat reports fingerprint deltas since the last beat`() {
        val h = EngineHarness()
        h.state.deviceToken = "tok-1"
        val fp = h.writeIncident()
        h.state.setReportedFingerprints(mapOf(fp to 0))

        assertEquals(TelemetryRun.OK, h.engine().runHeartbeat())
        val beat = h.transport.sent.last().payload.getJSONObject("heartbeat")
        assertEquals(1, beat.getJSONObject("fingerprints").getInt(fp), "delta = cumulative − reported")

        // Two more occurrences (deduped locally — counters only) → next beat = +2.
        h.ringLog.incident(RingLog.IncidentType.FGS_DENIED, "Orchestrator.executeShow", "c-2", setOf("FGS_DENIED"))
        h.ringLog.incident(RingLog.IncidentType.FGS_DENIED, "Orchestrator.executeShow", "c-3", setOf("FGS_DENIED"))
        h.ringLog.awaitDrained()
        assertEquals(TelemetryRun.OK, h.engine().runHeartbeat())
        val next = h.transport.sent.last().payload.getJSONObject("heartbeat")
        assertEquals(2, next.getJSONObject("fingerprints").getInt(fp))

        // Nothing new → no fingerprint keys at all.
        assertEquals(TelemetryRun.OK, h.engine().runHeartbeat())
        assertEquals(0, h.transport.sent.last().payload.getJSONObject("heartbeat").getJSONObject("fingerprints").length())
    }

    // ── redaction at upload (defense in depth, §11.4) ────────────────────

    @Test
    fun `uploads re-redact PII when the effective level is below verbose`() {
        val h = EngineHarness(uploadLevel = CoreLogLevel.INFO)
        h.state.deviceToken = "tok-1"
        // Sink at VERBOSE wrote raw PII; the upload filter must still scrub it.
        h.ringLog.level = CoreLogLevel.VERBOSE
        h.ringLog.log(
            CoreLogLevel.INFO, "show", "cp", "c-1", "ringing",
            mapOf("name" to "Alice Real", "handle" to "+15551234", "apiKey" to "key-secret"),
        )
        h.writeIncident()

        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        val records = gunzipToString(
            Base64.getDecoder().decode(
                h.transport.sent.last().payload.getJSONObject("incident").getString("records_gzip_b64"),
            ),
        )
        assertFalse("Alice Real" in records, "caller name redacted at upload")
        assertFalse("+15551234" in records, "handle redacted at upload")
        assertFalse("key-secret" in records, "apiKey never ships in records at ANY level")
        assertTrue(RingLog.REDACTED in records)
    }

    @Test
    fun `verbose upload level ships PII — consenting-tester mode`() {
        val h = EngineHarness(uploadLevel = CoreLogLevel.VERBOSE)
        h.state.deviceToken = "tok-1"
        h.ringLog.level = CoreLogLevel.VERBOSE
        h.ringLog.log(CoreLogLevel.INFO, "show", "cp", "c-1", "ringing", mapOf("name" to "Alice Real"))
        h.writeIncident()

        assertEquals(TelemetryRun.OK, h.engine().runIncidents())
        val records = gunzipToString(
            Base64.getDecoder().decode(
                h.transport.sent.last().payload.getJSONObject("incident").getString("records_gzip_b64"),
            ),
        )
        assertTrue("Alice Real" in records)
    }

    // ── tier 3: pull ─────────────────────────────────────────────────────

    @Test
    fun `pull ships the whole buffer in ordered parts and clears the flag`() {
        val h = EngineHarness()
        h.state.deviceToken = "tok-1"
        h.state.applyKnobs(null, null, false, false, pullPending = true, deviceToken = null)
        // Synthesize a rotated buffer directly — deterministic size, independent
        // of the sink's async writer/queue. ~4.2 MB: above the 3 MB chunk size
        // (⇒ multiple parts) yet under the sink's 5 MB total cap, which the next
        // write batch would otherwise enforce by deleting the file.
        val filler = "x".repeat(1024)
        val line = { i: Int ->
            """{"seq":$i,"wall":${h.time.wall},"level":"INFO","tag":"ring","cp":"cp","event":"e-$i","data":{"blob":"$filler"}}"""
        }
        h.logsDir.mkdirs()
        File(h.logsDir, "ringlog-1000-1.jsonl").writeText((1..3_800).joinToString("\n") { line(it) })
        h.ringLog.log(CoreLogLevel.INFO, "ring", "cp", "c-tail", "tail")
        assertTrue(h.ringLog.awaitDrained())

        assertEquals(TelemetryRun.OK, h.engine().runPull())
        val pulls = h.transport.sent.filter { it.payload.optString("kind") == "pull" }
        assertTrue(pulls.size >= 2, "chunked: expected multiple parts, got ${pulls.size}")
        pulls.forEachIndexed { i, p ->
            assertEquals(i + 1, p.payload.getJSONObject("pull").getInt("part"))
            assertEquals(pulls.size, p.payload.getJSONObject("pull").getInt("of"))
        }
        val lastPart = gunzipToString(
            Base64.getDecoder().decode(
                pulls.last().payload.getJSONObject("pull").getString("records_gzip_b64"),
            ),
        )
        assertTrue("c-tail" in lastPart, "the current file rides at the end of the buffer")
        assertFalse(h.state.pullPending)
    }

    // ── user_report ──────────────────────────────────────────────────────

    @Test
    fun `user report ships the recent slice with the marker fingerprint`() {
        val h = EngineHarness()
        h.state.deviceToken = "tok-1"
        h.ringLog.log(CoreLogLevel.INFO, "ring", "cp", "c-old", "stale")
        h.ringLog.awaitDrained()
        h.time.advanceMs(TelemetryEngine.USER_REPORT_WINDOW_MS + 60_000)
        h.ringLog.log(CoreLogLevel.INFO, "ring", "cp", "c-new", "fresh")
        h.ringLog.awaitDrained()

        assertEquals(TelemetryRun.OK, h.engine().runUserReport())
        val payload = h.transport.sent.last().payload
        assertEquals("user_report", payload.getString("kind"))
        val block = payload.getJSONObject("incident")
        assertEquals(TelemetryEngine.USER_REPORT_FINGERPRINT, block.getString("fingerprint"))
        assertEquals("USER_REPORT", block.getString("incident_type"))
        val records = gunzipToString(Base64.getDecoder().decode(block.getString("records_gzip_b64")))
        assertTrue("c-new" in records, "recent records ship")
        assertFalse("c-old" in records, "records outside the window stay home")
    }
}
