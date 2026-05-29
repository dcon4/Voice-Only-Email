package com.example.voicegmail.browser

import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.voice.VoiceCommand
import com.example.voicegmail.voice.VoiceCommandEngine
import com.example.voicegmail.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BrowserVoiceFlow"

/**
 * Voice-driven web browser flow.
 *
 * When the user says "Browser" / "Browse the web" / "Internet", this flow:
 * 1. Asks what to search for
 * 2. Reads back search result titles
 * 3. User picks a result by number
 * 4. Fetches and reads the page content aloud (chunked, like emails)
 * 5. Offers navigation: "next result", "new search", "back", "cancel"
 *
 * Calls [onExit] when the user leaves browser mode so the caller
 * (InboxViewModel) can resume the normal command loop.
 */
@Singleton
class BrowserVoiceFlow @Inject constructor(
    private val browserRepository: BrowserRepository,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val voiceManager: VoiceManager
) {
    private var currentResults: List<SearchResult> = emptyList()
    private var currentResultIndex: Int = 0
    private var currentPageChunks: List<String> = emptyList()
    private var currentChunkIndex: Int = 0

    /**
     * Entry point — start the browser voice flow.
     */
    fun start(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        DebugLogger.log(TAG, "Browser flow started")
        browserRepository.clear()
        askForSearch(scope, onExit)
    }

    // ── Search ────────────────────────────────────────────────────────────

    private fun askForSearch(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        voiceCommandEngine.speakThenListen(
            "Browser. What would you like to search for? Say your search query, or 'cancel' to go back."
        ) { cmd -> handleSearchInput(cmd, scope, onExit) }
    }

    private fun handleSearchInput(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                exitFlow(onExit)
            }
            is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> {
                onExit(cmd)
            }
            is VoiceCommand.FreeText -> {
                val query = cmd.text.trim()
                if (query.isBlank()) {
                    voiceCommandEngine.speakThenListen(
                        "I didn't hear a search query. Say what you'd like to search for, or 'cancel'."
                    ) { retry -> handleSearchInput(retry, scope, onExit) }
                    return
                }
                performSearch(query, scope, onExit)
            }
            else -> {
                // Any other recognized command exits browser mode
                onExit(cmd)
            }
        }
    }

    private fun performSearch(query: String, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        voiceManager.speak("Searching for: $query.")
        scope.launch {
            val results = browserRepository.search(query)
            currentResults = results
            currentResultIndex = 0

            if (results.isEmpty()) {
                voiceCommandEngine.speakThenListen(
                    "No results found for '$query'. Say another search query, or 'cancel'."
                ) { cmd -> handleSearchInput(cmd, scope, onExit) }
            } else {
                readResults(scope, onExit)
            }
        }
    }

    // ── Results navigation ────────────────────────────────────────────────

    private fun readResults(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val count = currentResults.size
        val summary = buildString {
            append("Found $count result${if (count == 1) "" else "s"}. ")
            currentResults.forEachIndexed { i, r ->
                append("${i + 1}: ${r.title}. ")
            }
            append("Say a number to open, 'new search', or 'cancel'.")
        }
        voiceCommandEngine.speakThenListen(summary) { cmd ->
            handleResultSelection(cmd, scope, onExit)
        }
    }

    private fun handleResultSelection(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> exitFlow(onExit)
            is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> onExit(cmd)
            is VoiceCommand.Search -> askForSearch(scope, onExit)
            is VoiceCommand.Refresh -> readResults(scope, onExit) // re-read the list
            is VoiceCommand.Repeat -> readResults(scope, onExit)
            is VoiceCommand.FreeText -> {
                val text = cmd.text.trim()
                val number = extractNumber(text)
                if (number != null && number in 1..currentResults.size) {
                    currentResultIndex = number - 1
                    openResult(currentResultIndex, scope, onExit)
                } else if (text.isNotBlank()) {
                    // Treat as a new search query
                    performSearch(text, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Say a result number (1 to ${currentResults.size}), 'new search', or 'cancel'."
                    ) { retry -> handleResultSelection(retry, scope, onExit) }
                }
            }
            is VoiceCommand.Next -> {
                if (currentResultIndex < currentResults.size - 1) {
                    currentResultIndex++
                    openResult(currentResultIndex, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "That's the last result. Say 'new search', a number, or 'cancel'."
                    ) { retry -> handleResultSelection(retry, scope, onExit) }
                }
            }
            else -> onExit(cmd)
        }
    }

    // ── Page reading ──────────────────────────────────────────────────────

    private fun openResult(index: Int, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val result = currentResults.getOrNull(index)
        if (result == null) {
            voiceCommandEngine.speakThenListen("Invalid result. Say a number or 'cancel'.") { cmd ->
                handleResultSelection(cmd, scope, onExit)
            }
            return
        }

        voiceManager.speak("Opening: ${result.title}.")
        scope.launch {
            val page = browserRepository.fetchPage(result.url)
            if (page == null || page.text.isBlank()) {
                voiceCommandEngine.speakThenListen(
                    "I couldn't read that page. It may be blocked or not a text page. " +
                        "Say 'next' for the next result, a number, 'new search', or 'cancel'."
                ) { cmd -> handleResultSelection(cmd, scope, onExit) }
                return@launch
            }

            // Chunk the page text for reading
            currentPageChunks = chunkText(page.text)
            currentChunkIndex = 0

            voiceManager.speak("${page.title}.")
            readNextChunk(scope, onExit)
        }
    }

    private fun readNextChunk(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (currentChunkIndex >= currentPageChunks.size) {
            // Finished reading the page
            voiceCommandEngine.speakThenListen(
                "End of page. Say 'next' for the next search result, " +
                    "'repeat' to read again, 'new search', or 'cancel'."
            ) { cmd -> handlePostPageCommand(cmd, scope, onExit) }
            return
        }

        val chunk = currentPageChunks[currentChunkIndex]
        currentChunkIndex++

        // Use email chunk reading pattern — speak chunk then auto-advance
        voiceCommandEngine.speakEmailSentenceAndListen(chunk) { cmd ->
            when (cmd) {
                is VoiceCommand.FreeText -> {
                    if (cmd.text.isBlank()) {
                        // Silence — continue reading
                        readNextChunk(scope, onExit)
                    } else {
                        handleReadingInterrupt(cmd, scope, onExit)
                    }
                }
                is VoiceCommand.Pause -> {
                    voiceCommandEngine.speakThenListen(
                        "Paused. Say 'continue' to resume reading, or another command."
                    ) { resumeCmd ->
                        when (resumeCmd) {
                            is VoiceCommand.ContinueReading -> readNextChunk(scope, onExit)
                            else -> handleReadingInterrupt(resumeCmd, scope, onExit)
                        }
                    }
                }
                is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> onExit(cmd)
                is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                    voiceCommandEngine.speakThenListen(
                        "Stopped reading. Say 'next' for next result, 'new search', or 'cancel' to leave browser."
                    ) { retry -> handlePostPageCommand(retry, scope, onExit) }
                }
                else -> handleReadingInterrupt(cmd, scope, onExit)
            }
        }
    }

    private fun handleReadingInterrupt(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                voiceCommandEngine.speakThenListen(
                    "Stopped reading. Say 'next', 'new search', or 'cancel' to leave browser."
                ) { retry -> handlePostPageCommand(retry, scope, onExit) }
            }
            is VoiceCommand.ContinueReading -> readNextChunk(scope, onExit)
            is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> onExit(cmd)
            is VoiceCommand.Next -> {
                if (currentResultIndex < currentResults.size - 1) {
                    currentResultIndex++
                    openResult(currentResultIndex, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No more results. Say 'new search' or 'cancel'."
                    ) { retry -> handlePostPageCommand(retry, scope, onExit) }
                }
            }
            is VoiceCommand.Search -> askForSearch(scope, onExit)
            else -> onExit(cmd)
        }
    }

    private fun handlePostPageCommand(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> exitFlow(onExit)
            is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> onExit(cmd)
            is VoiceCommand.Next -> {
                if (currentResultIndex < currentResults.size - 1) {
                    currentResultIndex++
                    openResult(currentResultIndex, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No more results. Say 'new search' or 'cancel' to leave browser."
                    ) { retry -> handlePostPageCommand(retry, scope, onExit) }
                }
            }
            is VoiceCommand.Previous -> {
                if (currentResultIndex > 0) {
                    currentResultIndex--
                    openResult(currentResultIndex, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "That's the first result. Say 'next', 'new search', or 'cancel'."
                    ) { retry -> handlePostPageCommand(retry, scope, onExit) }
                }
            }
            is VoiceCommand.Repeat -> {
                currentChunkIndex = 0
                readNextChunk(scope, onExit)
            }
            is VoiceCommand.Search -> askForSearch(scope, onExit)
            is VoiceCommand.Refresh -> readResults(scope, onExit)
            is VoiceCommand.FreeText -> {
                val text = cmd.text.trim()
                val number = extractNumber(text)
                if (number != null && number in 1..currentResults.size) {
                    currentResultIndex = number - 1
                    openResult(currentResultIndex, scope, onExit)
                } else if (text.isNotBlank()) {
                    performSearch(text, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Say 'next', 'new search', a result number, or 'cancel'."
                    ) { retry -> handlePostPageCommand(retry, scope, onExit) }
                }
            }
            else -> onExit(cmd)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun exitFlow(onExit: (VoiceCommand) -> Unit) {
        browserRepository.clear()
        onExit(VoiceCommand.None)
    }

    /**
     * Split text into chunks of roughly 200-250 chars at sentence boundaries.
     * Same approach as email chunk reading.
     */
    private fun chunkText(text: String, maxChars: Int = 240): List<String> {
        val sentences = Regex("(?<=[.!?])\\s+")
            .split(text.trim())
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (sentences.isEmpty()) return listOf(text.take(maxChars))

        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        for (s in sentences) {
            if (buf.isNotEmpty() && buf.length + 1 + s.length > maxChars) {
                chunks.add(buf.toString())
                buf.clear()
            }
            if (buf.isNotEmpty()) buf.append(" ")
            buf.append(s)
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString())
        return chunks
    }

    /**
     * Extract a number from spoken text like "1", "one", "number 3", "result two".
     */
    private fun extractNumber(text: String): Int? {
        val lower = text.trim().lowercase()
        lower.toIntOrNull()?.let { return it }
        Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val wordNumbers = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5
        )
        return wordNumbers.entries.firstOrNull { lower.contains(it.key) }?.value
    }
}
