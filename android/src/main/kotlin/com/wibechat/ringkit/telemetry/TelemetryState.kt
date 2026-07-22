package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.CoreLogLevel
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * Uploader bookkeeping (PLAN §11.5): device token, server-driven knobs, the
 * per-device daily budget ledger, shipped-incident dedup markers, heartbeat
 * counter day-buckets and last-reported fingerprint counts.
 *
 * One JSON file (`telemetry.json`) beside the CoreStore artifacts in
 * `noBackupFilesDir/ringkit/` — §8.10 rules apply: install-ID fenced (a file
 * from another install is discarded wholesale), atomic tmp+rename writes,
 * lenient never-throwing reads. All access is synchronized; callers are the
 * telemetry executor + WorkManager worker threads (never the show path).
 *
 * The apiKey/token never appear in logs; the token DOES persist here (it is a
 * per-install bearer the server can shed — §11.5 abuse guard), fenced so a
 * cloned image re-registers instead of colliding.
 */
internal class TelemetryState(private val file: File, private val installId: String) {

    companion object {
        const val FILE_NAME = "telemetry.json"
        const val SCHEMA_VERSION = 1

        /** Heartbeat counter day-buckets kept at most this many days. */
        const val MAX_COUNTER_DAYS = 7

        /** Bounds for the dedup / delta maps (defensive; real fleets stay tiny). */
        const val MAX_SHIPPED_INCIDENTS = 60
        const val MAX_REPORTED_FINGERPRINTS = 200

        fun open(dir: File, installId: String): TelemetryState =
            TelemetryState(File(dir, FILE_NAME), installId)
    }

    private val lock = Any()
    private var data: JSONObject = load()

    // ── identity / token ─────────────────────────────────────────────────

    var deviceToken: String?
        get() = synchronized(lock) { data.optString("deviceToken", "").takeIf { it.isNotBlank() } }
        set(value) = mutate { it.put("deviceToken", value ?: JSONObject.NULL) }

    // ── server knobs (§11.5 — applied atomically per response) ───────────

    /** Server log-level override; null = follow the app config. */
    val knobLogLevel: CoreLogLevel?
        get() = synchronized(lock) { knobs().optString("logLevel", "").toLevelOrNull() }

    val knobBudgetKb: Int?
        get() = synchronized(lock) { knobs().optInt("budgetKb", -1).takeIf { it > 0 } }

    val sampled: Boolean
        get() = synchronized(lock) { knobs().optBoolean("sampled", false) }

    val killed: Boolean
        get() = synchronized(lock) { knobs().optBoolean("killed", false) }

    val pullPending: Boolean
        get() = synchronized(lock) { data.optBoolean("pullPending", false) }

    /** One write for the whole knob set — the "applied atomically" contract. */
    fun applyKnobs(
        logLevel: CoreLogLevel?,
        budgetKb: Int?,
        sampled: Boolean,
        killed: Boolean,
        pullPending: Boolean,
        deviceToken: String?,
    ) = mutate { d ->
        val k = JSONObject()
        logLevel?.let { k.put("logLevel", it.name) }
        budgetKb?.let { k.put("budgetKb", it) }
        k.put("sampled", sampled)
        k.put("killed", killed)
        d.put("knobs", k)
        d.put("pullPending", pullPending)
        deviceToken?.let { d.put("deviceToken", it) }
    }

    fun clearPullPending() = mutate { it.put("pullPending", false) }

    // ── daily upload budget ledger (client-side guard; pulls exempt) ─────

    fun bytesToday(today: String): Int =
        synchronized(lock) {
            val b = data.optJSONObject("budget") ?: return 0
            if (b.optString("day") == today) b.optInt("bytesToday", 0) else 0
        }

    fun addBytes(today: String, bytes: Int) = mutate { d ->
        val b = d.optJSONObject("budget") ?: JSONObject()
        val current = if (b.optString("day") == today) b.optInt("bytesToday", 0) else 0
        d.put("budget", JSONObject().put("day", today).put("bytesToday", current + bytes))
    }

    /** 413 from the server ⇒ stop trying until the day rolls over. */
    fun exhaustBudget(today: String, budgetBytes: Int) = mutate { d ->
        d.put("budget", JSONObject().put("day", today).put("bytesToday", budgetBytes))
    }

    // ── incident shipping dedup (once per fingerprint per device per day) ─

    fun isFileShipped(fileName: String): Boolean =
        synchronized(lock) {
            val s = data.optJSONObject("shippedIncidents") ?: return false
            s.keys().asSequence().any { fp -> s.optJSONObject(fp)?.optString("file") == fileName }
        }

