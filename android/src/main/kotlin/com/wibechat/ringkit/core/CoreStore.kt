package com.wibechat.ringkit.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/**
 * File-backed persistence (PLAN §8.10). Every artifact lives in
 * `noBackupFilesDir/ringkit/` as schema-versioned JSON: SharedPreferences is
 * auto-backed-up and a library cannot set app-level dataExtractionRules — a
 * cloud restore would replay stale events and resurrect ghost calls (R31).
 *
 * "Exactly-once per install" is anchored by a random install ID created at
 * first native init; a restore lands in an empty noBackup dir ⇒ fresh ID ⇒
 * empty store by construction.
 *
 * Threading: confined to the single writer executor (§8.9) by contract. A
 * best-effort FileLock guards against unsupported multi-process setups.
 * Reads are lenient and NEVER throw (R23): unreadable files are quarantined
 * (renamed aside) and counted; unreadable entries are skipped and counted.
 */
class CoreStore internal constructor(private val baseDir: File) {

    companion object {
        const val DIR_NAME = "ringkit"

        fun open(context: Context): CoreStore = CoreStore(File(context.noBackupFilesDir, DIR_NAME))

        /** Test entry — any directory works; production uses [open]. */
        fun openAt(dir: File): CoreStore = CoreStore(dir)
    }

    private val installFile = File(baseDir, "install.json")
    private val configFile = File(baseDir, "config.json")
    private val callsFile = File(baseDir, "calls.json")
    private val tombstonesFile = File(baseDir, "tombstones.json")
    private val eventsFile = File(baseDir, "events.jsonl")
    private val seqFile = File(baseDir, "seq.json")
    private val registryFile = File(baseDir, "registry.json")
    private val launchActionFile = File(baseDir, "launch_action.json")
    private val countersFile = File(baseDir, "counters.json")
    private val lockFile = File(baseDir, "store.lock")

    /** Corrupt entries/files quarantined this process — surfaced via diagnose() (§11.3). */
    var quarantinedCount: Int = 0
        private set

    val installId: String by lazy {
        baseDir.mkdirs()
        val existing =
            readEnvelope(installFile)?.optString("installId", "")?.takeIf { !it.isNullOrBlank() }
        existing ?: UUID.randomUUID().toString().also { fresh ->
            writeEnvelope(installFile, org.json.JSONObject().put("installId", fresh))
        }
    }

    // ── config ───────────────────────────────────────────────────────────

    fun readConfig(): CoreConfig = CoreJson.config(readEnvelope(configFile))

    fun writeConfig(config: CoreConfig) {
        writeEnvelope(configFile, CoreJson.config(config))
    }

    // ── calls ────────────────────────────────────────────────────────────

    fun readCalls(): List<CallRecord> {
        val data = readEnvelope(callsFile) ?: return emptyList()
        val (list, skipped) = CoreJson.list(data.optJSONArray("calls")) { CoreJson.callRecord(it) }
        quarantinedCount += skipped
        return list
    }

    fun writeCalls(calls: List<CallRecord>) {
        writeEnvelope(
            callsFile,
            org.json.JSONObject().put("calls", CoreJson.array(calls) { CoreJson.callRecord(it) }),
        )
    }

    // ── tombstones ───────────────────────────────────────────────────────

    fun readTombstones(): Pair<List<Tombstone>, List<PreTombstone>> {
        val data = readEnvelope(tombstonesFile) ?: return emptyList<Tombstone>() to emptyList()
        val (tombs, s1) = CoreJson.list(data.optJSONArray("tombstones")) { CoreJson.tombstone(it) }
        val (pres, s2) = CoreJson.list(data.optJSONArray("preTombstones")) { CoreJson.preTombstone(it) }
        quarantinedCount += s1 + s2
        return tombs to pres
    }

    fun writeTombstones(tombstones: List<Tombstone>, preTombstones: List<PreTombstone>) {
        writeEnvelope(
            tombstonesFile,
            org.json.JSONObject()
                .put("tombstones", CoreJson.array(tombstones) { CoreJson.tombstone(it) })
                .put("preTombstones", CoreJson.array(preTombstones) { CoreJson.preTombstone(it) }),
        )
    }

