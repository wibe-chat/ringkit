package com.wibechat.ringkit.diag

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.TestSupport
import com.wibechat.ringkit.ui.CallNotificationFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * §11.3 diagnostics v1: reality-read findings (channel demotion/block,
 * notifications-blocked, resolvables, OEM table) + the last-show/last-delivery
 * breadcrumbs the adapter surfaces, + §12 OemSupport table behavior.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsTest {

    private lateinit var context: Context
    private lateinit var core: RingKitCore.Instance

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

    private fun findings(): Map<String, DiagnosticsEngine.Finding> =
        DiagnosticsEngine.run(context, core).associateBy { it.id }

    private fun ensureChannels() =
        CallNotificationFactory(context, core.repository.currentConfig(), core.time, core.ringLog, { 424200 })
            .ensureChannels()

    // ── channel findings (§8.11 rows read back) ──────────────────────────

    @Test
    fun healthy_state_reports_no_errors() {
        ensureChannels()
        val errors = findings().values.filter { it.severity == DiagnosticsEngine.Severity.ERROR }
        assertTrue("healthy device ⇒ zero error findings, got $errors", errors.isEmpty())
    }

    @Test
    fun missing_channels_report_info_until_created() {
        assertEquals(
            DiagnosticsEngine.Severity.INFO,
            findings().getValue("incomingChannelMissing").severity,
        )
    }

    @Test
    fun demoted_incoming_channel_reports_warning_with_deep_link() {
        ensureChannels()
        notificationManager.getNotificationChannel("ringkit_incoming_v1").importance =
            NotificationManager.IMPORTANCE_LOW

        val finding = findings().getValue("incomingChannelDemoted")
        assertEquals(DiagnosticsEngine.Severity.WARNING, finding.severity)
        assertEquals("channel:incoming", finding.deepLink)
    }

    @Test
    fun blocked_channel_and_blocked_notifications_report_errors() {
        ensureChannels()
        notificationManager.getNotificationChannel("ringkit_incoming_v1").importance =
            NotificationManager.IMPORTANCE_NONE
        shadowOf(notificationManager).setNotificationsEnabled(false)

        val all = findings()
        assertEquals(DiagnosticsEngine.Severity.ERROR, all.getValue("incomingChannelBlocked").severity)
        assertEquals(DiagnosticsEngine.Severity.ERROR, all.getValue("notificationsBlocked").severity)
    }

    @Test
    fun unresolvable_icon_and_raw_ringtone_report_errors() {
        core.repository.updateConfig(
            core.repository.currentConfig().copy(
                notificationIcon = "no_such_drawable",
                defaultRingtone = "raw:no_such_tone",
            ),
        )
        val all = findings()
        assertEquals(DiagnosticsEngine.Severity.ERROR, all.getValue("notificationIconUnresolvable").severity)
        assertEquals(DiagnosticsEngine.Severity.ERROR, all.getValue("ringtoneUnresolvable").severity)
    }

    @Test
    fun unregistered_background_handler_is_an_info_finding() {
        assertEquals(
            DiagnosticsEngine.Severity.INFO,
            findings().getValue("backgroundHandlerUnregistered").severity,
        )
        core.store.writeBackgroundHandles(1L, 2L)
        assertFalse(findings().containsKey("backgroundHandlerUnregistered"))
    }

    // ── §11.3 breadcrumbs ────────────────────────────────────────────────

    @Test
    fun show_records_the_last_show_result_breadcrumb() {
        val orchestrator = IncomingCallOrchestrator(context, core) { _, _ -> }
        orchestrator.showOnWriter(TestSupport.incomingCall(id = "bc-1"))

        val raw = core.store.readLastShowResult()
        assertNotNull(raw)
        val json = org.json.JSONObject(raw!!)
        assertEquals("SHOWN", json.getString("outcome"))
    }

    @Test
    fun degraded_show_breadcrumb_carries_the_degradation_set() {
        val denied =
            IncomingCallOrchestrator(context, core) { _, _ -> throw IllegalStateException("fgs denied") }
        denied.showOnWriter(TestSupport.incomingCall(id = "bc-2"))

        val json = org.json.JSONObject(core.store.readLastShowResult()!!)
        val degradations =
            (0 until json.getJSONArray("degradations").length())
                .map { json.getJSONArray("degradations").getString(it) }
        assertTrue("FGS_DENIED recorded", "FGS_DENIED" in degradations)
    }

    @Test
    fun live_delivery_records_the_delivery_path_breadcrumb() {
        core.liveSink = { _, _ -> true }
        val orchestrator = IncomingCallOrchestrator(context, core) { _, _ -> }
        orchestrator.showOnWriter(TestSupport.incomingCall(id = "bc-3"))
        assertEquals("live", core.store.readLastDeliveryPath())
    }

    // ── §12 OEM table ────────────────────────────────────────────────────

    @Test
    fun oem_table_matches_by_manufacturer_and_lists_restrictions() {
        val samsung = OemSupport.entryFor(manufacturer = "samsung", brand = "samsung")
        assertNotNull(samsung)
        assertTrue("sleepingApps" in samsung!!.restrictions)

        val xiaomi = OemSupport.entryFor(manufacturer = "Xiaomi", brand = "Redmi")
        assertTrue("autostartPermission" in xiaomi!!.restrictions)

        assertEquals("unknown OEM → no entry", null, OemSupport.entryFor("google", "pixel"))
    }

    @Test
    fun oem_open_screen_is_false_when_nothing_resolves() {
        // Robolectric has none of the OEM packages installed — graceful false.
        assertFalse(OemSupport.openScreen(context, OemSupport.Screen.AUTOSTART))
    }
}
