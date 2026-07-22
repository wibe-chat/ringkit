package com.wibechat.ringkit.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.core.CallBus
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreEventType
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreStore
import com.wibechat.ringkit.core.CoreTelemetryConfig
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingLog
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §11.5 gateway lifecycle (rule 14): FULLY inert when config is null; when
 * enabled, activation wires the tally seams + reconciles schedules in every
 * process; initialize() transitions enable/disable/change the session.
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryGatewayTest {

    private lateinit var context: Context
    private lateinit var scheduler: RecordingScheduler
    private lateinit var transport: FakeTransport

    private val telemetryConfig =
        CoreTelemetryConfig(
            endpoint = "https://fleet.example",
            apiKey = "key-secret",
            deviceLabel = "bench-A",
            uploadLevel = CoreLogLevel.INFO,
            dailyUploadBudgetKb = 200,
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scheduler = RecordingScheduler()
        transport = FakeTransport()
        Telemetry.resetForTests()
        RingKitCore.resetForTests()
        Telemetry.schedulerFactory = { scheduler }
        Telemetry.transportFactory = { transport }
    }

    @After
    fun tearDown() {
        Telemetry.resetForTests()
        RingKitCore.resetForTests()
    }

    private fun stateFile(): File =
        File(File(context.noBackupFilesDir, CoreStore.DIR_NAME), TelemetryState.FILE_NAME)

    // ── rule 14: inert when null ─────────────────────────────────────────

    @Test
    fun `null config — module fully inert at process entry`() {
        val core = RingKitCore.get(context) // default config: telemetry == null

        assertNull(Telemetry.session, "no session")
        assertNull(core.showOutcomeSink, "no tally seam")
        assertNull(core.ringLog.onIncidentSnapshot, "no incident seam")
        assertEquals(0, core.bus.listenerCount(), "no bus listener")
        assertEquals(0, scheduler.calls.size, "scheduler untouched")
        assertFalse(stateFile().exists(), "no state file created")

        Telemetry.onConfigChanged(core) // null → null stays inert
        assertNull(Telemetry.session)
        assertEquals(0, scheduler.calls.size)
    }

    @Test
    fun `activation at process entry wires seams and reconciles schedules`() {
        CoreStore.open(context).writeConfig(CoreConfig(telemetry = telemetryConfig))
        val core = RingKitCore.get(context)

        assertNotNull(Telemetry.session)
        assertNotNull(core.showOutcomeSink)
        assertNotNull(core.ringLog.onIncidentSnapshot)
        assertEquals(1, core.bus.listenerCount())
        assertTrue(awaitTrue { "heartbeat" in scheduler.calls }, "daily beat ensured on activation")
    }

    @Test
    fun `incident snapshot triggers the expedited upload schedule`() {
        CoreStore.open(context).writeConfig(CoreConfig(telemetry = telemetryConfig))
        val core = RingKitCore.get(context)

        core.ringLog.incident(RingLog.IncidentType.SHOW_FAILED, "Orchestrator.executeShow", "c-1")
        assertTrue(core.ringLog.awaitDrained())
        assertTrue(awaitTrue { "incident" in scheduler.calls }, "snapshot → expedited one-shot")
    }

    @Test
    fun `activation reconciles incident files a dead process never enqueued`() {
        CoreStore.open(context).writeConfig(CoreConfig(telemetry = telemetryConfig))
        // Simulate the dead process: incident file exists before this "boot".
        val logsDir = File(File(context.noBackupFilesDir, CoreStore.DIR_NAME), "logs")
        File(logsDir, "incidents").mkdirs()
        File(logsDir, "incidents/abcd1234abcd1234-1000.jsonl").writeText("""{"tag":"incident"}""" + "\n")

        RingKitCore.get(context)
        assertTrue(awaitTrue { "incident" in scheduler.calls }, "activation sweep found the straggler")
    }

    // ── heartbeat tallies ────────────────────────────────────────────────

    @Test
    fun `show outcomes and bus events land in the day-bucket counters`() {
        CoreStore.open(context).writeConfig(CoreConfig(telemetry = telemetryConfig))
        val core = RingKitCore.get(context)
        val session = Telemetry.session!!

        core.showOutcomeSink!!.invoke("SHOWN", null, setOf("FSI_DENIED"))
        core.showOutcomeSink!!.invoke("SHOWN", "NOTIFICATIONS_BLOCKED", emptySet())
        core.bus.publish(CallBus.CallSignal("c-1", CoreCallState.ACCEPTED, CoreEventType.ACCEPTED))
        core.bus.publish(CallBus.CallSignal("c-1", null, CoreEventType.DECLINED))
        core.bus.publish(CallBus.CallSignal("c-2", CoreCallState.RINGING, CoreEventType.RINGING))

        val day = TelemetryEngine.dayUtc(core.time.wallMs())
        assertTrue(
            awaitTrue {
                session.state.countersFor(day) ==
                    mapOf(
                        "shows" to 1,
                        "deg.fsiDenied" to 1,
                        "fail.notificationsBlocked" to 1,
                        "accepts" to 1,
                        "declines" to 1,
                    )
            },
            "tallies (RINGING is not a counter): ${session.state.countersFor(day)}",
        )
    }

    // ── config transitions ───────────────────────────────────────────────

    @Test
    fun `disable cancels workers and detaches every seam`() {
        CoreStore.open(context).writeConfig(CoreConfig(telemetry = telemetryConfig))
        val core = RingKitCore.get(context)
        assertNotNull(Telemetry.session)

        core.repository.updateConfig(CoreConfig(telemetry = null))
        Telemetry.onConfigChanged(core)

        assertNull(Telemetry.session)
        assertNull(core.showOutcomeSink)
        assertNull(core.ringLog.onIncidentSnapshot)
        assertEquals(0, core.bus.listenerCount())
        assertTrue("cancelAll" in scheduler.calls)
    }

    @Test
    fun `enable via initialize starts the session late`() {
        val core = RingKitCore.get(context) // inert boot
        assertNull(Telemetry.session)

        core.repository.updateConfig(CoreConfig(telemetry = telemetryConfig))
        Telemetry.onConfigChanged(core)

        assertNotNull(Telemetry.session)
        assertTrue(awaitTrue { "heartbeat" in scheduler.calls })
    }

    @Test
    fun `persisted server level override is re-applied at activation`() {
        CoreStore.open(context).writeConfig(CoreConfig(telemetry = telemetryConfig))
        // Pre-seed state with a VERBOSE override, as a prior process left it.
        val dir = File(context.noBackupFilesDir, CoreStore.DIR_NAME)
        val installId = CoreStore.open(context).installId
        TelemetryState.open(dir, installId)
            .applyKnobs(CoreLogLevel.VERBOSE, null, false, false, false, null)

        val core = RingKitCore.get(context)
        assertTrue(
            awaitTrue { core.ringLog.level == CoreLogLevel.VERBOSE },
            "server override survives the process restart",
        )
    }

    @Test
    fun `killed state schedules only the knob poll at activation`() {
        CoreStore.open(context).writeConfig(CoreConfig(telemetry = telemetryConfig))
        val dir = File(context.noBackupFilesDir, CoreStore.DIR_NAME)
        val installId = CoreStore.open(context).installId
        TelemetryState.open(dir, installId)
            .applyKnobs(null, null, false, killed = true, pullPending = false, deviceToken = null)

        RingKitCore.get(context)
        assertTrue(awaitTrue { "killPoll:now" in scheduler.calls })
        assertFalse("heartbeat" in scheduler.calls, "no data workers while killed")
        assertFalse("incident" in scheduler.calls)
    }

    @Test
    fun `config clamp trace never leaks the apiKey into the sink`() {
        CoreStore.open(context).writeConfig(CoreConfig(telemetry = telemetryConfig))
        val core = RingKitCore.get(context)
        // Invalid timeout forces the clamp trace, which stringifies the config.
        core.repository.updateConfig(CoreConfig(defaultTimeoutSeconds = 1, telemetry = telemetryConfig))
        assertTrue(core.ringLog.awaitDrained())

        val logsDir = File(File(context.noBackupFilesDir, CoreStore.DIR_NAME), "logs")
        val allRecords = (logsDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("ringlog-") }
            .joinToString("\n") { it.readText() }
        assertFalse("key-secret" in allRecords, "§11.4: apiKey never logged at any level")
        assertTrue(RingLog.REDACTED in allRecords, "clamp trace fired with the redacted form")
    }

    @Test
    fun `userReportNow is refused when disabled and schedules when enabled`() {
        val core = RingKitCore.get(context)
        assertFalse(Telemetry.userReportNow())

        core.repository.updateConfig(CoreConfig(telemetry = telemetryConfig))
        Telemetry.onConfigChanged(core)
        assertTrue(Telemetry.userReportNow())
        assertTrue(awaitTrue { "userReport" in scheduler.calls })
    }
}