    // ── events (JSON-lines; append-optimized, rewrite on mutation) ───────

    fun readEvents(): List<CoreEvent> {
        if (!eventsFile.exists()) return emptyList()
        val out = ArrayList<CoreEvent>()
        try {
            eventsFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parsed =
                    runCatching { CoreJson.event(org.json.JSONObject(line)) }.getOrNull()
                if (parsed != null) out.add(parsed) else quarantinedCount++
            }
        } catch (_: Exception) {
            quarantine(eventsFile)
        }
        return out
    }

    fun appendEvent(event: CoreEvent) {
        withLock {
            baseDir.mkdirs()
            FileOutputStream(eventsFile, true).use { fos ->
                fos.write((CoreJson.event(event).toString() + "\n").toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
        }
    }

    fun rewriteEvents(events: List<CoreEvent>) {
        withLock {
            baseDir.mkdirs()
            val tmp = File(baseDir, "events.jsonl.tmp")
            FileOutputStream(tmp).use { fos ->
                events.forEach { e ->
                    fos.write((CoreJson.event(e).toString() + "\n").toByteArray(Charsets.UTF_8))
                }
                fos.fd.sync()
            }
            if (!tmp.renameTo(eventsFile)) {
                // Fallback for exotic filesystems: non-atomic replace.
                eventsFile.delete()
                tmp.renameTo(eventsFile)
            }
        }
    }

    // ── seq (persisted separately so pruning never rewinds it, §10.1) ────

    fun readNextSeq(): Long = readEnvelope(seqFile)?.optLong("next", 1L) ?: 1L

    fun writeNextSeq(next: Long) {
        writeEnvelope(seqFile, org.json.JSONObject().put("next", next))
    }

    // ── notification-ID registry (§5.2) ──────────────────────────────────

    fun readRegistry(): Map<String, Int> = CoreJson.registry(readEnvelope(registryFile))

    fun writeRegistry(map: Map<String, Int>) {
        writeEnvelope(registryFile, CoreJson.registry(map))
    }

    // ── launch action (one-shot, §10.2/R44) ──────────────────────────────

    fun readLaunchAction(): LaunchAction? =
        readEnvelope(launchActionFile)?.let { CoreJson.launchAction(it) }

    fun writeLaunchAction(action: LaunchAction?) {
        if (action == null) {
            withLock { launchActionFile.delete() }
        } else {
            writeEnvelope(launchActionFile, CoreJson.launchAction(action))
        }
    }

    // ── counters (§8.10 bounds bookkeeping) ──────────────────────────────

    fun readDroppedEvents(): Int = readEnvelope(countersFile)?.optInt("droppedEvents", 0) ?: 0

    fun writeDroppedEvents(count: Int) {
        writeEnvelope(countersFile, org.json.JSONObject().put("droppedEvents", count))
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private fun readEnvelope(file: File): org.json.JSONObject? {
        if (!file.exists()) return null
        return try {
            val raw = file.readText(Charsets.UTF_8)
            val data = CoreJson.openEnvelope(raw)
            if (data == null) {
                quarantine(file)
            }
            data
        } catch (_: Exception) {
            quarantine(file)
            null
        }
    }

    private fun writeEnvelope(file: File, data: org.json.JSONObject) {
        withLock {
            baseDir.mkdirs()
            val tmp = File(baseDir, file.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(CoreJson.envelope(data).toString().toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    /** Corrupt file → renamed aside (never deleted — diagnosable), counted (R23). */
    private fun quarantine(file: File) {
        quarantinedCount++
        runCatching { file.renameTo(File(baseDir, file.name + ".quarantined")) }
    }

    /** Best-effort cross-process lock; multi-process manifests are unsupported (§8.9). */
    private inline fun <T> withLock(block: () -> T): T {
        return try {
            baseDir.mkdirs()
            RandomAccessFile(lockFile, "rw").use { raf ->
                val lock = runCatching { raf.channel.lock() }.getOrNull()
                try {
                    block()
                } finally {
                    runCatching { lock?.release() }
                }
            }
        } catch (_: Exception) {
            // Lock plumbing must never break persistence itself.
            block()
        }
    }
}
