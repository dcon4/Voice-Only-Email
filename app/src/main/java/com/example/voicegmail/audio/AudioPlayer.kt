package com.example.voicegmail.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioPlayer"

@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaPlayer: MediaPlayer? = null
    private var queue: List<AudioTrack> = emptyList()
    private var queueIndex: Int = -1
    private var onTrackComplete: ((AudioTrack) -> Unit)? = null
    private var onTrackError: ((AudioTrack, String) -> Unit)? = null

    val isPlaying: Boolean get() = mediaPlayer?.isPlaying ?: false
    val currentTrack: AudioTrack? get() = queue.getOrNull(queueIndex)
    val hasNext: Boolean get() = queueIndex + 1 < queue.size
    val hasPrevious: Boolean get() = queueIndex > 0
    val currentPosition: Long get() = mediaPlayer?.currentPosition?.toLong() ?: 0L
    val duration: Long get() = mediaPlayer?.duration?.toLong() ?: 0L

    // ── Queue management ──────────────────────────────────────────────

    fun playQueue(tracks: List<AudioTrack>, startIndex: Int = 0) {
        stop()
        queue = tracks.toList()
        queueIndex = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        playCurrent()
    }

    fun play(track: AudioTrack) {
        stop()
        queue = listOf(track)
        queueIndex = 0
        playCurrent()
    }

    fun next(): AudioTrack? {
        if (!hasNext) return null
        queueIndex++
        playCurrent()
        return currentTrack
    }

    fun previous(): AudioTrack? {
        if (!hasPrevious) return null
        queueIndex--
        playCurrent()
        return currentTrack
    }

    fun pause() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                DebugLogger.log(TAG, "pause")
            }
        }
    }

    fun resume() {
        mediaPlayer?.let { mp ->
            if (!mp.isPlaying) {
                mp.start()
                DebugLogger.log(TAG, "resume")
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.let { mp ->
            try {
                mp.seekTo(positionMs.toInt())
            } catch (e: Exception) {
                DebugLogger.log(TAG, "seekTo: ${e.message}")
            }
        }
    }

    fun stop() {
        mediaPlayer?.apply {
            setOnCompletionListener(null)
            setOnErrorListener(null)
            try {
                if (isPlaying) stop()
                reset()
                release()
            } catch (e: Exception) {
                DebugLogger.log(TAG, "stop: ${e.message}")
            }
        }
        mediaPlayer = null
        DebugLogger.log(TAG, "stop")
    }

    fun setOnTrackComplete(listener: ((AudioTrack) -> Unit)?) {
        onTrackComplete = listener
    }

    fun setOnTrackError(listener: ((AudioTrack, String) -> Unit)?) {
        onTrackError = listener
    }

    // ── Internal ──────────────────────────────────────────────────────

    private fun playCurrent() {
        val track = currentTrack ?: return

        try {
            mediaPlayer?.apply {
                setOnCompletionListener(null)
                setOnErrorListener(null)
                try { stop() } catch (_: Exception) {}
                reset()
                release()
            }
        } catch (_: Exception) {}

        mediaPlayer = null

        val uri = try {
            Uri.parse(track.uri)
        } catch (e: Exception) {
            DebugLogger.log(TAG, "playCurrent: invalid URI for ${track.title}")
            onTrackError?.invoke(track, "Invalid file URI")
            return
        }

        val mp = MediaPlayer()
        try {
            mp.setDataSource(context, uri)
            mp.setOnPreparedListener { player ->
                player.start()
                DebugLogger.log(TAG, "playCurrent: started ${track.title}")
            }
            mp.setOnCompletionListener {
                DebugLogger.log(TAG, "playCurrent: completed ${track.title}")
                onTrackComplete?.invoke(track)
            }
            mp.setOnErrorListener { _, what, extra ->
                DebugLogger.log(TAG, "playCurrent: error what=$what extra=$extra on ${track.title}")
                onTrackError?.invoke(track, "Playback error ($what/$extra)")
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            DebugLogger.log(TAG, "playCurrent: exception ${e.message} on ${track.title}")
            try { mp.release() } catch (_: Exception) {}
            onTrackError?.invoke(track, e.message ?: "Unknown error")
        }
    }
}
