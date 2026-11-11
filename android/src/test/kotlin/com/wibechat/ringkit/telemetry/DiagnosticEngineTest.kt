package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.CoreLogLevel
import java.util.Base64
import kotlin.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class DiagnosticEngineTest {
    private val requestId = "10000000-0000-4000-8000-000000000001"
    private fun enabled(h: EngineHarness, kind: String = "diagnostic_session"): () -> Unit {
        var requestState = "offered"
        var open = true
        h.transport.defaultResponse = { p ->
            val kindSent = p.optString("kind")
            val b = FakeTransport.okBody(kindSent).put("telemetry_protocol", 2).put("diagnostics_enabled", true).put("status", "ok")
            if (kindSent == "control") {
                if (p.optString("action") == "acknowledged") requestState = "acknowledged"
                val offered = JSONObject().put("id", requestId).put("kind", kind).put("state", requestState)
                    .put("duration_ms", 1_800_000).put("max_calls", 20).put("max_bytes", if (kind == "full_buffer") 8388608 else 524288)
                b.put("requests", if (open) JSONArray().put(offered) else JSONArray())
                if (!open) b.put("request_states", JSONArray().put(JSONObject().put("id", requestId).put("state", "cancelled")))
                if (p.has("request_id")) b.put("request_state", requestState)
            }
            if (kindSent == "diagnostic_report") b.put("state", if (p.getJSONObject("report").getJSONObject("metadata").getBoolean("final")) "received" else "collecting")
            CheckinResult.Http(200, b)
        }
        return { open = false }
    }
    private fun finishCall(h: EngineHarness, id: String = "call-1") {
        h.state.diagnostics.noteCall(id, true, h.time)
        h.ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.start", id, "policy", mapOf("sound" to "true", "name" to "Private Caller"))
        h.ringLog.log(CoreLogLevel.INFO, "ring", "RingtonePlayer.advance", id, "Playing", mapOf("engine" to "MediaPlayer", "audible" to "true"))
        h.time.advanceMs(1_000)
        h.state.diagnostics.noteCall(id, false, h.time)
        assertTrue(h.ringLog.awaitDrained())
    }
    private fun reports(h: EngineHarness) = h.transport.sent.filter { it.payload.optString("kind") == "diagnostic_report" }

    @Test fun r14_v1_server_never_receives_v2_kinds_and_metadata_is_debounced() {
        val h = EngineHarness()
        assertEquals(TelemetryRun.DEFERRED, h.engine().runControl())
        assertEquals(TelemetryRun.DEFERRED, h.engine().runControl())
        assertEquals(listOf("register"), h.transport.kinds())
    }
    @Test fun r14_session_captures_apparently_playing_call_and_final_manifest() {
        val h = EngineHarness(); enabled(h)
        assertEquals(TelemetryRun.OK, h.engine().runControl())
        assertTrue(h.state.diagnostics.requests().single().getBoolean("acked"))
        finishCall(h)
        assertEquals(TelemetryRun.OK, h.engine().runControl())
        val first = reports(h).single().payload.getJSONObject("report")
        val raw = gunzipToString(Base64.getDecoder().decode(first.getString("records_gzip_b64")))
        assertTrue(raw.contains("Playing")); assertFalse(raw.contains("Private Caller"))
        assertFalse(first.getJSONObject("metadata").getBoolean("final"))
        h.time.advanceMs(1_800_001)
        h.engine().runControl()
        val final = reports(h).last().payload.getJSONObject("report").getJSONObject("metadata")
        assertTrue(final.getBoolean("final")); assertEquals(2, final.getJSONArray("manifest").length())
        assertEquals("time_limit", final.getString("end_reason"))
    }
    @Test fun r14_retry_uses_identical_report_after_new_logs_and_process_reload() {
        val h = EngineHarness(); enabled(h); h.engine().runControl(); finishCall(h)
        val responder = h.transport.defaultResponse
        var fail = true
        h.transport.defaultResponse = { p -> if (fail && p.optString("kind") == "diagnostic_report") CheckinResult.Http(503, null) else responder(p) }
        assertEquals(TelemetryRun.RETRY, h.engine().runControl())
        val original = reports(h).single().payload.toString()
        val reloaded = DiagnosticStore(java.io.File(h.dir, "diagnostic-requests.json"), "install-1")
        assertEquals(original, reloaded.requests().single().getJSONArray("pending").getJSONObject(0).toString())
        h.time.advanceMs(5_000); fail = false
        h.ringLog.log(CoreLogLevel.INFO, "ring", "later", "call-1", "must not change snapshot")
        assertEquals(TelemetryRun.OK, h.engine().runControl())
        assertEquals(original, reports(h).last().payload.toString())
    }
    @Test fun r14_remote_cancellation_prevents_pending_upload() {
        val h = EngineHarness(); val cancel = enabled(h); h.engine().runControl(); finishCall(h)
        cancel(); h.engine().runControl()
        assertTrue(reports(h).isEmpty())
        assertEquals("cancelled", h.state.diagnostics.requests().single().getString("end_reason"))
    }
    @Test fun r14_reboot_ends_capture_without_extending_deadline() {
        val h = EngineHarness(); enabled(h); h.engine().runControl(); finishCall(h)
        h.time.boot++
        h.engine().runControl()
        assertEquals("reboot", reports(h).last().payload.getJSONObject("report").getJSONObject("metadata").getString("end_reason"))
        assertFalse(h.state.diagnostics.active(h.time))
    }
    @Test fun r14_ordinary_budget_does_not_block_explicit_session() {
        val h = EngineHarness(budgetKb = 1); enabled(h); h.engine().runControl()
        h.state.exhaustBudget(TelemetryEngine.dayUtc(h.time.wallMs()), 1024 * 1024)
        finishCall(h); assertEquals(TelemetryRun.OK, h.engine().runControl())
        assertEquals(1, reports(h).size)
    }
    @Test fun r14_twenty_call_limit_ends_session_and_reports_actual_count() {
        val h = EngineHarness(); enabled(h); h.engine().runControl()
        repeat(21) { finishCall(h, "call-$it") }
        h.engine().runControl()
        assertEquals(21, reports(h).size) // 20 call reports plus one final manifest.
        val meta = reports(h).last().payload.getJSONObject("report").getJSONObject("metadata")
        assertEquals("call_limit", meta.getString("end_reason")); assertEquals(20, meta.getInt("observed_call_count"))
        assertTrue(reports(h).sumOf { it.payload.toString().toByteArray(Charsets.UTF_8).size } <= 524288)
    }
    @Test fun r14_full_pull_is_correlated_and_empty_is_representable() {
        val h = EngineHarness(); enabled(h, "full_buffer")
        assertEquals(TelemetryRun.OK, h.engine().runControl())
        val p = reports(h).single().payload.getJSONObject("report")
        assertEquals(requestId, p.getString("request_id")); assertEquals(1, p.getInt("of"))
        assertEquals("", gunzipToString(Base64.getDecoder().decode(p.getString("records_gzip_b64"))))
    }
    @Test fun r14_disabling_during_first_upload_stops_following_reports() {
        val h = EngineHarness(); enabled(h); h.engine().runControl()
        finishCall(h, "one"); finishCall(h, "two")
        val original = h.transport.defaultResponse
        h.transport.defaultResponse = { p ->
            if (p.optString("kind") == "diagnostic_report") h.enabled = false
            original(p)
        }
        assertEquals(TelemetryRun.DEFERRED, h.engine().runControl())
        assertEquals(1, reports(h).size)
    }
    @Test fun r14_failed_journal_write_cannot_be_acknowledged() {
        val h = EngineHarness(); enabled(h)
        java.io.File(h.dir, "diagnostic-requests.json").mkdir()
        assertFails { h.engine().runControl() }
        assertFalse(h.transport.sent.any { it.payload.optString("action") == "acknowledged" })
    }
    @Test fun r14_large_unicode_and_incompressible_call_has_bounded_snapshot_work() {
        val h = EngineHarness(); enabled(h); h.engine().runControl()
        h.state.diagnostics.noteCall("large", true, h.time)
        val random = java.util.Random(7)
        repeat(650) {
            val bytes = ByteArray(1800).also(random::nextBytes)
            h.ringLog.log(CoreLogLevel.INFO, "ring", "test", "large", "മലയാളം😀", mapOf("context" to Base64.getEncoder().encodeToString(bytes)))
            if (it % 20 == 0) assertTrue(h.ringLog.awaitDrained())
        }
        h.state.diagnostics.noteCall("large", false, h.time)
        val start = System.nanoTime()
        assertEquals(TelemetryRun.OK, h.engine().runControl())
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5000, "snapshot compression must be bounded")
        val report = reports(h).first().payload
        assertTrue(report.toString().toByteArray(Charsets.UTF_8).size <= 524288 - 8192)
        assertTrue(report.getJSONObject("report").getJSONObject("metadata").getBoolean("truncated"))
        assertTrue(report.getJSONObject("report").getJSONObject("metadata").getInt("omitted_records") > 0)
    }

    @Test fun r14_session_deadline_stops_admission_before_the_next_network_worker() {
        val h = EngineHarness(); enabled(h); h.engine().runControl()
        finishCall(h, "inside-session")
        h.time.advanceMs(1_800_001)
        finishCall(h, "outside-session")
        h.engine().runControl()
        val raw = reports(h).joinToString("\n") { gunzipToString(Base64.getDecoder().decode(it.payload.getJSONObject("report").getString("records_gzip_b64"))) }
        assertTrue(raw.contains("inside-session")); assertFalse(raw.contains("outside-session"))
    }
    @Test fun r14_capability_disablement_cannot_resume_the_old_session() {
        val h = EngineHarness(); enabled(h); h.engine().runControl()
        h.engine().applyKnobs(FakeTransport.okBody().put("telemetry_protocol", 2).put("diagnostics_enabled", false))
        finishCall(h, "disabled-call")
        h.engine().runControl()
        assertFalse(h.state.diagnostics.active(h.time)); assertTrue(reports(h).isEmpty())
    }

    @Test fun r14_lost_final_response_reconciles_durable_received_state() {
        val h = EngineHarness(); enabled(h, "full_buffer")
        val responder = h.transport.defaultResponse
        var stored = false
        h.transport.defaultResponse = { p ->
            when {
                p.optString("kind") == "diagnostic_report" -> { stored = true; CheckinResult.Http(503, null) }
                stored && p.optString("kind") == "control" -> CheckinResult.Http(200,
                    FakeTransport.okBody("control").put("status", "ok").put("telemetry_protocol", 2).put("diagnostics_enabled", true)
                        .put("requests", JSONArray()).put("request_states", JSONArray().put(JSONObject().put("id", requestId).put("state", "received_empty"))))
                else -> responder(p)
            }
        }
        assertEquals(TelemetryRun.RETRY, h.engine().runControl())
        assertEquals(TelemetryRun.OK, h.engine().runControl())
        val saved = h.state.diagnostics.requests().single()
        assertEquals("received_empty", saved.getString("end_reason"))
        assertTrue(saved.getBoolean("complete")); assertEquals(1, reports(h).size)
    }

    @Test fun r14_build_change_cannot_attribute_new_buffer_records_to_an_old_build() {
        val h = EngineHarness(); enabled(h); h.engine().runControl()
        finishCall(h, "same-call")
        val journal = java.io.File(h.dir, "diagnostic-requests.json")
        val contents = JSONObject(journal.readText())
        contents.getJSONObject("requests").getJSONObject(requestId).getJSONObject("identity").put("sdk_build_id", "previous-build")
        journal.writeText(contents.toString())
        // Recreate state as a new native process would after a package update.
        val reloaded = TelemetryState.open(h.dir, "install-1")
        val config = h.config
        val engine = TelemetryEngine("install-1", h.time, h.ringLog, { CoreLogLevel.ERROR }, { "9.9.9" }, "com.example.host", config, reloaded, h.transport, h.scheduler)
        h.ringLog.log(CoreLogLevel.INFO, "ring", "test", "same-call", "NEW BUILD must not be attributed to previous-build")
        engine.runControl()
        val raw = reports(h).joinToString("\n") { gunzipToString(Base64.getDecoder().decode(it.payload.getJSONObject("report").getString("records_gzip_b64"))) }
        assertFalse(raw.contains("NEW BUILD"))
        assertEquals("build_changed", reports(h).last().payload.getJSONObject("report").getJSONObject("metadata").getString("end_reason"))
    }

    @Test fun r14_retry_reason_is_persisted_and_sent_on_the_next_successful_control() {
        val h = EngineHarness(); enabled(h); h.engine().runControl(); finishCall(h)
        val responder = h.transport.defaultResponse
        h.transport.defaultResponse = { p -> if (p.optString("kind") == "diagnostic_report") CheckinResult.Http(503, null) else responder(p) }
        assertEquals(TelemetryRun.RETRY, h.engine().runControl())
        h.transport.defaultResponse = responder
        h.engine().runControl()
        val control = h.transport.sent.last { it.payload.optString("kind") == "control" }.payload
        assertEquals("http_5xx", control.getJSONArray("diagnostic_status").getJSONObject(0).getString("outcome"))
    }

}
