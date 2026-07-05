package com.wibechat.ringkit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import com.wibechat.ringkit.core.CoreLogLevel
import com.wibechat.ringkit.core.RingLog

/**
 * Per-call avatar/image loader (PLAN §8.2): the notification posts immediately;
 * this fetches (`https://` or `flutterAsset:`, per-call [headers]), caps at
 * 512 KB / 3 s, LRU-caches on disk, and hands the bitmap to a PER-CALL
 * CANCELLABLE target — terminal state cancels the fetch so a slow avatar can
 * never resurrect a dead call's notification or land on a reused target (#533).
 *
 * flutterAsset resolution is Flutter-free by construction: app-bundle assets
 * live at the stable AssetManager path `flutter_assets/<path>` (the adapter
 * documents the app-asset restriction; plugin-package assets are out of scope).
 */
class AvatarLoader(
    private val context: Context,
    baseDir: File,
    private val ringLog: RingLog,
    /** Seam for tests: opens an https stream honoring headers + timeouts. */
    private val httpOpener: (String, Map<String, String>) -> InputStream = ::openHttps,
) {
    companion object {
        const val MAX_BYTES = 512 * 1024
        const val TIMEOUT_MS = 3_000
        const val MAX_CACHE_FILES = 24

        private fun openHttps(url: String, headers: Map<String, String>): InputStream {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            return connection.inputStream
        }
    }

    private val cacheDir = File(baseDir, "avatars")
    private val executor =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "ringkit-avatar").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }
    private val inFlight = ConcurrentHashMap<String, Future<*>>()

    /**
     * Fetch [url] for [callId] and deliver via [onLoaded] unless cancelled.
     * A new load for the same call replaces (cancels) the previous one.
     */
    fun load(callId: String, url: String, headers: Map<String, String>, onLoaded: (Bitmap) -> Unit) {
        cancel(callId)
        val future =
            executor.submit {
                val bitmap = fetch(callId, url, headers)
                // Cancellation between fetch and delivery: Future.cancel sets
                // the interrupt flag — deliver only if still current + live.
                if (bitmap != null && !Thread.currentThread().isInterrupted) {
                    inFlight.remove(callId)
                    onLoaded(bitmap)
                } else {
                    inFlight.remove(callId)
                }
            }
        inFlight[callId] = future
    }

    /** Terminal transitions cancel the fetch (§8.2 — no resurrection). */
    fun cancel(callId: String) {
        inFlight.remove(callId)?.cancel(true)
    }

    private fun fetch(callId: String, url: String, headers: Map<String, String>): Bitmap? {
        return try {
            val bytes =
                when {
                    url.startsWith("flutterAsset:") -> readCapped(
                        context.assets.open("flutter_assets/" + url.removePrefix("flutterAsset:")),
                    )
                    url.startsWith("https://") -> cachedOrFetch(url, headers)
                    else -> {
                        // Repository scheme validation should have filtered this.
                        ringLog.log(
                            CoreLogLevel.ERROR, "avatar", "AvatarLoader.fetch", callId,
                            "unsupported scheme", mapOf("avatarUrl" to url),
                        )
                        return null
                    }
                } ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: InterruptedException) {
            null
        } catch (e: Exception) {
            ringLog.log(
                CoreLogLevel.INFO, "avatar", "AvatarLoader.fetch", callId,
                "fetch failed — notification keeps placeholder", mapOf("avatarUrl" to url), e,
            )
            null
        }
    }

    private fun cachedOrFetch(url: String, headers: Map<String, String>): ByteArray? {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        val cached = File(cacheDir, "$key.img")
        if (cached.exists()) {
            cached.setLastModified(System.currentTimeMillis()) // LRU touch
            return cached.readBytes()
        }
        val bytes = readCapped(httpOpener(url, headers)) ?: return null
        runCatching {
            cacheDir.mkdirs()
            File(cacheDir, "$key.img.tmp").apply {
                writeBytes(bytes)
                renameTo(cached)
            }
            pruneLru()
        }
        return bytes
    }

    /** Reads at most MAX_BYTES; over-cap streams are rejected, not truncated. */
    private fun readCapped(stream: InputStream): ByteArray? =
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BYTES) return null
            }
            out.toByteArray()
        }

    private fun pruneLru() {
        val files = (cacheDir.listFiles() ?: return).filter { it.name.endsWith(".img") }
        if (files.size <= MAX_CACHE_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHE_FILES).forEach { it.delete() }
    }
}
