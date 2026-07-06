package com.wibechat.ringkit.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §10.2 delivery ladder — the routing decision at the single delivery point:
 * rung 1 (live designated engine) wins; otherwise an actionable event with a
 * registered handler goes to the rung-2 background host; with no handler it
 * stays for rung-3 replay. A host trigger failure is a DELIVERY_FAILURE (R38).
 */
@RunWith(RobolectricTestRunner::class)
class OrchestratorBackgroundLadderTest {

    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance
    private val bgStarts = mutableListOf<Unit>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RingKitCore.resetForTests()
        core = RingKitCore.get(context)
    }

    @After
    fun tearDown() {
        RingKitCore.resetForTests()
    }

    private fun orchestrator(starter: (Context) -> Unit = { bgStarts.add(Unit) }) =
        IncomingCallOrchestrator(
            context,
            core,
            serviceStarter = { _, _ -> },
            backgroundDeliveryStarter = starter,
        )

    private fun registerHandler() = core.store.writeBackgroundHandles(1L, 2L)

    private fun showThenDecline(orch: IncomingCallOrchestrator) {
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        orch.declineOnWriter("c-1")
    }

    @Test
    fun actionable_decline_with_no_live_engine_and_a_handler_routes_to_background() {
        registerHandler()
        // liveSink null ⇒ cold process, no designated engine.
        showThenDecline(orchestrator())
        assertEquals("decline-while-dead hands off to the headless host", 1, bgStarts.size)
    }

    @Test
    fun a_live_designated_engine_short_circuits_the_background_route() {
        registerHandler()
        core.liveSink = { _, _ -> true } // a live engine consumed it (rung 1)
        showThenDecline(orchestrator())
        assertEquals(0, bgStarts.size)
    }

    @Test
    fun no_registered_handler_leaves_events_for_replay() {
        // no writeBackgroundHandles ⇒ rung 3 (replay), never the host.
        showThenDecline(orchestrator())
        assertEquals(0, bgStarts.size)
    }

    @Test
    fun ringing_only_show_is_not_actionable_and_never_routes() {
        registerHandler()
        orchestrator().showOnWriter(TestSupport.incomingCall(id = "c-1"))
        assertEquals("a live-only CallRinging is not headless-delivered", 0, bgStarts.size)
    }

    @Test
    fun host_trigger_failure_records_a_delivery_failure_incident() {
        registerHandler()
        showThenDecline(orchestrator(starter = { throw RuntimeException("host down") }))

        assertEquals("the throw is contained (never propagates to the action path)", 0, bgStarts.size)
        assertTrue(core.ringLog.awaitDrained())
        val incidentsDir = File(File(File(context.applicationContext.noBackupFilesDir, CoreStore.DIR_NAME), "logs"), "incidents")
        val recorded =
            (incidentsDir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".jsonl") }
                .any { it.readText().contains("incident:DELIVERY_FAILURE") }
        assertTrue("host/runner failure ⇒ DELIVERY_FAILURE, then replay", recorded)
    }
}
