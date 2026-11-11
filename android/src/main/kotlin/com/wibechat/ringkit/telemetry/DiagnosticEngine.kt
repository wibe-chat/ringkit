package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.RingLog
import com.wibechat.ringkit.core.TimeSource
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Negotiated, bounded control/capture. All work is on the serialized telemetry worker. */
internal class DiagnosticEngine(
    private val installId: String,
    private val time: TimeSource,
    private val log: RingLog,
    private val state: TelemetryState,
    private val transport: CheckinTransport,
    private val scheduler: TelemetryScheduler,
    private val url: String,
    private val device: () -> JSONObject,
    private val register: () -> TelemetryRun,
    private val applyKnobs: (JSONObject) -> Unit,
    private val diagnose: () -> JSONArray,
    private val enabled: () -> Boolean,
) {
    private val store get() = state.diagnostics
    private var parseDrops = 0
    private fun base(kind: String) = JSONObject().put("v", 2).put("kind", kind).put("install_id", installId)
        .put("device_wall_ms", time.wallMs()).put("boot_count", time.bootCount())

    fun run(): TelemetryRun {
        if (!enabled() || state.killed || state.serverProtocol < 2 || !state.diagnosticsEnabled) return TelemetryRun.DEFERRED
        // First discover commands/cancellation, then acknowledge only persisted commands.
        var outcome = control(null)
        if (outcome != TelemetryRun.OK || state.killed || !state.diagnosticsEnabled) return outcome
        for (r in store.requests().filter { !it.optBoolean("complete") && (!it.optBoolean("acked") || it.optBoolean("failed")) }) {
            outcome = control(r)
            if (outcome != TelemetryRun.OK) return outcome
        }
        store.reconcile(time, device().optString("sdk_build_id"))
        val lines = log.bufferSnapshot() ?: return TelemetryRun.RETRY
        val records = lines.filter { it.isNotBlank() }.mapNotNull(::redact)
        parseDrops = lines.count { it.isNotBlank() } - records.size
        for (r in store.requests().filter { it.optBoolean("acked") && !it.optBoolean("complete") && !it.optBoolean("failed") }) {
            if (!enabled()) return TelemetryRun.DEFERRED
            if (r.optString("kind") == "full_buffer" && !r.optBoolean("final_queued")) queuePull(r, if (r.getJSONObject("identity").optString("sdk_build_id") == device().optString("sdk_build_id")) records else emptyList())
            else if (r.optString("kind") == "diagnostic_session") queueSession(r, records)
        }
        for (r in store.requests().filter { !it.optBoolean("complete") && !it.optBoolean("failed") }) {
            val pending = r.getJSONArray("pending")
            for (i in 0 until pending.length()) {
                if (!enabled() || state.killed || !state.diagnosticsEnabled) return TelemetryRun.DEFERRED
                val p = pending.getJSONObject(i)
                if (store.requests().firstOrNull { it.getString("id") == r.getString("id") }?.optBoolean("complete") != false) break
                val response = post(p, control = false)
                if (!enabled()) return TelemetryRun.DEFERRED
                if (response == null) return TelemetryRun.RETRY
                val status = response.optString("status")
                val report = p.getJSONObject("report")
                when (status) {
                    "ok" -> store.sent(r.getString("id"), report.getString("report_id"), report.getInt("part"), response.optString("state"))
                    "cancelled", "expired" -> { store.acknowledge(r.getString("id"), status); break }
                    "over_budget" -> { store.fail(r.getString("id"), "server_budget"); return TelemetryRun.DEFERRED }
                    else -> { store.fail(r.getString("id"), "report_rejected"); return TelemetryRun.DEFERRED }
                }
            }
        }
        return TelemetryRun.OK
    }

    private fun control(request: JSONObject?): TelemetryRun {
        if (!enabled()) return TelemetryRun.DEFERRED
        val p = base("control").put("device", device()).put("diagnostic_status", store.statuses())
        request?.let {
            p.put("request_id", it.getString("id"))
                .put("action", if (it.optBoolean("lost") || it.optBoolean("failed")) "failed" else "acknowledged")
        }
        val body = post(p, control = true) ?: return TelemetryRun.RETRY
        if (!enabled()) return TelemetryRun.DEFERRED
        if (body.optString("status") == "over_budget") return TelemetryRun.DEFERRED
        if (body.optString("status") != "ok") return TelemetryRun.RETRY
        applyKnobs(body)
        if (state.killed || !state.diagnosticsEnabled) { store.stopAll("disabled"); return TelemetryRun.DEFERRED }
        request?.let { store.acknowledge(it.getString("id"), body.optString("request_state")) }
        val before = store.requests().map { it.getString("id") }.toSet()
        val offers = body.optJSONArray("requests") ?: JSONArray()
        val initialDiagnosis = if ((0 until offers.length()).any { offers.getJSONObject(it).optString("id") !in before }) diagnose() else JSONArray()
        store.acceptOffers(offers, time, device(), initialDiagnosis, body.optLong("server_wall_ms", -1).takeIf { it >= 0 }?.minus(time.wallMs()), body.optJSONArray("request_states") ?: JSONArray())
        store.requests().filter { it.getString("id") !in before && it.optString("kind") == "diagnostic_session" }.forEach {
            scheduler.scheduleSessionEnd(it.getLong("deadline") - time.elapsedMs())
        }
        return TelemetryRun.OK
    }

    private fun post(payload: JSONObject, control: Boolean): JSONObject? {
        val raw = payload.toString().toByteArray(Charsets.UTF_8)
        val day = TelemetryEngine.dayUtc(time.wallMs())
        if (raw.size > if (control) 8192 else 3 * 1024 * 1024) return JSONObject().put("status", "invalid")
        if (control && state.controlBytes(day) + raw.size > 65536) return JSONObject().put("status", "over_budget")
        for (attempt in 0..1) {
            if (!enabled() || state.killed || !state.diagnosticsEnabled) return JSONObject().put("status", "cancelled")
            val token = state.deviceToken ?: return null
            // Count attempts, including lost responses, to bound the client's control traffic.
            if (control) state.addControlBytes(day, raw.size)
            val report = payload.optJSONObject("report")
            val requestId = report?.optString("request_id")
            requestId?.let { store.recordAttempt(it, report, "attempting", time) }
            val response = transport.post(url, token, gzip(raw))
            if (enabled()) requestId?.let {
                val outcome = when (response) {
                    is CheckinResult.Failure -> "network_error"
                    is CheckinResult.Http -> when {
                        response.code in 200..299 -> "acknowledged_part"
                        response.code == 429 -> "budget_deferred"
                        response.code in 500..599 -> "http_5xx"
                        response.code == 401 -> "auth_retry"
                        else -> "rejected"
                    }
                }
                store.recordAttempt(it, report, outcome, time)
            }
            when (response) {
                is CheckinResult.Failure -> return null
                is CheckinResult.Http -> when {
                    !enabled() -> return null
                    response.code == 401 && attempt == 0 -> if (register() != TelemetryRun.OK) return null
                    response.code in 200..299 || response.code == 409 || response.code == 429 || response.code == 400 ->
                        return response.body ?: JSONObject().put("status", "invalid")
                    else -> return null
                }
            }
        }
        return null
    }

    private fun queueSession(original: JSONObject, records: List<JSONObject>) {
        var r = original
        val calls = r.getJSONObject("calls")
        val sameBuild = r.getJSONObject("identity").optString("sdk_build_id") == device().optString("sdk_build_id")
        // The retained log may now contain a different native build. Only previously
        // frozen payloads can safely carry the old request's immutable identity.
        for (callId in if (sameBuild) calls.keys().asSequence().toList() else emptyList()) {
            val call = calls.getJSONObject(callId)
            if (call.optBoolean("captured") || (!call.optBoolean("finished") && !r.has("end_reason"))) continue
            val matching = records.filter { it.optString("callId") == callId && it.optInt("boot") == r.optInt("boot") &&
                it.optLong("elapsed") >= r.optLong("start") && it.optLong("elapsed") <= r.optLong("deadline") }.toMutableList()
            val reportId = UUID.randomUUID().toString()
            val remaining = r.optLong("max_bytes") - r.optLong("reserved") - FINAL_RESERVE
            var truncated = false
            var payload = report(r, reportId, matching, final = false, truncated = false)
            if (bytes(payload) > remaining) {
                truncated = true
                var low = 0
                var high = matching.size
                payload = report(r, reportId, emptyList(), final = false, truncated = true, omitted = matching.size)
                // Binary search bounds compression passes even for a full 5-MiB slice.
                while (low <= high) {
                    val keep = (low + high) / 2
                    val rows = matching.take((keep + 1) / 2) + matching.takeLast(keep / 2)
                    val candidate = report(r, reportId, rows, final = false, truncated = true, omitted = matching.size - keep)
                    if (bytes(candidate) <= remaining) { payload = candidate; low = keep + 1 } else high = keep - 1
                }
            }
            if (bytes(payload) > remaining || !store.queue(r.getString("id"), listOf(payload), callId)) {
                store.finish(r.getString("id"), "byte_limit"); break
            }
            if (truncated) store.finish(r.getString("id"), "byte_limit")
            r = store.requests().first { it.getString("id") == r.getString("id") }
        }
        r = store.requests().first { it.getString("id") == r.getString("id") }
        if (r.has("end_reason") && !r.optBoolean("final_queued")) {
            val payload = report(r, UUID.randomUUID().toString(), emptyList(), final = true, truncated = r.optString("end_reason") in setOf("byte_limit", "build_changed"))
            if (!store.queue(r.getString("id"), listOf(payload), endReason = r.getString("end_reason"))) store.fail(r.getString("id"), "manifest_budget")
        }
    }

    private fun queuePull(r: JSONObject, records: List<JSONObject>) {
        val chunks = mutableListOf<List<JSONObject>>()
        var chunk = mutableListOf<JSONObject>(); var used = 0; var total = 0; var truncated = r.optString("end_reason") == "build_changed"
        for (record in records) {
            val size = record.toString().toByteArray(Charsets.UTF_8).size + 1
            if (size > PART_RAW || total + size > 5 * 1024 * 1024) { truncated = true; continue }
            if (used + size > PART_RAW) { chunks += chunk; chunk = mutableListOf(); used = 0 }
            chunk += record; used += size; total += size
        }
        if (chunk.isNotEmpty() || chunks.isEmpty()) chunks += chunk
        val id = UUID.randomUUID().toString()
        val payloads = chunks.mapIndexed { index, rows -> report(r, id, rows, final = true, truncated = truncated,
            part = index + 1, of = chunks.size) }
        if (!store.queue(r.getString("id"), payloads, endReason = "completed")) store.fail(r.getString("id"), "pull_budget")
    }

    private fun report(r: JSONObject, id: String, records: List<JSONObject>, final: Boolean, truncated: Boolean, part: Int = 1, of: Int = 1, omitted: Int = 0): JSONObject {
        val manifest = JSONArray(r.getJSONArray("reports").toString()).put(id)
        val meta = JSONObject().put("schema", 1).put("final", final).put("truncated", truncated)
            .put("unparsed_records", parseDrops)
            .put("boot", r.optInt("boot")).put("from_elapsed", r.optLong("start"))
            .put("to_elapsed", if (r.optInt("boot") == time.bootCount()) minOf(time.elapsedMs(), r.optLong("deadline")) else r.optLong("last_elapsed"))
            .put("from_wall", r.optLong("wall")).put("to_wall", time.wallMs())
            .put("observed_call_count", r.getJSONObject("calls").length()).put("dropped_records", log.droppedLogs())
            .put("clock_offset_ms", r.opt("clock_offset_ms") ?: JSONObject.NULL).put("omitted_records", omitted)
        if (final) meta.put("manifest", manifest).put("end_reason", r.optString("end_reason", "completed"))
        val rows = records.toMutableList()
        // A bounded initial native diagnosis is useful even when no call records exist.
        // It is metadata, not an invented call or proof of audible sound.
        if (r.getJSONArray("reports").length() == 0 && part == 1) meta.put("diagnose", r.optJSONArray("diagnose") ?: JSONArray())
        val raw = rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n").toByteArray(Charsets.UTF_8)
        return base("diagnostic_report").put("report", JSONObject().put("request_id", r.getString("id"))
            .put("report_id", id).put("part", part).put("of", of).put("identity", r.getJSONObject("identity"))
            .put("metadata", meta).put("records_gzip_b64", Base64.getEncoder().encodeToString(gzip(raw))))
    }

    companion object {
        const val FINAL_RESERVE = 8192L
        const val PART_RAW = 1024 * 1024
        private fun bytes(p: JSONObject) = p.toString().toByteArray(Charsets.UTF_8).size
        /** Sessions always ship redacted data; local verbose capture cannot override this. */
        fun redact(line: String): JSONObject? {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
            o.remove("stack") // exception messages may contain private source paths
            o.optJSONObject("data")?.let { d -> d.keys().asSequence().toList().forEach { key ->
                if (key in RingLog.PII_KEYS || key == RingLog.ALWAYS_REDACTED_KEY || key.lowercase().contains("uri") || key.lowercase().contains("path")) d.put(key, RingLog.REDACTED)
            } }
            return o
        }
    }
}
