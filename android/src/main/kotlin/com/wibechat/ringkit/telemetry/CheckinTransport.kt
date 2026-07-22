package com.wibechat.ringkit.telemetry

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

/**
 * §11.5 transport: plain `HttpURLConnection` POST with a gzipped JSON body —
 * NEVER FCM or any high-priority mechanism (R28), and never on the show path
 * (callers are WorkManager worker threads only). The bearer (apiKey or device
 * token) goes into the Authorization header and is never logged (§11.4).
 */
internal interface CheckinTransport {
    fun post(url: String, bearer: String, gzippedJson: ByteArray): CheckinResult
}

internal sealed interface CheckinResult {
    /** Any HTTP status; [body] is the parsed JSON response when one came back. */
    data class Http(val code: Int, val body: JSONObject?) : CheckinResult

    /** Transport-level failure (DNS/timeout/socket) — retry with backoff. */
    data class Failure(val cause: Exception) : CheckinResult
}

internal fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

internal class HttpCheckinTransport : CheckinTransport {

    override fun post(url: String, bearer: String, gzippedJson: ByteArray): CheckinResult {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.setRequestProperty("Authorization", "Bearer $bearer")
                conn.setFixedLengthStreamingMode(gzippedJson.size)
                conn.outputStream.use { it.write(gzippedJson) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = runCatching { stream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                val body = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                CheckinResult.Http(code, body)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            CheckinResult.Failure(e)
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
