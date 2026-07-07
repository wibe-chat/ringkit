package com.wibechat.ringkit.core

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * The always-on black-box capture sink (PLAN §11.5 capture half; §11.4).
 *
 * Every component in every process appends records
 * `{seq, wall, elapsed, boot, level, tag, cp, callId?, event, data, stack?}`
 * to size-capped rotating JSONL files under `noBackupFilesDir/ringkit/logs`.
 * Writes happen on a dedicated MIN_PRIORITY background thread — NEVER
 * synchronously on the show/ring path; when the queue is full the record is
 * dropped and counted (`droppedLogs`), the caller is never blocked.
 *
 * INCIDENTs (§11.5 severity taxonomy) snapshot the surrounding buffer slice
 * (past 5 min) into an incident file keyed by a fingerprint =
 * hash{type, codepoint, degradation set}; snapshots dedup to once per
 * fingerprint per day (occurrence counters always increment). Uploading is
 * SCOPE-2 — this class opens no sockets.
 *
 * PII rule (§11.4): caller names/handles/avatar URLs/headers in `data` are
 * redacted unless [level] is VERBOSE; the telemetry apiKey is redacted at
 * EVERY level. [level] also gates the logcat tee — the sink itself always
 * captures (a black box that only records when healthy is useless).
 */
