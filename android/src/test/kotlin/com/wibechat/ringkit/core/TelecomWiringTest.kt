package com.wibechat.ringkit.core

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * §8.7 / §8.11 telecom WIRING against a fake [TelecomBridge] — a real
 * `CallsManager` needs a live system Telecom service, so the adapter is
 * device-verified (M5.3/M5.7) and here we prove the orchestrator contract:
 * lazy (telecom-OFF never touches the bridge), addCall-BEFORE-post ordering,
 * the notifications-blocked bypass (§8.11 row 2) and its fallback flip, and
 * answer/setActive/disconnect mirroring (§5.1).
 */
@RunWith(RobolectricTestRunner::class)
class TelecomWiringTest {

    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance

    /** Shared ordering log: the fake bridge appends "addCall"; serviceStarter "post". */
    private val order = mutableListOf<String>()
    private var providerInvocations = 0

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

    /** Records every mirror call; addCall returns [addCallResult] + logs ordering. */
    private inner class FakeBridge(private val addCallResult: Boolean) : TelecomBridge {
        val answers = mutableListOf<String>()
        val actives = mutableListOf<String>()
        val disconnects = mutableListOf<Pair<String, TelecomDisconnectCause>>()
        val audioRouteRequests = mutableListOf<CoreAudioRoute>()
        var setAudioRouteResult = true
        /** Captured so tests can fire hardware callbacks (BT/watch answer, §8.7). */
        var lastHooks: TelecomHooks? = null
        private var reg = false

        override val isRegistered: Boolean get() = reg

        override fun addCall(call: CoreIncomingCall, hooks: TelecomHooks): Boolean {
            order.add("addCall")
            lastHooks = hooks
            reg = addCallResult
            return addCallResult
        }

        override fun onLocalAnswer(callId: String) { answers.add(callId) }
        override fun onLocalActive(callId: String) { actives.add(callId) }
        override fun onLocalDisconnect(callId: String, cause: TelecomDisconnectCause) {
            disconnects.add(callId to cause)
        }

        override fun setAudioRoute(route: CoreAudioRoute): Boolean {
            audioRouteRequests.add(route)
            return setAudioRouteResult
        }
    }

    private fun orchestrator(bridge: TelecomBridge?) =
        IncomingCallOrchestrator(
            context,
            core,
            telecomProvider = { providerInvocations++; bridge },
            serviceStarter = { _, _ -> order.add("post") },
        )

    private fun enableTelecom() {
        core.repository.updateConfig(core.repository.currentConfig().copy(telecomIntegration = true))
    }

    // ── lazy: telecom OFF never classloads/touches the bridge ────────────

    @Test
    fun telecom_off_never_invokes_the_provider() {
        // Default config: telecomIntegration = false.
        val orch = orchestrator(FakeBridge(addCallResult = true))
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        orch.declineOnWriter("c-1")

        assertEquals("telecom-OFF never touches the provider (no classload)", 0, providerInvocations)
        assertFalse("no telecom addCall in the ordering log", order.contains("addCall"))
    }

    // ── ordering: addCall BEFORE the notification post (§8.7) ────────────

