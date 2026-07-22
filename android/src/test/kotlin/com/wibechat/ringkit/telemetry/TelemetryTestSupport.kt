package com.wibechat.ringkit.telemetry

import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.CoreTelemetryConfig
import com.wibechat.ringkit.core.FakeTime
import com.wibechat.ringkit.core.RingLog
import com.wibechat.ringkit.core.TestSupport
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject

internal fun gunzipToString(bytes: ByteArray): String =
    GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }

/** Captures every POST (url, bearer, decoded payload); scripted responses. */
internal class FakeTransport : CheckinTransport {

    data class Sent(val url: String, val bearer: String, val payload: JSONObject, val gzippedSize: Int)

    val sent = mutableListOf<Sent>()
    val queue = ArrayDeque<CheckinResult>()

    /** Contract-shaped 200 unless the queue scripts something else. */
    var defaultResponse: (JSONObject) -> CheckinResult = { payload -> ok(payload.optString("kind")) }

    override fun post(url: String, bearer: String, gzippedJson: ByteArray): CheckinResult {
        val payload = JSONObject(gunzipToString(gzippedJson))
        sent += Sent(url, bearer, payload, gzippedJson.size)
        return if (queue.isNotEmpty()) queue.removeFirst() else defaultResponse(payload)
    }

    fun kinds(): List<String> = sent.map { it.payload.optString("kind") }

    companion object {
        fun okBody(kind: String = "", token: String = "tok-1"): JSONObject =
            JSONObject().apply {
                if (kind == "register") put("device_token", token)
                put("log_level", "info")
                put("pull_buffer", false)
                put("sampled", false)
                put("budget_kb", 200)
                put("kill", false)
            }

        fun ok(kind: String = "", token: String = "tok-1"): CheckinResult.Http =
            CheckinResult.Http(200, okBody(kind, token))
    }
}

internal class RecordingScheduler : TelemetryScheduler {
    val calls = mutableListOf<String>()

    override fun ensureHeartbeat() {
        calls += "heartbeat"
    }

    override fun scheduleIncidentUpload() {
        calls += "incident"
    }

    override fun schedulePull() {
        calls += "pull"
    }

    override fun scheduleUserReport() {
        calls += "userReport"
    }

    override fun scheduleKillPoll(delayed: Boolean) {
        calls += if (delayed) "killPoll:delayed" else "killPoll:now"
    }

    override fun cancelAllWork() {
        calls += "cancelAll"
    }
}

/** Everything an engine needs, on temp dirs — no Android, no Robolectric. */
internal class EngineHarness(
    val time: FakeTime = FakeTime(),
    budgetKb: Int = 200,
    uploadLevel: CoreLogLevel = CoreLogLevel.INFO,
) {
    val dir: File = TestSupport.newTempDir()
    val logsDir = File(dir, "logs")
    val ringLog = RingLog(logsDir, time) { _, _ -> }
    val state = TelemetryState.open(dir, "install-1")
    val transport = FakeTransport()
    val scheduler = RecordingScheduler()
    var appLevel: CoreLogLevel = CoreLogLevel.ERROR
    val config =
        CoreTelemetryConfig(
            endpoint = "https://fleet.example",
            apiKey = "key-secret",
            deviceLabel = "bench-A",
            uploadLevel = uploadLevel,
            dailyUploadBudgetKb = budgetKb,
        )

    fun engine(config: CoreTelemetryConfig = this.config): TelemetryEngine =
        TelemetryEngine(
            installId = "install-1",
            time = time,
            ringLog = ringLog,
            appLogLevel = { appLevel },
            appVersion = { "9.9.9" },
            config = config,
            state = state,
            transport = transport,
            scheduler = scheduler,
        )

    /** Write an incident through the real sink and wait for the file. */
    fun writeIncident(
        type: RingLog.IncidentType = RingLog.IncidentType.FGS_DENIED,
        codepoint: String = "Orchestrator.executeShow",
        callId: String? = "c-1",
        degradations: Set<String> = setOf("FGS_DENIED"),
    ): String {
        ringLog.incident(type, codepoint, callId, degradations)
        check(ringLog.awaitDrained())
        return ringLog.fingerprint(type, codepoint, degradations)
    }
}

internal fun awaitTrue(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

/**
 * Minimal single-threaded HTTP/1.1 ingest mock on a raw ServerSocket —
 * android.jar's compile classpath has no com.sun.net.httpserver, and the
 * §13 dep policy rules out a mockwebserver artifact for one suite.
 */
internal class MockIngestServer {

    class Seen(val path: String, val bearer: String?, val encoding: String?, val payload: JSONObject)

    val seen = mutableListOf<Seen>()

    /** Scripted per-request status codes (drained first), else contract 200s. */
    val statusQueue = ArrayDeque<Int>()

    var issuedToken = "server-tok-1"

    private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val thread = Thread({ acceptLoop() }, "mock-ingest").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = socket.localPort

    fun stop() {
        runCatching { socket.close() }
        thread.join(2_000)
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            client.use { runCatching { handle(it) } }
        }
    }

    private fun handle(client: java.net.Socket) {
        val input = java.io.BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val path = requestLine.split(" ").getOrNull(1) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val encoding = headers["content-encoding"]
        val raw = if (encoding == "gzip") gunzipToString(body) else String(body, Charsets.UTF_8)
        val payload = JSONObject(raw)
        synchronized(seen) {
            seen += Seen(path, headers["authorization"]?.removePrefix("Bearer "), encoding, payload)
        }

        val status = synchronized(statusQueue) { statusQueue.removeFirstOrNull() } ?: 200
        val responseBody =
            JSONObject().apply {
                if (payload.optString("kind") == "register") put("device_token", issuedToken)
                put("log_level", "info")
                put("pull_buffer", false)
                put("sampled", false)
                put("budget_kb", 200)
                put("kill", false)
            }.toString().toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Error"
        val out = client.getOutputStream()
        out.write(
            ("HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${responseBody.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
        )
        out.write(responseBody)
        out.flush()
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