class RingLog internal constructor(
    private val dir: File,
    private val time: TimeSource,
    /** Logcat tee seam — Robolectric-free tests inject a recorder. */
    private val logcat: (level: CoreLogLevel, message: String) -> Unit = AndroidLogcat,
) {
    companion object {
        const val TAG = "RingKit"
        const val MAX_FILE_BYTES = 512L * 1024L
        const val MAX_TOTAL_BYTES = 5L * 1024L * 1024L
        const val MAX_INCIDENT_FILES = 20
        const val SNAPSHOT_WINDOW_MS = 5L * 60L * 1000L
        const val SNAPSHOT_DEDUP_MS = 24L * 60L * 60L * 1000L
        const val QUEUE_CAPACITY = 1024
        const val REDACTED = "«redacted»"

        private val PII_KEYS = setOf("name", "handle", "avatarUrl", "avatarHeaders", "callerName", "calleeName")
        private const val ALWAYS_REDACTED_KEY = "apiKey"

        fun open(baseDir: File, time: TimeSource): RingLog = RingLog(File(baseDir, "logs"), time)

        /** android.util.Log tee (kept out of unit-test paths via injection). */
        private val AndroidLogcat: (CoreLogLevel, String) -> Unit = { level, message ->
            when (level) {
                CoreLogLevel.ERROR -> android.util.Log.e(TAG, message)
                CoreLogLevel.INFO -> android.util.Log.i(TAG, message)
                CoreLogLevel.VERBOSE -> android.util.Log.v(TAG, message)
                CoreLogLevel.NONE -> Unit
            }
        }
    }

    /** §11.5 severity taxonomy → INCIDENT. */
    enum class IncidentType {
        SHOW_FAILED,
        BOUNDARY_EXCEPTION,
        CALLSTYLE_REJECTED,
        FGS_DENIED,
        DELIVERY_FAILURE,
        LEASE_EXPIRED,
        SWEEP_GHOST,
        WATCHDOG_FIRED,
        PARSE_REJECTED,
        FCM_DOWNGRADED,
        TELECOM_FALLBACK,
    }

    /** Config logLevel (§11.4): gates the logcat tee + PII redaction, not capture. */
    @Volatile
    var level: CoreLogLevel = CoreLogLevel.ERROR

    private val incidentsDir = File(dir, "incidents")
    private val metaFile = File(dir, "meta.json")
    private val seq = AtomicLong(0)
    private val dropped = AtomicInteger(0)
    private val queue = ArrayBlockingQueue<Entry>(QUEUE_CAPACITY)
    private var currentFile = File(dir, "ringlog-current.jsonl")

    private sealed class Entry {
        data class Record(val json: JSONObject, val teeLevel: CoreLogLevel, val teeMessage: String) : Entry()

        data class Incident(val fingerprint: String, val record: JSONObject) : Entry()

        data class Flush(val latch: CountDownLatch) : Entry()
    }

    private val writer =
        Thread({ drainLoop() }, "ringkit-log").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }

    // ── public API ───────────────────────────────────────────────────────

    fun log(
        level: CoreLogLevel,
        tag: String,
        codepoint: String,
        callId: String?,
        event: String,
        data: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ) {
        require(level != CoreLogLevel.NONE) { "NONE is a config level, not a record level" }
        val record = record(level, tag, codepoint, callId, event, data, throwable)
        val teeMessage = "[$tag] $event ${callId?.let { "call=$it " } ?: ""}${redact(data)}"
        offer(Entry.Record(record, level, teeMessage))
    }

    /**
     * §11.5: record + fingerprint + counter, and (≤ once per fingerprint per
     * day) snapshot the past-5-min buffer slice into an incident file.
     */
    fun incident(
        type: IncidentType,
        codepoint: String,
        callId: String?,
        degradations: Set<String> = emptySet(),
        data: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ) {
        val fingerprint = fingerprint(type, codepoint, degradations)
        val record =
            record(
                CoreLogLevel.ERROR,
                "incident",
                codepoint,
                callId,
                "incident:${type.name}",
                data + mapOf("fingerprint" to fingerprint, "degradations" to degradations.sorted().joinToString(",")),
                throwable,
            )
        offer(Entry.Record(record, CoreLogLevel.ERROR, "[incident] ${type.name} fp=$fingerprint cp=$codepoint"))
        offer(Entry.Incident(fingerprint, record))
    }

    fun droppedLogs(): Int = dropped.get()

    /** Stable §11.5 fingerprint: hash{incident type, codepoint, degradation set}. */
    fun fingerprint(type: IncidentType, codepoint: String, degradations: Set<String>): String {
        val basis = "${type.name}|$codepoint|${degradations.sorted().joinToString(",")}"
        val digest = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Blocks until everything enqueued so far hit disk (tests + pull-on-demand). */
    fun awaitDrained(timeoutMs: Long = 5_000L): Boolean {
        val latch = CountDownLatch(1)
        if (!queue.offer(Entry.Flush(latch))) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    // ── record assembly (caller thread — cheap, no I/O) ──────────────────

    private fun record(
        level: CoreLogLevel,
        tag: String,
        codepoint: String,
        callId: String?,
        event: String,
        data: Map<String, String>,
        throwable: Throwable?,
    ): JSONObject {
        val json =
            JSONObject()
                .put("seq", seq.incrementAndGet())
                .put("wall", time.wallMs())
                .put("elapsed", time.elapsedMs())
                .put("boot", time.bootCount())
                .put("level", level.name)
                .put("tag", tag)
                .put("cp", codepoint)
                .put("event", event)
        if (callId != null) json.put("callId", callId)
        val redacted = redact(data)
        if (redacted.isNotEmpty()) {
            val obj = JSONObject()
            redacted.forEach { (k, v) -> obj.put(k, v) }
            json.put("data", obj)
        }
        if (throwable != null) json.put("stack", throwable.stackTraceToString().take(8_192))
        return json
    }

    private fun redact(data: Map<String, String>): Map<String, String> {
        if (data.isEmpty()) return data
        val verbose = level == CoreLogLevel.VERBOSE
        return data.mapValues { (key, value) ->
            when {
                key == ALWAYS_REDACTED_KEY -> REDACTED // §11.4: never logged at ANY level
                !verbose && key in PII_KEYS -> REDACTED
                else -> value
            }
        }
    }

    private fun offer(entry: Entry) {
        if (entry is Entry.Record) {
            // Tee to logcat from the caller thread (Log.* is lock-free enough and
            // keeps adb traces causally ordered with the code path that logged).
            if (entry.teeLevel.ordinal <= level.ordinal && level != CoreLogLevel.NONE) {
                logcat(entry.teeLevel, entry.teeMessage)
            }
        }
        if (!queue.offer(entry)) dropped.incrementAndGet()
    }

    // ── writer thread ────────────────────────────────────────────────────

    private fun drainLoop() {
        val batch = ArrayList<Entry>(64)
        while (true) {
            batch.clear()
            try {
                batch.add(queue.take())
            } catch (_: InterruptedException) {
                return
            }
            queue.drainTo(batch, 63)
            try {
                writeBatch(batch)
            } catch (_: Throwable) {
                // The sink must never take the host app down; records in this
                // batch are lost and counted.
                dropped.addAndGet(batch.count { it is Entry.Record })
            }
            batch.forEach { if (it is Entry.Flush) it.latch.countDown() }
        }
    }

    private fun writeBatch(batch: List<Entry>) {
        dir.mkdirs()
        val records = batch.filterIsInstance<Entry.Record>()
        if (records.isNotEmpty()) {
            FileOutputStream(currentFile, true).use { fos ->
                records.forEach { fos.write((it.json.toString() + "\n").toByteArray(Charsets.UTF_8)) }
                fos.fd.sync()
            }
            rotateIfNeeded()
            enforceTotalCap()
            persistMetaIfDirty()
        }
        batch.filterIsInstance<Entry.Incident>().forEach { snapshotIncident(it) }
    }

    private fun rotateIfNeeded() {
        if (currentFile.length() < MAX_FILE_BYTES) return
        // Monotonic counter suffix: two rotations inside the same wall-ms must
        // never rename onto the same target (silent whole-file loss).
        currentFile.renameTo(File(dir, "ringlog-${time.wallMs()}-${rotationCounter.incrementAndGet()}.jsonl"))
    }

    /** Total cap 5 MB incl. the current file — enforced after EVERY batch; oldest dropped + counted (§11.5). */
    private fun enforceTotalCap() {
        var rotated = rotatedFiles()
        var total = rotated.sumOf { it.length() } + currentFile.length()
        while (total > MAX_TOTAL_BYTES && rotated.isNotEmpty()) {
            val oldest = rotated.first()
            total -= oldest.length()
            oldest.delete()
            dropped.incrementAndGet()
            rotated = rotated.drop(1)
        }
    }

    private val rotationCounter = AtomicLong(0)

    /** Rotated files, oldest first: primary = embedded wall ms, tie-break = rotation counter. */
    private fun rotatedFiles(): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("ringlog-") && it.name != currentFile.name }
            .sortedWith(
                compareBy(
                    { it.name.removePrefix("ringlog-").substringBefore('-').toLongOrNull() ?: 0L },
                    { it.name.removePrefix("ringlog-").substringAfter('-').removeSuffix(".jsonl").toLongOrNull() ?: 0L },
                ),
            )

    // ── incidents ────────────────────────────────────────────────────────

    private fun snapshotIncident(incident: Entry.Incident) {
        incidentsDir.mkdirs()
        val meta = readMeta()
        val perFingerprint = meta.optJSONObject("incidents") ?: JSONObject()
        val entry = perFingerprint.optJSONObject(incident.fingerprint) ?: JSONObject()
        val nowWall = time.wallMs()
        entry.put("count", entry.optInt("count", 0) + 1)
        entry.put("lastWall", nowWall)

        // Once per fingerprint per day (§11.5 dedup — capture side).
        val lastSnapshot = entry.optLong("lastSnapshotWall", 0L)
        if (nowWall - lastSnapshot >= SNAPSHOT_DEDUP_MS) {
            entry.put("lastSnapshotWall", nowWall)
            val out = File(incidentsDir, "${incident.fingerprint}-$nowWall.jsonl")
            FileOutputStream(out).use { fos ->
                bufferSlice(nowWall - SNAPSHOT_WINDOW_MS).forEach { line ->
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
                fos.write((incident.record.toString() + "\n").toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            pruneIncidentFiles()
        }
        perFingerprint.put(incident.fingerprint, entry)
        meta.put("incidents", perFingerprint)
        writeMeta(meta)
    }

    /** Lines from the two newest buffer files with wall ≥ [sinceWallMs] (lenient: torn lines skipped). */
    private fun bufferSlice(sinceWallMs: Long): List<String> {
        val sources = (rotatedFiles().takeLast(1) + currentFile).filter { it.exists() }
        val slice = ArrayList<String>()
        sources.forEach { file ->
            runCatching {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val wall = runCatching { JSONObject(line).optLong("wall", Long.MAX_VALUE) }.getOrNull()
                    if (wall != null && wall >= sinceWallMs) slice.add(line)
                }
            }
        }
        return slice
    }

    private fun pruneIncidentFiles() {
        val files =
            (incidentsDir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".jsonl") }
                .sortedBy { it.name.substringAfterLast('-').removeSuffix(".jsonl").toLongOrNull() ?: 0L }
        files.dropLast(MAX_INCIDENT_FILES).forEach { it.delete() }
    }

    // ── meta (droppedLogs + incident counters; lenient like every §8.10 read) ──

    private var lastPersistedDropped = 0

    private fun persistMetaIfDirty() {
        val current = dropped.get()
        if (current == lastPersistedDropped) return
        val meta = readMeta().put("droppedLogs", current)
        writeMeta(meta)
        lastPersistedDropped = current
    }

    private fun readMeta(): JSONObject =
        runCatching { JSONObject(metaFile.readText(Charsets.UTF_8)) }.getOrNull() ?: JSONObject()

    private fun writeMeta(meta: JSONObject) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, metaFile.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(meta.toString().toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (!tmp.renameTo(metaFile)) {
                metaFile.delete()
                tmp.renameTo(metaFile)
            }
        }
    }
}
