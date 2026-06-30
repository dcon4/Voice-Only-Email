package com.example.voicegmail.audio

import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.media.MediaSessionController
import com.example.voicegmail.voice.VoiceCommand
import com.example.voicegmail.voice.VoiceCommandEngine
import com.example.voicegmail.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioPlayerVoiceFlow"

@Singleton
class AudioPlayerVoiceFlow @Inject constructor(
    private val audioRepository: AudioRepository,
    private val audioPlayer: AudioPlayer,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val voiceManager: VoiceManager,
    private val mediaSessionController: MediaSessionController
) {
    private var currentQueue: List<AudioTrack> = emptyList()
    private var currentIndex: Int = -1
    private var isPaused: Boolean = false
    private var flowGen: Int = 0

    val isActive: Boolean get() = currentQueue.isNotEmpty() && currentIndex >= 0
    val isPlayPaused: Boolean get() = isPaused
    val nowPlaying: AudioTrack? get() = currentQueue.getOrNull(currentIndex)

    // ── Entry point ───────────────────────────────────────────────────

    fun start(query: String, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        DebugLogger.log(TAG, "start: query='$query'")
        flowGen++
        stop()
        searchAndPlay(query, scope, onExit)
    }

    // ── Wake interrupt ────────────────────────────────────────────────

    fun handleWakeInterrupt(): Boolean {
        if (audioPlayer.isPlaying || isPaused) {
            DebugLogger.log(TAG, "handleWakeInterrupt: pausing")
            flowGen++
            audioPlayer.stop()
            isPaused = true
            mediaSessionController.setPaused()
            return true
        }
        return false
    }

    fun resumeAfterWake(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (!isPaused) {
            onExit(VoiceCommand.None)
            return
        }
        isPaused = false
        audioPlayer.playQueue(currentQueue, currentIndex)
        mediaSessionController.setPlaying(
            currentQueue[currentIndex].title,
            currentQueue[currentIndex].artist
        )
        onExit(VoiceCommand.GoToSleep)
    }

    fun resumeAndRestart(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (!isPaused) {
            onExit(VoiceCommand.None)
            return
        }
        isPaused = false
        audioPlayer.seekTo(0)
        audioPlayer.playQueue(currentQueue, currentIndex)
        mediaSessionController.setPlaying(
            currentQueue[currentIndex].title,
            currentQueue[currentIndex].artist
        )
        onExit(VoiceCommand.GoToSleep)
    }

    fun resumeAndNext(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (!isPaused || !audioPlayer.hasNext) {
            onExit(VoiceCommand.None)
            return
        }
        isPaused = false
        val next = audioPlayer.next() ?: run {
            onExit(VoiceCommand.None)
            return
        }
        currentIndex = (currentIndex + 1).coerceAtMost(currentQueue.size - 1)
        mediaSessionController.setPlaying(next.title, next.artist)
        onExit(VoiceCommand.GoToSleep)
    }

    fun resumeAndPrevious(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (!isPaused) {
            onExit(VoiceCommand.None)
            return
        }
        isPaused = false
        if (audioPlayer.hasPrevious) {
            val prev = audioPlayer.previous() ?: run {
                onExit(VoiceCommand.None)
                return
            }
            currentIndex = (currentIndex - 1).coerceAtLeast(0)
            mediaSessionController.setPlaying(prev.title, prev.artist)
            onExit(VoiceCommand.GoToSleep)
        } else {
            audioPlayer.seekTo(0)
            onExit(VoiceCommand.GoToSleep)
        }
    }

    fun stop() {
        flowGen++
        audioPlayer.stop()
        currentQueue = emptyList()
        currentIndex = -1
        isPaused = false
        audioPlayer.setOnTrackComplete(null)
        audioPlayer.setOnTrackError(null)
        mediaSessionController.setStopped()
        DebugLogger.log(TAG, "stop")
    }

    // ── Internal ──────────────────────────────────────────────────────

    private fun searchAndPlay(query: String, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (query.isBlank()) {
            handleNoIndex(scope, onExit)
            return
        }

        scope.launch(Dispatchers.IO) {
            // Lazy scan if no tracks loaded
            if (audioRepository.trackCount() == 0 && audioRepository.hasFolders()) {
                DebugLogger.log(TAG, "searchAndPlay: lazy scan")
                audioRepository.scanAll()
            }

            if (audioRepository.trackCount() == 0) {
                handleNoIndex(scope, onExit)
                return@launch
            }

            val results = audioRepository.searchAll(query)
            DebugLogger.log(TAG, "searchAndPlay: results=${results.size} for '$query'")
            when {
                results.isEmpty() -> {
                    voiceCommandEngine.speakThenListen(
                        "No matches found for $query. Say a song, album, or artist name, or 'stop'."
                    ) { cmd ->
                        when (cmd) {
                            is VoiceCommand.FreeText -> {
                                val text = cmd.text.trim()
                                if (text.isNotBlank()) searchAndPlay(text, scope, onExit)
                                else handleNoIndex(scope, onExit)
                            }
                            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                                stop()
                                onExit(VoiceCommand.None)
                            }
                            else -> {
                                stop()
                                onExit(cmd)
                            }
                        }
                    }
                }
                else -> {
                    val track = results.first()
                    currentQueue = if (audioRepository.getTracksByAlbum(track.album).size > 1) {
                        val albumTracks = audioRepository.getTracksByAlbum(track.album)
                        val startIdx = albumTracks.indexOfFirst { it.uri == track.uri }
                            .coerceAtLeast(0)
                        currentIndex = startIdx
                        albumTracks
                    } else {
                        currentIndex = 0
                        results
                    }

                    val trackToPlay = currentQueue[currentIndex]
                    voiceManager.speak("Playing ${trackToPlay.title} by ${trackToPlay.artist}.") {
                        playCurrentAndListen(scope, onExit)
                    }
                }
            }
        }
    }

    private fun handleNoIndex(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        voiceCommandEngine.speakThenListen(
            "No audio files found. A sighted helper needs to add an audio folder in the audio player settings. " +
                "Say 'cancel' to go back."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.Cancel, is VoiceCommand.GoBack -> onExit(VoiceCommand.None)
                is VoiceCommand.SessionTimeout, is VoiceCommand.GoToSleep -> onExit(cmd)
                else -> {
                    val text = (cmd as? VoiceCommand.FreeText)?.text?.trim()?.lowercase() ?: ""
                    if (text.contains("setting") || text.contains("folder") || text.contains("audio")) {
                        onExit(VoiceCommand.None)
                    } else {
                        onExit(cmd)
                    }
                }
            }
        }
    }

    private fun playCurrentAndListen(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val track = currentQueue.getOrNull(currentIndex) ?: run {
            announceQueueEnd(scope, onExit)
            return
        }

        audioPlayer.stop()
        audioPlayer.setOnTrackComplete {
            DebugLogger.log(TAG, "track complete: ${it.title}")
            scope.launch {
                if (currentIndex + 1 < currentQueue.size) {
                    currentIndex++
                    playCurrentAndListen(scope, onExit)
                } else {
                    announceQueueEnd(scope, onExit)
                }
            }
        }
        audioPlayer.setOnTrackError { errTrack, msg ->
            DebugLogger.log(TAG, "track error: ${errTrack.title} — $msg")
            if (currentIndex + 1 < currentQueue.size) {
                currentIndex++
                playCurrentAndListen(scope, onExit)
            } else {
                announceQueueEnd(scope, onExit)
            }
        }

        audioPlayer.playQueue(currentQueue, currentIndex)
        mediaSessionController.setPlaying(track.title, track.artist)
        // Go to sleep silently — no beeping, no listening. Audio continues
        // playing in the background. The user can press the power button to
        // wake, pause, and interact.
        onExit(VoiceCommand.GoToSleep)
    }

    private fun openPlaybackWindow(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val gen = flowGen
        voiceCommandEngine.listen { cmd ->
            if (gen != flowGen) return@listen
            handlePlaybackCommand(cmd, scope, onExit)
        }
    }

    private fun handlePlaybackCommand(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Next -> {
                if (audioPlayer.hasNext) {
                    val next = audioPlayer.next() ?: return
                    mediaSessionController.setPlaying(next.title, next.artist)
                    currentIndex = audioPlayer.currentTrack
                        ?.let { currentQueue.indexOf(it) }
                        ?.coerceAtLeast(0) ?: (currentIndex + 1).coerceAtMost(currentQueue.size - 1)
                    openPlaybackWindow(scope, onExit)
                } else {
                    voiceManager.speak("End of queue.") {
                        openPlaybackWindow(scope, onExit)
                    }
                }
            }

            is VoiceCommand.Previous -> {
                if (audioPlayer.hasPrevious) {
                    val prev = audioPlayer.previous() ?: return
                    mediaSessionController.setPlaying(prev.title, prev.artist)
                    currentIndex = (currentIndex - 1).coerceAtLeast(0)
                    openPlaybackWindow(scope, onExit)
                } else {
                    audioPlayer.seekTo(0)
                    openPlaybackWindow(scope, onExit)
                }
            }

            is VoiceCommand.Pause -> {
                audioPlayer.pause()
                isPaused = true
                mediaSessionController.setPaused()
                voiceCommandEngine.speakThenListen(
                    "Paused. Say 'resume', 'continue', or 'play' to resume, or 'stop' to exit."
                ) { resumeCmd ->
                    when (resumeCmd) {
                        is VoiceCommand.ContinueReading -> {
                            audioPlayer.resume()
                            isPaused = false
                            val track = currentQueue.getOrNull(currentIndex)
                            if (track != null) {
                                mediaSessionController.setPlaying(track.title, track.artist)
                            }
                            openPlaybackWindow(scope, onExit)
                        }
                        is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                            stop()
                            onExit(VoiceCommand.None)
                        }
                        is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> {
                            // Stay paused
                            onExit(resumeCmd)
                        }
                        else -> {
                            openPlaybackWindow(scope, onExit)
                        }
                    }
                }
            }

            is VoiceCommand.ContinueReading -> {
                if (isPaused) {
                    audioPlayer.resume()
                    isPaused = false
                    val track = currentQueue.getOrNull(currentIndex)
                    if (track != null) {
                        mediaSessionController.setPlaying(track.title, track.artist)
                    }
                }
                openPlaybackWindow(scope, onExit)
            }

            is VoiceCommand.Repeat -> {
                audioPlayer.seekTo(0)
                openPlaybackWindow(scope, onExit)
            }

            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                stop()
                onExit(VoiceCommand.None)
            }

            is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> {
                // Let audio keep playing — no pause on sleep
                onExit(cmd)
            }

            is VoiceCommand.FreeText -> {
                val text = cmd.text.trim()
                if (text.isNotBlank()) {
                    searchAndPlay(text, scope, onExit)
                } else {
                    // Timeout or empty — go to sleep, audio keeps playing
                    onExit(VoiceCommand.GoToSleep)
                }
            }

            else -> {
                stop()
                onExit(cmd)
            }
        }
    }

    // ── Media session helpers (from BT / notification buttons) ──────

    fun mediaPlay(): Boolean {
        if (isPaused) {
            audioPlayer.resume()
            isPaused = false
            val track = currentQueue.getOrNull(currentIndex)
            if (track != null) mediaSessionController.setPlaying(track.title, track.artist)
            return true
        }
        return false
    }

    fun mediaPause(): Boolean {
        if (audioPlayer.isPlaying) {
            audioPlayer.pause()
            isPaused = true
            mediaSessionController.setPaused()
            return true
        }
        return false
    }

    fun mediaNext(): Boolean {
        if (!audioPlayer.hasNext) return false
        val next = audioPlayer.next() ?: return false
        currentIndex = (currentIndex + 1).coerceAtMost(currentQueue.size - 1)
        mediaSessionController.setPlaying(next.title, next.artist)
        return true
    }

    fun mediaPrevious(): Boolean {
        if (!audioPlayer.hasPrevious) {
            audioPlayer.seekTo(0)
            return true
        }
        val prev = audioPlayer.previous() ?: return false
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        mediaSessionController.setPlaying(prev.title, prev.artist)
        return true
    }

    private fun announceQueueEnd(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        voiceManager.speak("End of queue. Say a song, album, or artist name, or 'cancel' to exit.") {
            onExit(VoiceCommand.None)
        }
    }
}
