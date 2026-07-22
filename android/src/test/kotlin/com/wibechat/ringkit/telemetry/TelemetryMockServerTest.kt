package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreTelemetryConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * M2 integration: the REAL HttpURLConnection transport + engine against a
 * local mock ingest — full round-trip for all five kinds (register / incident /
 * heartbeat / pull / user_report), gzip bodies, bearer discipline, 401
 * re-register and 413 budget semantics per docs/TELEMETRY.md.
 */
class TelemetryMockServerTest {

    private lateinit var server: MockIngestServer
    private lateinit var harness: EngineHarness

    @Before
    fun setUp() {
        server = MockIngestServer()
        harness = EngineHarness()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun engine(): TelemetryEngine {
        val config =
            CoreTelemetryConfig(
                endpoint = "http://127.0.0.1:${server.port}",
                apiKey = "key-secret",
                deviceLabel = "bench-A",
                uploadLevel = CoreLogLevel.INFO,
                dailyUploadBudgetKb = 200,
            )
        return TelemetryEngine(
            installId = "install-1",
            time = harness.time,
            ringLog = harness.ringLog,
            appLogLevel = { CoreLogLevel.ERROR },
            appVersion = { "9.9.9" },
            config = config,
            state = harness.state,
            transport = HttpCheckinTransport(),
            scheduler = harness.scheduler,
        )
    }

    @Test
    fun `all five kinds round-trip with gzip bodies and bearer discipline`() {
        val e = engine()
        harness.writeIncident()

        assertEquals(TelemetryRun.OK, e.runIncidents()) // register + incident
        assertEquals(TelemetryRun.OK, e.runHeartbeat())
        // Operator flags the device (every 200 above said pull_buffer=false).
        harness.state.applyKnobs(null, null, false, false, pullPending = true, deviceToken = null)
        assertEquals(TelemetryRun.OK, e.runPull())
        assertEquals(TelemetryRun.OK, e.runUserReport())

        val kinds = server.seen.map { it.payload.getString("kind") }
        assertEquals(listOf("register", "incident", "heartbeat", "pull", "user_report"), kinds)
        assertTrue(server.seen.all { it.encoding == "gzip" }, "all bodies gzip per contract")
        assertTrue(server.seen.all { it.path == "/functions/v1/checkin" })
        assertEquals("key-secret", server.seen[0].bearer, "register: apiKey bearer")
        assertTrue(
            server.seen.drop(1).all { it.bearer == "server-tok-1" },
            "everything else: the issued device token",
        )
        assertTrue(server.seen.all { it.payload.getInt("v") == 1 })
        assertTrue(server.seen.all { it.payload.getString("install_id") == "install-1" })
        assertTrue(server.seen.all { it.payload.has("device_wall_ms") && it.payload.has("boot_count") })
        assertEquals("server-tok-1", harness.state.deviceToken)
    }

    @Test
    fun `401 on a stale token re-registers once and replays the upload`() {
        harness.state.deviceToken = "tok-stale"
        harness.writeIncident()
        server.statusQueue += 401 // the first incident POST is rejected

        assertEquals(TelemetryRun.OK, engine().runIncidents())
        assertEquals(
            listOf("incident" to "tok-stale", "register" to "key-secret", "incident" to "server-tok-1"),
            server.seen.map { it.payload.getString("kind") to it.bearer },
        )
    }

    @Test
    fun `413 stops the day — knobs from the error body still apply`() {
        harness.state.deviceToken = "server-tok-1"
        harness.writeIncident()
        server.statusQueue += 413

        assertEquals(TelemetryRun.DEFERRED, engine().runIncidents())
        assertEquals(1, server.seen.size)
        assertTrue(
            harness.state.bytesToday(TelemetryEngine.dayUtc(harness.time.wall)) >= 200 * 1024,
            "budget pinned to the cap",
        )
        // And no further traffic happens today.
        assertEquals(TelemetryRun.DEFERRED, engine().runIncidents())
        assertEquals(1, server.seen.size)
    }

    @Test
    fun `transport failure surfaces as RETRY and leaves files on device`() {
        harness.state.deviceToken = "server-tok-1"
        harness.writeIncident()
        val e = engine() // captures the port …
        server.stop() // … then nothing is listening

        assertEquals(TelemetryRun.RETRY, e.runIncidents())
        assertEquals(1, e.pendingIncidentFiles().size, "nothing was lost")
        assertTrue(server.seen.isEmpty())
    }
}