    fun fingerprintShippedOn(fingerprint: String): String? =
        synchronized(lock) {
            data.optJSONObject("shippedIncidents")?.optJSONObject(fingerprint)
                ?.optString("day", "")?.takeIf { it.isNotBlank() }
        }

    fun markIncidentShipped(fingerprint: String, fileName: String, day: String) = mutate { d ->
        val s = d.optJSONObject("shippedIncidents") ?: JSONObject()
        s.put(fingerprint, JSONObject().put("day", day).put("file", fileName))
        while (s.length() > MAX_SHIPPED_INCIDENTS) s.remove(s.keys().next())
        d.put("shippedIncidents", s)
    }

    /** Drop markers whose snapshot file RingLog has already pruned. */
    fun pruneShippedIncidents(existingFiles: Set<String>) = mutate { d ->
        val s = d.optJSONObject("shippedIncidents") ?: return@mutate
        val stale = s.keys().asSequence()
            .filter { fp -> s.optJSONObject(fp)?.optString("file") !in existingFiles }
            .toList()
        stale.forEach { s.remove(it) }
        d.put("shippedIncidents", s)
    }

    // ── heartbeat counters (day-bucketed) + fingerprint deltas ───────────

    fun incrementCounter(day: String, key: String, by: Int = 1) = mutate { d ->
        val all = d.optJSONObject("counters") ?: JSONObject()
        val bucket = all.optJSONObject(day) ?: JSONObject()
        bucket.put(key, bucket.optInt(key, 0) + by)
        all.put(day, bucket)
        pruneCounterDays(all)
        d.put("counters", all)
    }

    /** Days with a bucket, oldest first (ISO strings sort chronologically). */
    fun counterDays(): List<String> =
        synchronized(lock) {
            (data.optJSONObject("counters") ?: JSONObject()).keys().asSequence().toList().sorted()
        }

    fun countersFor(day: String): Map<String, Int> =
        synchronized(lock) {
            val bucket = data.optJSONObject("counters")?.optJSONObject(day) ?: return emptyMap()
            bucket.keys().asSequence().associateWith { bucket.optInt(it, 0) }
        }

    /** After a heartbeat lands: past-day buckets are gone; today's keeps accumulating. */
    fun removeCounterDay(day: String) = mutate { d ->
        val all = d.optJSONObject("counters") ?: return@mutate
        all.remove(day)
        d.put("counters", all)
    }

    fun reportedFingerprints(): Map<String, Int> =
        synchronized(lock) {
            val r = data.optJSONObject("reportedFingerprints") ?: return emptyMap()
            r.keys().asSequence().associateWith { r.optInt(it, 0) }
        }

    fun setReportedFingerprints(counts: Map<String, Int>) = mutate { d ->
        val r = JSONObject()
        counts.entries.take(MAX_REPORTED_FINGERPRINTS).forEach { (k, v) -> r.put(k, v) }
        d.put("reportedFingerprints", r)
    }

    // ── kill-mode knob-poll throttle ─────────────────────────────────────

    var lastKillPollDay: String?
        get() = synchronized(lock) { data.optString("lastKillPollDay", "").takeIf { it.isNotBlank() } }
        set(value) = mutate { it.put("lastKillPollDay", value ?: "") }

    // ── plumbing (§8.10 style: fenced, atomic, lenient) ──────────────────

    fun exists(): Boolean = file.exists()

    private fun load(): JSONObject {
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return JSONObject()
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return JSONObject()
        // Install fence: state from another install (cloned image / reused dir)
        // is discarded wholesale — the stale token would collide server-side.
        if (envelope.optString("installId") != installId) return JSONObject()
        return envelope.optJSONObject("data") ?: JSONObject()
    }

    private fun mutate(block: (JSONObject) -> Unit) {
        synchronized(lock) {
            block(data)
            persist()
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val envelope =
                JSONObject().put("v", SCHEMA_VERSION).put("installId", installId).put("data", data)
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(envelope.toString().toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun knobs(): JSONObject = data.optJSONObject("knobs") ?: JSONObject()

    private fun pruneCounterDays(all: JSONObject) {
        val days = all.keys().asSequence().toList().sorted()
        days.dropLast(MAX_COUNTER_DAYS).forEach { all.remove(it) }
    }

    private fun String.toLevelOrNull(): CoreLogLevel? =
        runCatching { if (isBlank()) null else CoreLogLevel.valueOf(this) }.getOrNull()
}
