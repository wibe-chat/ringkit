package com.wibechat.ringkit.telemetry

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.wibechat.ringkit.core.CoreCallOptions
import com.wibechat.ringkit.core.CoreCallState
import com.wibechat.ringkit.core.CoreConfig
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreStore
import com.wibechat.ringkit.core.CoreTelemetryConfig
import com.wibechat.ringkit.core.IncomingCallOrchestrator
import com.wibechat.ringkit.core.RingKitCore
import com.wibechat.ringkit.core.TestSupport
import com.wibechat.ringkit.ui.RingPlaybackBackend
import com.wibechat.ringkit.ui.RingPlaybackCancellation
import com.wibechat.ringkit.ui.RingPlaybackEngine
import com.wibechat.ringkit.ui.RingPlaybackScheduler
import com.wibechat.ringkit.ui.RingtonePlayer
import com.wibechat.ringkit.ui.RingAudioState
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A real playback fault must reach HTTP without explicitly injecting an incident/report. */
@RunWith(RobolectricTestRunner::class)
class RingtoneTelemetryIntegrationTest {
    private lateinit var context: Context
    private lateinit var server: MockIngestServer
    private lateinit var core: RingKitCore.Instance
    private lateinit var scheduler: RecordingScheduler
    private lateinit var player: RingtonePlayer
    private var owner = true
    private val probes = mutableListOf<() -> Unit>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockIngestServer()
        scheduler = RecordingScheduler()
        Telemetry.resetForTests()
        RingKitCore.resetForTests()
        Telemetry.schedulerFactory = { scheduler }
        Telemetry.transportFactory = { HttpCheckinTransport() }
        CoreStore.open(context).writeConfig(CoreConfig(telemetry = CoreTelemetryConfig(
            endpoint = "http://127.0.0.1:${server.port}",
            apiKey = "test-fleet-key",
            deviceLabel = "audio-test",
            uploadLevel = CoreLogLevel.INFO,
            dailyUploadBudgetKb = 200,
        )))
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_RING, 3, 0)
        core = RingKitCore.get(context)
    }

    @After
    fun tearDown() {
        if (::player.isInitialized) core.writer.await { player.stop() }
        Telemetry.resetForTests()
        RingKitCore.resetForTests()
        server.stop()
    }

    @Test
    fun r37_unrecoverable_ring_automatically_schedules_and_uploads_redacted_context() {
        start(FaultBackend())
        assertTrue(core.ringLog.awaitDrained())
        assertTrue(awaitTrue { "incident" in scheduler.calls }, "playback failure must schedule its own upload")

        // Drain the automatically requested job through the real worker and HTTP transport.
        val worker = TestListenableWorkerBuilder<IncidentUploadWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        val upload = synchronized(server.seen) { server.seen.single { it.payload.optString("kind") == "incident" } }
        val incident = upload.payload.getJSONObject("incident")
        assertEquals("RING_AUDIO_FAILED", incident.getString("incident_type"))
        assertEquals("audio-fault", incident.getString("call_id"))
        assertEquals("RingtonePlayer.advance", incident.getString("codepoint"))
        val records = gunzipToString(Base64.getDecoder().decode(incident.getString("records_gzip_b64")))
        assertTrue(records.contains("ringVolume"))
        assertTrue(records.contains("focusResult"))
        assertTrue(records.contains("sourceKind"))
        assertFalse(records.contains("private-source"), "source URI/exception messages must not leak")
        assertFalse(records.contains("Sensitive Caller"))
        assertEquals(CoreCallState.RINGING, core.repository.callFor("audio-fault")!!.state)
    }

    @Test
    fun native_activation_bootstraps_metadata_without_waiting_for_heartbeat() {
        assertTrue(awaitTrue { "control" in scheduler.calls }, "activation must schedule metadata discovery before canary enrollment")
    }

    class VisibleHost : android.app.Activity(), androidx.lifecycle.LifecycleOwner {
        override fun hasWindowFocus() = false
        val registry = androidx.lifecycle.LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @Test fun r14_enabling_while_started_without_window_focus_keeps_foreground_polling() {
        val activity = org.robolectric.Robolectric.buildActivity(VisibleHost::class.java).setup().get()
        activity.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        // No focus: mirrors enablement while the shade or a dialog owns focus.
        assertFalse(activity.hasWindowFocus())
        val entries = mutableListOf<Boolean>()
        Telemetry.session!!.lifecycle!!.close()
        Telemetry.session!!.lifecycle = TelemetryLifecycle(context, { entries += it }, { 300_000L })
        Telemetry.seedForeground(activity)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(true), entries)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(5))
        assertEquals(listOf(true, false), entries)
        Telemetry.session!!.lifecycle!!.onActivityStopped(activity)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(5))
        assertEquals(2, entries.size)
    }

    @Test
    fun r14_native_call_bus_and_worker_capture_a_call_without_an_audio_incident() {
        val id = "10000000-0000-4000-8000-000000000001"
        var acknowledged = false
        server.responseTransform = { request, response ->
            response.put("telemetry_protocol", 2).put("diagnostics_enabled", true).put("status", "ok")
            if (request.optString("kind") == "control") {
                if (request.optString("action") == "acknowledged") acknowledged = true
                response.put("request_state", if (acknowledged) "acknowledged" else "offered")
                    .put("requests", org.json.JSONArray().put(org.json.JSONObject().put("id", id)
                        .put("kind", "diagnostic_session").put("state", if (acknowledged) "acknowledged" else "offered")))
            }
            if (request.optString("kind") == "diagnostic_report") response.put("state", "collecting")
            response
        }
        fun sync() = runBlocking { TestListenableWorkerBuilder<ControlWorker>(context).build().doWork() }
        assertEquals(ListenableWorker.Result.success(), sync())
        start(DelayedBackend())
        core.writer.await { IncomingCallOrchestrator(context, core) { _, _ -> }.declineOnWriter("audio-fault") }
        drain()
        assertTrue("control:successor" in scheduler.calls)
        assertEquals(ListenableWorker.Result.success(), sync())
        val upload = server.seen.single { it.payload.optString("kind") == "diagnostic_report" }.payload.getJSONObject("report")
        val raw = gunzipToString(Base64.getDecoder().decode(upload.getString("records_gzip_b64")))
        assertTrue(raw.contains("audio-fault")); assertTrue(raw.contains("policy"))
        assertFalse(raw.contains("Sensitive Caller"))
        assertFalse(server.seen.any { it.payload.optString("kind") == "incident" })
    }

    @Test
    fun native_build_identity_is_packaged_and_snapshot_keeps_capture_identity() {
        start(FaultBackend())
        drain()
        assertEquals(ListenableWorker.Result.success(), runBlocking {
            TestListenableWorkerBuilder<IncidentUploadWorker>(context).build().doWork()
        })
        val device = server.seen.first { it.payload.optString("kind") == "register" }.payload.getJSONObject("device")
        assertTrue(device.getString("sdk_build_id").matches(Regex("sha256:[a-f0-9]{64}")))
        assertEquals(2, device.getInt("telemetry_protocol"))
        assertTrue(device.has("host_version_code"))
        assertTrue(device.has("host_debuggable"))
        val identity = server.seen.first { it.payload.optString("kind") == "incident" }
            .payload.getJSONObject("incident").getJSONObject("identity")
        assertEquals(device.getString("sdk_build_id"), identity.getString("sdk_build_id"))
    }

    @Test
    fun r37_delayed_failure_and_duplicate_callback_create_one_incident() {
        val backend = DelayedBackend()
        start(backend)
        backend.error(1, -19)
        backend.error(1, -19)
        drain()
        assertEquals(1, scheduler.calls.count { it == "incident" })
    }

    @Test
    fun r37_verified_not_playing_exhausts_ladder_and_reports() {
        start(DelayedBackend(playing = false))
        probes.toList().forEach { it() }
        drain()
        assertTrue("incident" in scheduler.calls)
    }

    @Test
    fun r37_recovered_fallback_is_not_a_failed_ring() {
        start(object : FaultBackend() {
            override fun openBundled(attributes: AudioAttributes, onError: (Int, Int) -> Unit) = Engine()
        })
        drain()
        assertFalse("incident" in scheduler.calls)
    }

    @Test
    fun r37_stopped_or_superseded_callback_cannot_report() {
        val backend = DelayedBackend()
        start(backend)
        core.writer.await { player.stop() }
        backend.error(1, -19)
        probes.toList().forEach { it() }
        drain()
        assertFalse("incident" in scheduler.calls)
    }

    @Test
    fun r37_non_owner_failure_cannot_report() {
        val backend = DelayedBackend()
        start(backend)
        owner = false
        backend.error(1, -19)
        drain()
        assertFalse("incident" in scheduler.calls)
    }

    @Test
    fun r37_focus_loss_alone_is_not_playback_failure() {
        start(DelayedBackend())
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(manager).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        drain()
        assertFalse("incident" in scheduler.calls)
    }

    @Test
    fun r37_policy_silence_does_not_attempt_or_report_audio() {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        start(FaultBackend())
        drain()
        assertFalse("incident" in scheduler.calls)
    }

    @Test
    fun r37_retry_preserves_failure_and_shipping_dedup() {
        start(FaultBackend())
        drain()
        synchronized(server.statusQueue) { server.statusQueue.addAll(listOf(200, 503, 200)) }
        fun upload() = runBlocking { TestListenableWorkerBuilder<IncidentUploadWorker>(context).build().doWork() }
        assertEquals(ListenableWorker.Result.retry(), upload())
        assertEquals(ListenableWorker.Result.success(), upload())
        assertEquals(ListenableWorker.Result.success(), upload())
        val uploads = synchronized(server.seen) { server.seen.filter { it.payload.optString("kind") == "incident" } }
        assertEquals(2, uploads.size)
        assertEquals(uploads[0].payload.getJSONObject("incident").getString("fingerprint"),
            uploads[1].payload.getJSONObject("incident").getString("fingerprint"))
    }

    @Test
    fun r37_reentrant_stop_observer_cannot_report_stale_failure() {
        start(FaultBackend()) { state -> if (state is RingAudioState.Failed) player.stop() }
        drain()
        assertFalse("incident" in scheduler.calls)
    }

    private fun drain() {
        core.writer.await { }
        assertTrue(core.ringLog.awaitDrained())
        // Await the telemetry executor barrier through an existing scheduled operation.
        scheduler.calls.removeAll { it == "userReport" }
        Telemetry.userReportNow()
        assertTrue(awaitTrue { "userReport" in scheduler.calls })
    }

    private fun start(backend: RingPlaybackBackend, observer: ((RingAudioState) -> Unit)? = null) {
        player = RingtonePlayer(context, core.ringLog, backend, object : RingPlaybackScheduler {
            override fun schedule(delayMs: Long, block: () -> Unit): RingPlaybackCancellation {
                probes += block
                return RingPlaybackCancellation { }
            }
        }, isRingingOwner = { owner }, dispatchPlayback = { core.writer.post(it) })
        core.writer.await {
            val call = TestSupport.incomingCall(id = "audio-fault", name = "Sensitive Caller").copy(
                options = CoreCallOptions(ringtone = "content://ringtone/private-source"),
            )
            IncomingCallOrchestrator(context, core) { _, _ -> }.showOnWriter(call)
            val record = core.repository.callFor(call.id)!!
            player.start(record, null, anyInCallState = false, onAudioState = observer)
        }
    }

    private open class FaultBackend : RingPlaybackBackend {
        override fun openPrimary(uri: Uri, attributes: AudioAttributes, onError: (Int, Int) -> Unit): RingPlaybackEngine =
            throw IllegalStateException("cannot read $uri")

        override fun openRingtone(uri: Uri, attributes: AudioAttributes): RingPlaybackEngine? =
            throw IllegalStateException("cannot read $uri")

        override fun openBundled(attributes: AudioAttributes, onError: (Int, Int) -> Unit): RingPlaybackEngine =
            throw IllegalStateException("cannot read bundled tone")
    }

    private class Engine(private val playing: Boolean = true) : RingPlaybackEngine {
        override val name = "MediaPlayer"
        override fun start() = Unit
        override fun stop() = Unit
        override fun isPlaying() = playing
    }

    private class DelayedBackend(private val playing: Boolean = true) : FaultBackend() {
        lateinit var error: (Int, Int) -> Unit
        override fun openPrimary(uri: Uri, attributes: AudioAttributes, onError: (Int, Int) -> Unit): RingPlaybackEngine {
            error = onError
            return Engine(playing)
        }
    }
}
