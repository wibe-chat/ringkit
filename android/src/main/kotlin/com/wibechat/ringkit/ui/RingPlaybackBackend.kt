package com.wibechat.ringkit.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.wibechat.ringkit.R

internal sealed interface RingAudioState {
    data class Playing(val engine: String) : RingAudioState

    data class Failed(val reason: String) : RingAudioState

    data object Stopped : RingAudioState
}

internal fun interface RingPlaybackCancellation {
    fun cancel()
}

internal interface RingPlaybackScheduler {
    fun schedule(delayMs: Long, block: () -> Unit): RingPlaybackCancellation
}

internal object ImmediateRingPlaybackScheduler : RingPlaybackScheduler {
    override fun schedule(delayMs: Long, block: () -> Unit): RingPlaybackCancellation {
        block()
        return RingPlaybackCancellation { }
    }
}

internal class HandlerRingPlaybackScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : RingPlaybackScheduler {
    override fun schedule(delayMs: Long, block: () -> Unit): RingPlaybackCancellation {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return RingPlaybackCancellation { handler.removeCallbacks(runnable) }
    }
}

internal interface RingPlaybackEngine {
    val name: String

    fun start()

    fun isPlaying(): Boolean

    fun stop()
}

internal interface RingPlaybackBackend {
    fun openPrimary(
        uri: Uri,
        attributes: AudioAttributes,
        onError: (what: Int, extra: Int) -> Unit,
    ): RingPlaybackEngine

    fun openRingtone(uri: Uri, attributes: AudioAttributes): RingPlaybackEngine?

    fun openBundled(
        attributes: AudioAttributes,
        onError: (what: Int, extra: Int) -> Unit,
    ): RingPlaybackEngine
}

internal class AndroidRingPlaybackBackend(
    private val context: Context,
) : RingPlaybackBackend {

    override fun openPrimary(
        uri: Uri,
        attributes: AudioAttributes,
        onError: (what: Int, extra: Int) -> Unit,
    ): RingPlaybackEngine {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(context, uri)
            player.isLooping = true
            player.setOnErrorListener { _, what, extra ->
                onError(what, extra)
                true
            }
            player.prepare()
            return MediaPlayerEngine("MediaPlayer", player)
        } catch (error: Throwable) {
            runCatching { player.release() }
            throw error
        }
    }

    override fun openRingtone(uri: Uri, attributes: AudioAttributes): RingPlaybackEngine? {
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return null
        ringtone.audioAttributes = attributes
        ringtone.isLooping = true
        return object : RingPlaybackEngine {
            override val name: String = "Ringtone"

            override fun start() = ringtone.play()

            override fun isPlaying(): Boolean = ringtone.isPlaying

            override fun stop() = ringtone.stop()
        }
    }

    override fun openBundled(
        attributes: AudioAttributes,
        onError: (what: Int, extra: Int) -> Unit,
    ): RingPlaybackEngine {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            context.resources.openRawResourceFd(R.raw.ringkit_default_ringtone).use { descriptor ->
                player.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            player.isLooping = true
            player.setOnErrorListener { _, what, extra ->
                onError(what, extra)
                true
            }
            player.prepare()
            return MediaPlayerEngine("BundledDefault", player)
        } catch (error: Throwable) {
            runCatching { player.release() }
            throw error
        }
    }

    private class MediaPlayerEngine(
        override val name: String,
        private val player: MediaPlayer,
    ) : RingPlaybackEngine {
        override fun start() = player.start()

        override fun isPlaying(): Boolean = player.isPlaying

        override fun stop() {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
    }
}
