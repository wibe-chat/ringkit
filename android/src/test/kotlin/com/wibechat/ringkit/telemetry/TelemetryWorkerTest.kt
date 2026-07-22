package com.wibechat.ringkit.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreStore
import com.wibechat.ringkit.core.CoreTelemetryConfig
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.RingLog
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Worker shells around the engine: the stale-schedule guard (fired worker in a
 * process whose config went null) and a real WorkManager-driven incident run.
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryWorkerTest {

    private lateinit var context: Context
    private lateinit var transport: FakeTransport

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        transport = FakeTransport()
        Telemetry.resetForTests()
        RingKitCore.resetForTests()
        Telemetry.transportFactory = { transport }
    }

    @After
    fun tearDown() {
        Telemetry.resetForTests()
        RingKitCore.resetForTests()
    }

    @Test
    fun `worker fired with a null config cancels the stale schedule and succeeds`() {
        // Enqueue something under the tag so the cancel is observable.
        val scheduler = WorkTelemetryScheduler(context)
        scheduler.ensureHeartbeat()
        assertTrue(
            WorkManager.getInstance(context)
                .getWorkInfosByTag(WorkTelemetryScheduler.TAG).get().isNotEmpty(),
        )

        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(context).build()
        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        val infos = WorkManager.getInstance(context).getWorkInfosByTag(WorkTelemetryScheduler.TAG).get()
        assertTrue(infos.all { it.state.isFinished }, "stale telemetry work cancelled: $infos")
        assertEquals(0, transport.sent.size, "a null-config worker never touches the network")
    }

    @Test
    fun `incident worker ships pending snapshots through the real shell`() {
        CoreStore.open(context).writeConfig(
            CoreConfig(
                telemetry = CoreTelemetryConfig(
                    endpoint = "https://fleet.example",
                    apiKey = "key-secret",
                    deviceLabel = "bench-A",
                    uploadLevel = CoreLogLevel.INFO,
                    dailyUploadBudgetKb = 200,
                ),
            ),
        )
        val core = RingKitCore.get(context)
        core.ringLog.incident(RingLog.IncidentType.SHOW_FAILED, "Orchestrator.executeShow", "c-1")
        assertTrue(core.ringLog.awaitDrained())

        val worker = TestListenableWorkerBuilder<IncidentUploadWorker>(context).build()
        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf("register", "incident"), transport.kinds())
        assertEquals(0, Telemetry.session!!.engine().pendingIncidentFiles().size, "marked shipped")
    }

    @Test
    fun `transient failure retries with backoff — never data loss`() {
        CoreStore.open(context).writeConfig(
            CoreConfig(
                telemetry = CoreTelemetryConfig(
                    endpoint = "https://fleet.example",
                    apiKey = "key-secret",
                    deviceLabel = "bench-A",
                    uploadLevel = CoreLogLevel.INFO,
                    dailyUploadBudgetKb = 200,
                ),
            ),
        )
        val core = RingKitCore.get(context)
        Telemetry.session!!.state.deviceToken = "tok-1"
        core.ringLog.incident(RingLog.IncidentType.SHOW_FAILED, "Orchestrator.executeShow", "c-1")
        assertTrue(core.ringLog.awaitDrained())
        transport.queue += CheckinResult.Http(503, null)

        val worker = TestListenableWorkerBuilder<IncidentUploadWorker>(context).build()
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker.doWork() })
        assertEquals(1, Telemetry.session!!.engine().pendingIncidentFiles().size, "file stays on device")
    }
}
