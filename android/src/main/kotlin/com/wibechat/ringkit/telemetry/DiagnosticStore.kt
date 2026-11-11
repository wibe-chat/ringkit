package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.TimeSource
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Install-fenced command and immutable report journal. Never used on the call writer/main thread. */
internal class DiagnosticStore(private val file: File, private val installId: String) {
    private var data = runCatching { JSONObject(file.readText()).takeIf { it.optString("install") == installId } }
        .getOrNull() ?: JSONObject().put("install", installId).put("requests", JSONObject())

    @Synchronized fun requests(): List<JSONObject> = rows(data).map { JSONObject(it.toString()) }
    @Synchronized fun active(time: TimeSource): Boolean = rows(data).any {
        it.optString("kind") == "diagnostic_session" && it.optBoolean("acked") && !it.has("end_reason") &&
            it.optInt("boot") == time.bootCount() && time.elapsedMs() < it.optLong("deadline")
    }

    /** Strict atomic persistence: a failed write must never be acknowledged to the server. */
    @Synchronized private fun mutate(block: (JSONObject) -> Unit) {
        val next = JSONObject(data.toString())
        block(next)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out -> out.write(next.toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
        check(tmp.renameTo(file)) { "diagnostic journal rename failed" }
        data = next
    }

    fun acceptOffers(offers: JSONArray, time: TimeSource, identity: JSONObject, initialDiagnosis: JSONArray = JSONArray(), clockOffset: Long? = null, terminalStates: JSONArray = JSONArray()) = mutate { d ->
        val requests = d.getJSONObject("requests")
        val offeredIds = (0 until offers.length()).map { offers.getJSONObject(it).getString("id") }.toSet()
        rows(d).filter { it.optString("id") !in offeredIds && !it.optBoolean("complete") }.forEach {
            val id = it.getString("id")
            val terminal = (0 until terminalStates.length()).map { n -> terminalStates.getJSONObject(n) }
                .firstOrNull { row -> row.optString("id") == id }?.optString("state")
            it.put("end_reason", terminal?.takeIf { value -> value in setOf("received", "received_empty", "cancelled", "expired", "failed") } ?: "no_longer_available")
                .put("complete", true).put("pending", JSONArray())
        }
        for (i in 0 until offers.length()) {
            val offer = offers.getJSONObject(i)
            val id = offer.optString("id")
            if (!isUuid(id) || requests.has(id) || offer.optString("kind") !in setOf("diagnostic_session", "full_buffer")) continue
            // A lost journal cannot restart an already acknowledged capture window.
            val lost = offer.optString("state") != "offered"
            val duration = offer.optLong("duration_ms", 1_800_000).coerceIn(1_000, 1_800_000)
            requests.put(id, JSONObject().put("id", id).put("kind", offer.getString("kind"))
                .put("boot", time.bootCount()).put("start", time.elapsedMs()).put("wall", time.wallMs())
                .put("deadline", time.elapsedMs() + duration).put("identity", identity)
                .put("diagnose", initialDiagnosis)
                .put("clock_offset_ms", clockOffset ?: JSONObject.NULL)
                .put("last_elapsed", time.elapsedMs())
                .put("max_calls", offer.optInt("max_calls", 20).coerceIn(1, 20))
                .put("max_bytes", offer.optInt("max_bytes", 524288).coerceIn(1024, if (offer.getString("kind") == "full_buffer") 8388608 else 524288))
                .put("acked", false).put("lost", lost).put("calls", JSONObject()).put("reports", JSONArray())
                .put("pending", JSONArray()).put("reserved", 0).put("complete", false))
        }
        // Keep only the two live kinds plus a bounded recent status history.
        rows(d).filter { it.optBoolean("complete") }.dropLast(4).forEach { requests.remove(it.getString("id")) }
    }

    fun acknowledge(id: String, status: String?) = mutate { d ->
        val r = d.getJSONObject("requests").optJSONObject(id) ?: return@mutate
        when (status) {
            "acknowledged", "collecting" -> r.put("acked", true)
            "cancelled", "expired", "failed", "received", "received_empty" -> r.put("complete", true).put("end_reason", status).put("pending", JSONArray())
        }
    }

    fun noteCall(id: String, ringing: Boolean, time: TimeSource, build: String? = null): Boolean {
        var changed = false
        mutate { d ->
        rows(d).filter { it.optString("kind") == "diagnostic_session" && it.optBoolean("acked") && !it.optBoolean("complete") }.forEach { r ->
            if (r.optInt("boot") != time.bootCount() || time.elapsedMs() >= r.optLong("deadline") || r.has("end_reason")) return@forEach
            if (build != null && r.getJSONObject("identity").optString("sdk_build_id") != build) {
                r.put("end_reason", "build_changed")
                return@forEach
            }
            val calls = r.getJSONObject("calls")
            r.put("last_elapsed", time.elapsedMs())
            if (ringing && !calls.has(id) && calls.length() < r.optInt("max_calls")) {
                calls.put(id, JSONObject().put("start", time.elapsedMs()).put("finished", false).put("captured", false))
                changed = true
            } else if (!ringing && calls.has(id) && !calls.getJSONObject(id).optBoolean("finished")) {
                calls.getJSONObject(id).put("finished", true)
                changed = true
            }
        }
        }
        return changed
    }

    fun reconcile(time: TimeSource, build: String) = mutate { d ->
        rows(d).filter { !it.optBoolean("complete") && it.optBoolean("acked") }.forEach { r ->
            if (r.has("end_reason")) return@forEach
            val calls = r.getJSONObject("calls")
            val reason = when {
                r.optInt("boot") != time.bootCount() -> "reboot"
                r.getJSONObject("identity").optString("sdk_build_id") != build -> "build_changed"
                time.elapsedMs() >= r.optLong("deadline") -> "time_limit"
                calls.length() >= r.optInt("max_calls") && calls.keys().asSequence().all { calls.getJSONObject(it).optBoolean("finished") } -> "call_limit"
                else -> null
            }
            reason?.let { r.put("end_reason", it) }
        }
    }

    fun queue(id: String, payloads: List<JSONObject>, callId: String? = null, endReason: String? = null): Boolean {
        var stored = false
        mutate { d ->
            val r = d.getJSONObject("requests").optJSONObject(id) ?: return@mutate
            if (r.optBoolean("complete")) return@mutate
            val bytes = payloads.sumOf { it.toString().toByteArray(Charsets.UTF_8).size }
            if (r.optLong("reserved") + bytes > r.optLong("max_bytes")) return@mutate
            val pending = r.getJSONArray("pending")
            val reports = r.getJSONArray("reports")
            payloads.forEach { p ->
                pending.put(p)
                val report = p.getJSONObject("report").getString("report_id")
                if (!(0 until reports.length()).any { reports.getString(it) == report }) reports.put(report)
            }
            r.put("reserved", r.optLong("reserved") + bytes)
            callId?.let { r.getJSONObject("calls").optJSONObject(it)?.put("captured", true) }
            endReason?.let { r.put("end_reason", it).put("final_queued", true) }
            stored = true
        }
        return stored
    }

    fun finish(id: String, reason: String) = mutate { d ->
        d.getJSONObject("requests").optJSONObject(id)?.let { if (!it.has("end_reason")) it.put("end_reason", reason) }
    }

    fun sent(id: String, report: String, part: Int, serverState: String) = mutate { d ->
        val r = d.getJSONObject("requests").optJSONObject(id) ?: return@mutate
        val old = r.getJSONArray("pending")
        val keep = JSONArray()
        for (i in 0 until old.length()) {
            val p = old.getJSONObject(i)
            val meta = p.getJSONObject("report")
            if (meta.optString("report_id") != report || meta.optInt("part") != part) keep.put(p)
        }
        r.put("pending", keep).put("server_state", serverState)
        if (serverState == "received" || serverState == "received_empty") r.put("complete", true)
    }

    fun recordAttempt(id: String, report: JSONObject?, outcome: String, time: TimeSource) = mutate { d ->
        d.getJSONObject("requests").optJSONObject(id)?.let { r ->
            r.put("last_attempt", JSONObject().put("id", id).put("outcome", outcome)
                .put("wall_ms", time.wallMs()).put("boot", time.bootCount()).put("elapsed_ms", time.elapsedMs())
                .put("report_id", report?.optString("report_id") ?: JSONObject.NULL)
                .put("part", report?.optInt("part") ?: 0).put("pending_parts", r.getJSONArray("pending").length()))
        }
    }

    fun statuses(): JSONArray = JSONArray(requests().mapNotNull { it.optJSONObject("last_attempt") }.takeLast(6))

    fun fail(id: String, reason: String) = mutate { d ->
        d.getJSONObject("requests").optJSONObject(id)?.put("last_error", reason)?.put("failed", true)
    }

    fun stopAll(reason: String) = mutate { d -> rows(d).forEach {
        if (!it.optBoolean("complete")) it.put("complete", true).put("end_reason", reason).put("pending", JSONArray())
    } }

    companion object {
        private fun rows(d: JSONObject): List<JSONObject> {
            val r = d.getJSONObject("requests")
            return r.keys().asSequence().map { r.getJSONObject(it) }.toList()
        }
        fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
    }
}
