package com.wibechat.ringkit.telemetry

import android.os.Build
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreTelemetryConfig
import com.wibechat.ringkit.core.RingLog
import com.wibechat.ringkit.core.TimeSource
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Worker-facing outcome of one engine run. */
internal enum class TelemetryRun {
    /** Done (or nothing to do). */
    OK,

    /** Intentionally not sent (kill switch / budget) — files stay, no retry. */
    DEFERRED,

    /** Transient failure — WorkManager exponential backoff (never data loss). */
    RETRY,
}

/**
 * The §11.5 tiered fleet protocol, client side. One instance per run; all
 * state lives in [TelemetryState]. Runs ONLY on WorkManager worker threads —
 * never the writer, never the show path. Wire contract: docs/TELEMETRY.md.
 */
internal class TelemetryEngine(
    private val installId: String,
    private val time: TimeSource,
    private val ringLog: RingLog,
    /** The app config's logLevel — the sink level when no server override is set. */
    private val appLogLevel: () -> CoreLogLevel,
    private val appVersion: () -> String,
    /** The host app's applicationId (§11.5 multi-app segregation). */
    private val packageName: String,
    private val config: CoreTelemetryConfig,
    private val state: TelemetryState,
    private val transport: CheckinTransport,
    private val scheduler: TelemetryScheduler,
) {

    companion object {
        /** Raw JSONL bytes per pull part — gzips well under the 2 MB wire cap. */
        const val PULL_PART_RAW_BYTES = 3 * 1024 * 1024

        /** user_report ships the recent slice, tail-capped (pull covers the rest). */
        const val USER_REPORT_WINDOW_MS = 30L * 60L * 1000L
        const val USER_REPORT_MAX_RAW_BYTES = 512 * 1024
        const val USER_REPORT_FINGERPRINT = "user-report"

        fun dayUtc(wallMs: Long): String =
            Instant.ofEpochMilli(wallMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()

        /** Wire `log_level` (lowercase) ↔ CoreLogLevel. */
        fun wireLevel(raw: String): CoreLogLevel? =
            when (raw.lowercase()) {
                "none" -> CoreLogLevel.NONE
                "error" -> CoreLogLevel.ERROR
                "info" -> CoreLogLevel.INFO
                "verbose" -> CoreLogLevel.VERBOSE
                else -> null
            }

        /** ENUM_NAME → enumName (wire style used across the Dart surface). */
        fun wireName(enumName: String): String =
            enumName.lowercase().split('_').mapIndexed { i, part ->
                if (i == 0) part else part.replaceFirstChar { it.uppercase() }
            }.joinToString("")
    }

    private fun today(): String = dayUtc(time.wallMs())

    fun checkinUrl(): String {
        val e = config.endpoint.trimEnd('/')
        return if (e.endsWith("/checkin")) e else "$e/functions/v1/checkin"
    }

    // ── tier 1: incident push (expedited; deduped once/fingerprint/day) ──

    fun pendingIncidentFiles(): List<File> {
        val files = ringLog.incidentFiles()
        state.pruneShippedIncidents(files.map { it.name }.toSet())
        val today = today()
        val seen = HashSet<String>()
        // At most ONE upload per fingerprint per UTC day. `seen` also collapses
        // multiple snapshot files that share a fingerprint in this very batch —
        // a prior process can die before capture-side dedup meta persists (e.g.
        // the killed-state harness), leaving >1 file per fingerprint on disk;
        // without this collapse the pre-batch filter would pass all of them and
        // ship duplicates (device-found on SM-G781B, 2026-07-23). Newest first
        // so the freshest snapshot is the one kept; the rest age out via
        // RingLog's incident-file pruning.
        return files
            .sortedByDescending { it.name.substringAfterLast('-').removeSuffix(".jsonl").toLongOrNull() ?: 0L }
            .filter { f ->
                val fp = f.name.substringBeforeLast('-')
                fp.isNotBlank() &&
                    !state.isFileShipped(f.name) &&
                    state.fingerprintShippedOn(fp) != today &&
                    seen.add(fp)
            }
    }

    fun runIncidents(): TelemetryRun {
        for (file in pendingIncidentFiles()) {
            val fp = file.name.substringBeforeLast('-')
            val wall = file.name.substringAfterLast('-').removeSuffix(".jsonl").toLongOrNull()
                ?: time.wallMs()
            val meta = incidentMeta(file)
            val payload = baseRequest("incident").put(
                "incident",
                JSONObject()
                    .put("fingerprint", fp)
                    .put("incident_type", meta.optString("type", "UNKNOWN"))
                    .put("codepoint", meta.optString("cp", "unknown"))
                    .put("degradations", meta.optJSONArray("degradations") ?: JSONArray())
                    .put("call_id", meta.optString("callId", "").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    .put("device_ts_ms", wall)
                    .put("records_gzip_b64", gzipB64(redactedFileBytes(file))),
            )
            when (send("incident", payload, gated = true)) {
                is SendResult.Ok -> state.markIncidentShipped(fp, file.name, today())
                SendResult.Deferred -> return TelemetryRun.DEFERRED
                SendResult.Retry -> return TelemetryRun.RETRY
                SendResult.Drop -> {
                    // 400 = poison payload; mark shipped so it never loops (the
                    // occurrence counters still reach the server via heartbeat).
                    state.markIncidentShipped(fp, file.name, today())
                    ringLog.log(
                        CoreLogLevel.ERROR, "telemetry", "TelemetryEngine.runIncidents", null,
                        "incident upload rejected 400 — marked poison", mapOf("fingerprint" to fp),
                    )
                }
            }
        }
        return TelemetryRun.OK
    }

    /** The incident record is the LAST line of the snapshot file by construction. */
    private fun incidentMeta(file: File): JSONObject {
        var found = JSONObject()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (o.optString("tag") == "incident") {
                    val meta = JSONObject()
                        .put("type", o.optString("event").substringAfter("incident:", "UNKNOWN"))
                        .put("cp", o.optString("cp"))
                        .put("callId", o.optString("callId"))
                    val degradationsCsv = o.optJSONObject("data")?.optString("degradations") ?: ""
                    val arr = JSONArray()
                    degradationsCsv.split(',').filter { it.isNotBlank() }.forEach { arr.put(wireName(it)) }
                    meta.put("degradations", arr)
                    found = meta
                }
            }
        }
        return found
    }

    // ── tier 2: daily heartbeat (counters only — ~300 B) ─────────────────

    fun runHeartbeat(): TelemetryRun {
        val today = today()
        val days = (state.counterDays() + today).distinct().sorted()
        val cumulative = ringLog.incidentCounters()
        for (day in days) {
            val newest = day == days.last()
            val counters = JSONObject()
            state.countersFor(day).forEach { (k, v) -> counters.put(k, v) }
            val fingerprints = JSONObject()
            if (newest) {
                val reported = state.reportedFingerprints()
                cumulative.forEach { (fp, count) ->
                    val delta = count - (reported[fp] ?: 0)
                    if (delta > 0) fingerprints.put(fp, delta)
                }
            }
            val dropped =
                if (newest) maxOf(ringLog.persistedDroppedLogs(), ringLog.droppedLogs()) else 0
            val payload = baseRequest("heartbeat")
                .put("device", deviceBlock())
                .put(
                    "heartbeat",
                    JSONObject()
                        .put("day", day)
                        .put("counters", counters)
                        .put("fingerprints", fingerprints)
                        .put("dropped_logs", dropped),
                )
            when (send("heartbeat", payload, gated = true)) {
                is SendResult.Ok -> {
                    if (day != today) state.removeCounterDay(day)
                    if (newest) state.setReportedFingerprints(cumulative)
                }
                SendResult.Deferred -> return TelemetryRun.DEFERRED
                SendResult.Retry -> return TelemetryRun.RETRY
                SendResult.Drop -> return TelemetryRun.OK // malformed beat: don't loop
            }
        }
        return TelemetryRun.OK
    }

    // ── tier 3: on-demand full-buffer pull (budget-exempt) ───────────────

    fun runPull(): TelemetryRun {
        if (!state.pullPending) return TelemetryRun.OK
        ringLog.awaitDrained(5_000L)
        val lines = ArrayList<String>()
        ringLog.bufferFilesOldestFirst().forEach { f ->
            runCatching { f.forEachLine { line -> redactLine(line)?.let { lines.add(it) } } }
        }
        val parts = packParts(lines, PULL_PART_RAW_BYTES).ifEmpty { listOf("") }
        parts.forEachIndexed { index, raw ->
            val payload = baseRequest("pull").put(
                "pull",
                JSONObject()
                    .put("records_gzip_b64", gzipB64(raw.toByteArray(Charsets.UTF_8)))
                    .put("part", index + 1)
                    .put("of", parts.size),
            )
            when (send("pull", payload, gated = false)) {
                is SendResult.Ok -> Unit
                SendResult.Deferred -> return TelemetryRun.DEFERRED
                SendResult.Retry -> return TelemetryRun.RETRY
                SendResult.Drop -> return TelemetryRun.OK
            }
        }
        // The final part's response knobs normally clear the flag; belt+braces
        // for a response that omitted them.
        state.clearPullPending()
        return TelemetryRun.OK
    }

    // ── "Report issue now" (push-side pull: recent slice + marker) ───────

    fun runUserReport(): TelemetryRun {
        ringLog.awaitDrained(5_000L)
        val now = time.wallMs()
        val since = now - USER_REPORT_WINDOW_MS
        val lines = ArrayList<String>()
        ringLog.bufferFilesOldestFirst().takeLast(2).forEach { f ->
            runCatching {
                f.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val wall = runCatching { JSONObject(line).optLong("wall", Long.MAX_VALUE) }.getOrNull()
                    if (wall != null && wall >= since) redactLine(line)?.let { lines.add(it) }
                }
            }
        }
        var raw = lines.joinToString("\n")
        while (raw.length > USER_REPORT_MAX_RAW_BYTES && lines.isNotEmpty()) {
            lines.removeAt(0) // tail-cap: keep the newest records
            raw = lines.joinToString("\n")
        }
        val payload = baseRequest("user_report").put(
            "incident",
            JSONObject()
                .put("fingerprint", USER_REPORT_FINGERPRINT)
                .put("incident_type", "USER_REPORT")
                .put("codepoint", "Telemetry.userReport")
                .put("degradations", JSONArray())
                .put("call_id", JSONObject.NULL)
                .put("device_ts_ms", now)
                .put("records_gzip_b64", gzipB64(raw.toByteArray(Charsets.UTF_8))),
        )
        return when (send("user_report", payload, gated = true)) {
            is SendResult.Ok -> TelemetryRun.OK
            SendResult.Deferred -> TelemetryRun.DEFERRED
            SendResult.Retry -> TelemetryRun.RETRY
            SendResult.Drop -> TelemetryRun.OK
        }
    }

    // ── kill-mode knob poll (tiny register — the un-kill channel) ────────

    fun runKnobPoll(): TelemetryRun {
        val today = today()
        if (state.lastKillPollDay == today) {
            if (state.killed) scheduler.scheduleKillPoll(delayed = true)
            return TelemetryRun.OK
        }
        val result = register()
        if (result is SendResult.Ok) {
            state.lastKillPollDay = today
            // Still killed ⇒ re-arm tomorrow's poll (the only un-kill channel).
            if (state.killed) scheduler.scheduleKillPoll(delayed = true)
            return TelemetryRun.OK
        }
        return if (result == SendResult.Retry) TelemetryRun.RETRY else TelemetryRun.OK
    }

    // ── wire plumbing ────────────────────────────────────────────────────

    private sealed interface SendResult {
        data class Ok(val body: JSONObject?) : SendResult

        object Deferred : SendResult

        object Retry : SendResult

        object Drop : SendResult
    }

    private fun baseRequest(kind: String): JSONObject =
        JSONObject()
            .put("v", 1)
            .put("kind", kind)
            .put("install_id", installId)
            .put("device_wall_ms", time.wallMs())
            .put("boot_count", time.bootCount())

    private fun deviceBlock(): JSONObject =
        JSONObject()
            .put("label", config.deviceLabel)
            .put("manufacturer", Build.MANUFACTURER ?: "unknown")
            .put("model", Build.MODEL ?: "unknown")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("app_version", appVersion())
            .put("plugin_version", Telemetry.PLUGIN_VERSION)
            // §11.5 multi-app: the applicationId. The server cross-checks it, but
            // the AUTHORITATIVE app identity is the fleet key the app registered
            // with (a client can't spoof its app by lying here).
            .put("package_name", packageName)

    private fun register(): SendResult {
        val payload = baseRequest("register").put("device", deviceBlock())
        return when (val r = transport.post(checkinUrl(), config.apiKey, gzip(payload.toString().toByteArray(Charsets.UTF_8)))) {
            is CheckinResult.Failure -> SendResult.Retry
            is CheckinResult.Http ->
                when {
                    r.code in 200..299 -> {
                        r.body?.let { applyKnobs(it) }
                        SendResult.Ok(r.body)
                    }
                    // Bad apiKey: back off — a misconfigured app must not hot-loop.
                    else -> SendResult.Retry
                }
        }
    }

    /**
     * One check-in. Gated kinds respect the kill switch and the client-side
     * daily budget (§11.5 guards); pulls and register are exempt. 401 →
     * re-register once with the apiKey, retry once, then back off.
     */
    private fun send(kind: String, payload: JSONObject, gated: Boolean): SendResult {
        if (state.killed) return SendResult.Deferred
        var token = state.deviceToken
        if (token == null) {
            val reg = register()
            if (reg !is SendResult.Ok) return SendResult.Retry
            token = state.deviceToken ?: return SendResult.Retry
        }
        val body = gzip(payload.toString().toByteArray(Charsets.UTF_8))
        if (gated) {
            val today = today()
            val budgetBytes = (state.knobBudgetKb ?: config.dailyUploadBudgetKb) * 1024
            if (state.bytesToday(today) + body.size > budgetBytes) return SendResult.Deferred
        }
        var retriedAuth = false
        while (true) {
            when (val r = transport.post(checkinUrl(), token!!, body)) {
                is CheckinResult.Failure -> return SendResult.Retry
                is CheckinResult.Http ->
                    when {
                        r.code in 200..299 -> {
                            if (gated) state.addBytes(today(), body.size)
                            r.body?.let { applyKnobs(it) }
                            return SendResult.Ok(r.body)
                        }
                        r.code == 401 && !retriedAuth -> {
                            retriedAuth = true
                            state.deviceToken = null
                            val reg = register()
                            if (reg !is SendResult.Ok) return SendResult.Retry
                            token = state.deviceToken ?: return SendResult.Retry
                        }
                        r.code == 401 -> return SendResult.Retry
                        r.code == 413 -> {
                            // Server-side budget breach: knobs still ride the body;
                            // stop until the day rolls over (files stay on device).
                            r.body?.let { applyKnobs(it) }
                            val budgetBytes = (state.knobBudgetKb ?: config.dailyUploadBudgetKb) * 1024
                            state.exhaustBudget(today(), budgetBytes)
                            return SendResult.Deferred
                        }
                        r.code == 400 -> return SendResult.Drop
                        else -> return SendResult.Retry // 429 / 5xx / anything odd
                    }
            }
        }
    }

    /**
     * §11.5 fleet steering: every response carries the knob set; applied in ONE
     * state write, then the side effects (sink level, kill/pull scheduling).
     */
    internal fun applyKnobs(body: JSONObject) {
        val wasKilled = state.killed
        val level = wireLevel(body.optString("log_level", ""))
        state.applyKnobs(
            logLevel = level,
            budgetKb = body.optInt("budget_kb", -1).takeIf { it > 0 },
            sampled = body.optBoolean("sampled", false),
            killed = body.optBoolean("kill", false),
            pullPending = body.optBoolean("pull_buffer", false),
            deviceToken = body.optString("device_token", "").takeIf { it.isNotBlank() },
        )
        // Capture level follows the server override, else the app config (§11.4).
        ringLog.level = level ?: appLogLevel()
        val nowKilled = state.killed
        when {
            !wasKilled && nowKilled -> {
                // Kill switch: stop ALL telemetry traffic; the sink keeps writing
                // locally. A delayed daily knob poll stays as the un-kill channel.
                scheduler.cancelAllWork()
                scheduler.scheduleKillPoll(delayed = true)
            }
            wasKilled && !nowKilled -> {
                scheduler.ensureHeartbeat()
                if (pendingIncidentFiles().isNotEmpty()) scheduler.scheduleIncidentUpload()
            }
        }
        if (!nowKilled && state.pullPending) scheduler.schedulePull()
    }

    // ── upload-time redaction (defense in depth — §11.4 applies to uploads) ──

    private fun uploadVerbose(): Boolean =
        (state.knobLogLevel ?: config.uploadLevel) == CoreLogLevel.VERBOSE

    private fun redactedFileBytes(file: File): ByteArray {
        val out = StringBuilder()
        runCatching {
            file.forEachLine { line -> redactLine(line)?.let { out.append(it).append('\n') } }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Torn/unparseable lines are dropped (they may hold PII fragments). */
    private fun redactLine(line: String): String? {
        if (line.isBlank()) return null
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val data = o.optJSONObject("data") ?: return o.toString()
        val verbose = uploadVerbose()
        data.keys().asSequence().toList().forEach { key ->
            if (key == RingLog.ALWAYS_REDACTED_KEY || (!verbose && key in RingLog.PII_KEYS)) {
                data.put(key, RingLog.REDACTED)
            }
        }
        return o.toString()
    }

    private fun gzipB64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(gzip(bytes))

    private fun packParts(lines: List<String>, maxRawBytes: Int): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        lines.forEach { line ->
            if (current.isNotEmpty() && current.length + line.length + 1 > maxRawBytes) {
                parts.add(current.toString())
                current.setLength(0)
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }
}