    @Test
    fun telecom_on_addCall_precedes_the_notification_post() {
        enableTelecom()
        val orch = orchestrator(FakeBridge(addCallResult = true))
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))

        assertEquals("addCall is registered before the surface is posted", listOf("addCall", "post"), order)
    }

    // ── §8.11 row 2: notifications-blocked + telecom-ON → bypass → shown ──

    @Test
    fun notifications_blocked_with_telecom_addCall_success_bypasses_and_reports_shown() {
        enableTelecom()
        shadowOf(notificationManager).setNotificationsEnabled(false)
        val orch = orchestrator(FakeBridge(addCallResult = true))

        val outcome = orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))

        assertEquals("telecom exemption ⇒ shown, not failed", TransitionOutcome.SHOWN, outcome.result.outcome)
        assertNull("no failure — the CallStyle notifications-blocked block is bypassed", outcome.failReason)
        assertTrue("addCall-before-post ordering held through the bypass", order == listOf("addCall", "post"))
    }

    // ── fallback: addCall failure re-evaluates §8.11 as telecom-OFF ──────

    @Test
    fun notifications_blocked_with_telecom_addCall_failure_falls_back_to_failed() {
        enableTelecom()
        shadowOf(notificationManager).setNotificationsEnabled(false)
        val orch = orchestrator(FakeBridge(addCallResult = false))

        val outcome = orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))

        assertEquals(
            "failed addCall ⇒ re-evaluate as OFF ⇒ row 2 flips back to failed(notificationsBlocked)",
            CoreFailReason.NOTIFICATIONS_BLOCKED,
            outcome.failReason,
        )
        assertTrue("addCall was attempted", order.contains("addCall"))
        assertFalse("invisible ringing is banned: no post on fallback-failed", order.contains("post"))
    }

    // ── mirroring (§5.1 → Telecom) ───────────────────────────────────────

    @Test
    fun decline_mirrors_a_rejected_disconnect() {
        enableTelecom()
        val bridge = FakeBridge(addCallResult = true)
        val orch = orchestrator(bridge)
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))

        orch.declineOnWriter("c-1")

        assertEquals(listOf("c-1" to TelecomDisconnectCause.REJECTED), bridge.disconnects)
    }

    @Test
    fun accept_then_connected_mirror_answer_then_active() {
        enableTelecom()
        val bridge = FakeBridge(addCallResult = true)
        val orch = orchestrator(bridge)
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))

        orch.acceptProgrammaticOnWriter("c-1")
        orch.setConnectedOnWriter("c-1")

        assertEquals("accept → Telecom answer", listOf("c-1"), bridge.answers)
        assertEquals("setCallConnected → Telecom setActive", listOf("c-1"), bridge.actives)
        assertTrue("no disconnect on the happy path", bridge.disconnects.isEmpty())
    }

    @Test
    fun timeout_mirrors_a_missed_disconnect() {
        enableTelecom()
        val bridge = FakeBridge(addCallResult = true)
        val orch = orchestrator(bridge)
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))

        orch.timeoutOnWriter("c-1")

        assertEquals(listOf("c-1" to TelecomDisconnectCause.MISSED), bridge.disconnects)
    }

    @Test
    fun eviction_disconnects_the_evicted_telecom_call() {
        // §5.2 cap eviction bypasses executeEffects; without the executeEviction
        // mirror the evicted call's addCall coroutine would leak (it suspends in
        // core-telecom's coroutineScope until disconnect). cap 2 + 3 arrivals ⇒
        // the oldest WAITING call (c-2) is evicted with TIMED_OUT semantics.
        core.repository.updateConfig(
            core.repository.currentConfig().copy(telecomIntegration = true, maxConcurrentRinging = 2),
        )
        val bridge = FakeBridge(addCallResult = true)
        val orch = orchestrator(bridge)
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        orch.showOnWriter(TestSupport.incomingCall(id = "c-2"))
        orch.showOnWriter(TestSupport.incomingCall(id = "c-3"))

        assertTrue(
            "the evicted waiting call disconnects its Telecom session (MISSED)",
            bridge.disconnects.contains("c-2" to TelecomDisconnectCause.MISSED),
        )
    }

    // ── audio routing (§6.3, M5.4) ───────────────────────────────────────

    @Test
    fun setAudioRoute_off_returns_wrongState_and_never_touches_the_bridge() {
        val bridge = FakeBridge(addCallResult = true)
        val orch = orchestrator(bridge)
        assertFalse("wrongState when telecom is off", orch.setAudioRouteOnWriter(CoreAudioRoute.SPEAKER))
        assertTrue("bridge untouched when off (no classload)", bridge.audioRouteRequests.isEmpty())
    }

    @Test
    fun setAudioRoute_on_delegates_to_the_bridge_and_reports_ok() {
        enableTelecom()
        val bridge = FakeBridge(addCallResult = true).apply { setAudioRouteResult = true }
        val orch = orchestrator(bridge)
        assertTrue(orch.setAudioRouteOnWriter(CoreAudioRoute.BLUETOOTH))
        assertEquals(listOf(CoreAudioRoute.BLUETOOTH), bridge.audioRouteRequests)
    }

    @Test
    fun setAudioRoute_on_but_route_unavailable_reports_wrongState() {
        enableTelecom()
        val bridge = FakeBridge(addCallResult = true).apply { setAudioRouteResult = false }
        val orch = orchestrator(bridge)
        assertFalse("no matching endpoint ⇒ wrongState (G3)", orch.setAudioRouteOnWriter(CoreAudioRoute.WIRED_HEADSET))
    }

    @Test
    fun audio_route_change_emits_a_live_only_event() {
        enableTelecom()
        val delivered = mutableListOf<CoreEvent>()
        core.liveSink = { events, _ -> delivered.addAll(events); true }
        val orch = orchestrator(FakeBridge(addCallResult = true))
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        delivered.clear() // drop the CallRinging emitted by show

        orch.emitAudioRouteChanged("c-1", CoreAudioRoute.SPEAKER)

        assertEquals(1, delivered.size)
        assertEquals(CoreEventType.AUDIO_ROUTE_CHANGED, delivered.single().type)
        assertEquals(CoreAudioRoute.SPEAKER, delivered.single().audioRoute)
    }

    // ── hardware answer/disconnect routing (§8.7, R30, M5.3) ─────────────
    // The BT/watch/wired callbacks arrive via the addCall hooks; here we fire
    // the captured hooks (a real device does this) and drain the writer. Proves
    // the routing that on-device BT hardware would exercise.

    @Test
    fun hardware_answer_routes_into_the_standard_accept_path() {
        enableTelecom()
        val bridge = FakeBridge(addCallResult = true)
        val orch = orchestrator(bridge)
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))

        bridge.lastHooks!!.onHardwareAnswer("c-1")
        core.writer.await { } // drain the posted accept (FIFO single writer)

        assertEquals("BT answer → standard accept path", CoreCallState.ACCEPTED, core.repository.callFor("c-1")?.state)
    }

    @Test
    fun hardware_disconnect_of_a_ringing_call_declines() {
        enableTelecom()
        val bridge = FakeBridge(addCallResult = true)
        val orch = orchestrator(bridge)
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))

        bridge.lastHooks!!.onHardwareDisconnect("c-1")
        core.writer.await { }

        assertNull("declined ⇒ no live record", core.repository.callFor("c-1"))
        assertEquals(TerminalType.DECLINED, core.repository.tombstoneFor("c-1")?.terminalType)
    }

    @Test
    fun hardware_disconnect_of_an_active_call_ends_it() {
        enableTelecom()
        val bridge = FakeBridge(addCallResult = true)
        val orch = orchestrator(bridge)
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))
        orch.acceptProgrammaticOnWriter("c-1")
        orch.setConnectedOnWriter("c-1")

        bridge.lastHooks!!.onHardwareDisconnect("c-1")
        core.writer.await { }

        assertNull(core.repository.callFor("c-1"))
        assertEquals("active + hardware end → ENDED (not decline)", TerminalType.ENDED, core.repository.tombstoneFor("c-1")?.terminalType)
    }

    // ── fallback diagnostics (§8.7, M5.5) ────────────────────────────────

    @Test
    fun telecom_addCall_failure_records_a_TELECOM_FALLBACK_incident() {
        enableTelecom()
        val orch = orchestrator(FakeBridge(addCallResult = false))
        orch.showOnWriter(TestSupport.incomingCall(id = "c-1"))

        assertTrue(core.ringLog.awaitDrained())
        val incidentsDir =
            File(File(File(context.applicationContext.noBackupFilesDir, CoreStore.DIR_NAME), "logs"), "incidents")
        val recorded =
            (incidentsDir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".jsonl") }
                .any { it.readText().contains("incident:TELECOM_FALLBACK") }
        assertTrue("addCall failure ⇒ TELECOM_FALLBACK incident so diagnose() reports it (§8.7)", recorded)
    }
}
