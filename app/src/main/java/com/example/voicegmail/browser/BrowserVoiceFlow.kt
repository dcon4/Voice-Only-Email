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
private const val PAGE_SIZE = 5

/**
 * Voice-driven web browser flow.
 *
 * When the user says "Browser" / "Browse the web" / "Internet", this flow:
 * 1. Asks what to search for
 * 2. Reads back search result titles (in groups of [PAGE_SIZE])
 * 3. User picks a result by number, or "read all"
 * 4. Fetches and reads the page content aloud (chunked, no beep — same as email)
 * 5. Power button pauses reading; "continue" resumes from the same position
 * 6. Offers navigation: "next", "more results", "new search", "cancel"
 *
 * Calls [onExit] when the user leaves browser mode.
 */
@Singleton
class BrowserVoiceFlow @Inject constructor(
    private val browserRepository: BrowserRepository,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val voiceManager: VoiceManager
) {
    // All results from the search (up to 20)
    private var allResults: List<SearchResult> = emptyList()
    // Current page offset (0-based, groups of PAGE_SIZE)
    private var pageOffset: Int = 0
    // Index within allResults of the currently open/read result
    private var currentResultIndex: Int = 0
    // Chunk state for reading a page
    private var currentPageChunks: List<String> = emptyList()
    private var currentChunkIndex: Int = 0
    // Generation counter for reading sessions (same pattern as email reading)
    private var readingGen: Int = 0
    // Whether we're reading all results sequentially
    private var readingAllSequentially: Boolean = false

    /**
     * Entry point — start the browser voice flow.
     */
    fun start(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        DebugLogger.log(TAG, "Browser flow started")
        browserRepository.clear()
        allResults = emptyList()
        pageOffset = 0
        readingAllSequentially = false
        askForSearch(scope, onExit)
    }

    /**
     * Called by InboxViewModel on wake event to pause reading.
     * Returns the resume prompt if reading was in progress, null otherwise.
     */
    fun handleWakeInterrupt(): Boolean {
        if (currentPageChunks.isNotEmpty() && currentChunkIndex > 0) {
            readingGen++ // invalidate in-flight chunk callbacks
            return true
        }
        return false
    }

    /**
     * Resume reading from current position after a wake interrupt.
     */
    fun resumeReading(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        readingGen++
        readNextChunk(scope, onExit, readingGen)
    }

    /**
     * Handle a command received during browser wake pause.
     * Returns true if the command was handled by the browser, false to let inbox handle it.
     */
    fun handleWakeCommand(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit): Boolean {
        return when (cmd) {
            is VoiceCommand.ContinueReading -> {
                resumeReading(scope, onExit); true
            }
            is VoiceCommand.Next -> {
                // Next article in browser context
                if (currentResultIndex < allResults.size - 1) {
                    currentResultIndex++
                    openResult(currentResultIndex, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No more articles. Say 'new search' or 'cancel' to leave browser."
                    ) { c -> handlePostPageCommand(c, scope, onExit) }
                }
                true
            }
            is VoiceCommand.Previous -> {
                if (currentResultIndex > 0) {
                    currentResultIndex--
                    openResult(currentResultIndex, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "That's the first article. Say 'next', 'continue', or 'cancel'."
                    ) { c -> handlePostPageCommand(c, scope, onExit) }
                }
                true
            }
            is VoiceCommand.Repeat -> {
                currentChunkIndex = 0
                readingGen++
                readNextChunk(scope, onExit, readingGen)
                true
            }
            is VoiceCommand.Search -> {
                askForSearch(scope, onExit); true
            }
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                exitFlow(onExit); true
            }
            else -> false // let inbox handle it
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    private fun askForSearch(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        voiceCommandEngine.speakThenListen(
            "Browser. What would you like to search for? Say your search query, or 'cancel' to go back."
        ) { cmd -> handleSearchInput(cmd, scope, onExit) }
    }

    private fun handleSearchInput(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> exitFlow(onExit)
            is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> onExit(cmd)
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
                val text = when (cmd) {
                    is VoiceCommand.LaunchApp -> cmd.query
                    else -> null
                }
                if (text != null && text.isNotBlank()) {
                    performSearch(text, scope, onExit)
                } else {
                    onExit(cmd)
                }
            }
        }
    }

    private fun performSearch(query: String, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        voiceManager.speak("Searching for: $query.")
        scope.launch {
            val results = browserRepository.search(query)
            allResults = results
            pageOffset = 0
            currentResultIndex = 0
            readingAllSequentially = false

            if (results.isEmpty()) {
                voiceCommandEngine.speakThenListen(
                    "No results found for '$query'. Say another search query, or 'cancel'."
                ) { cmd -> handleSearchInput(cmd, scope, onExit) }
            } else {
                presentCurrentPage(scope, onExit)
            }
        }
    }

    // ── Results presentation (paginated in groups of PAGE_SIZE) ────────────

    private fun currentPageResults(): List<SearchResult> {
        val start = pageOffset
        val end = (pageOffset + PAGE_SIZE).coerceAtMost(allResults.size)
        return allResults.subList(start, end)
    }

    private fun presentCurrentPage(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val pageResults = currentPageResults()
        val totalResults = allResults.size
        val startNum = pageOffset + 1
        val endNum = pageOffset + pageResults.size

        val summary = buildString {
            if (pageOffset == 0) {
                append("Found $totalResults result${if (totalResults == 1) "" else "s"}. ")
            } else {
                append("Results $startNum to $endNum of $totalResults. ")
            }
            pageResults.forEachIndexed { i, r ->
                append("${pageOffset + i + 1}: ${r.title}. ")
            }
            append("Say a number to open")
            if (pageResults.size > 1) append(", 'read all'")
            if (endNum < totalResults) append(", 'more results'")
            append(", 'new search', or 'cancel'.")
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
            is VoiceCommand.Refresh, is VoiceCommand.Repeat -> presentCurrentPage(scope, onExit)
            is VoiceCommand.ReadAll, is VoiceCommand.ReadAllUnread -> {
                // "read all" / "read all five"
                startReadingAll(scope, onExit)
            }
            is VoiceCommand.MoreResults -> {
                val nextPageStart = pageOffset + PAGE_SIZE
                if (nextPageStart < allResults.size) {
                    pageOffset = nextPageStart
                    presentCurrentPage(scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No more results available. Say a number, 'new search', or 'cancel'."
                    ) { retry -> handleResultSelection(retry, scope, onExit) }
                }
            }
            is VoiceCommand.Next -> {
                // "next" after listing → show next page of results if available
                val nextPageStart = pageOffset + PAGE_SIZE
                if (nextPageStart < allResults.size) {
                    pageOffset = nextPageStart
                    presentCurrentPage(scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No more results. Say 'new search' or 'cancel' to leave browser."
                    ) { retry -> handleResultSelection(retry, scope, onExit) }
                }
            }
            is VoiceCommand.FreeText -> {
                val text = cmd.text.trim().lowercase()
                // Check for "read all" / "all five" / "read all five"
                if (text.contains("read all") || text.contains("all five") ||
                    text.contains("all of them") || text == "all") {
                    startReadingAll(scope, onExit)
                    return
                }
                // Check for "more results" / "next five" / "more"
                if (text.contains("more result") || text.contains("next five") ||
                    text.contains("more findings") || text == "more") {
                    val nextPageStart = pageOffset + PAGE_SIZE
                    if (nextPageStart < allResults.size) {
                        pageOffset = nextPageStart
                        presentCurrentPage(scope, onExit)
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "No more results available. Say a number, 'new search', or 'cancel'."
                        ) { retry -> handleResultSelection(retry, scope, onExit) }
                    }
                    return
                }
                val number = extractNumber(text)
                if (number != null && number in 1..allResults.size) {
                    currentResultIndex = number - 1
                    readingAllSequentially = false
                    openResult(currentResultIndex, scope, onExit)
                } else if (text.isNotBlank()) {
                    // Treat as a new search query
                    performSearch(text, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Say a result number, 'read all', 'more results', 'new search', or 'cancel'."
                    ) { retry -> handleResultSelection(retry, scope, onExit) }
                }
            }
            else -> {
                val text = when (cmd) {
                    is VoiceCommand.LaunchApp -> cmd.query
                    else -> null
                }
                if (text != null && text.isNotBlank()) {
                    performSearch(text, scope, onExit)
                } else {
                    onExit(cmd)
                }
            }
        }
    }

    // ── Read all sequentially ─────────────────────────────────────────────

    private fun startReadingAll(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        readingAllSequentially = true
        currentResultIndex = pageOffset // start from first in current page
        openResult(currentResultIndex, scope, onExit)
    }

    // ── Page reading (no-beep chunk pattern, same as email) ───────────────

    private fun openResult(index: Int, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val result = allResults.getOrNull(index)
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
                ) { cmd -> handlePostPageCommand(cmd, scope, onExit) }
                return@launch
            }

            // Chunk the page text for reading
            currentPageChunks = chunkText(page.text)
            currentChunkIndex = 0
            readingGen++ // new reading session

            voiceManager.speak("${page.title}.")
            // Small delay to let the title finish before starting chunk reading
            voiceCommandEngine.speakEmailChunk("") {
                readNextChunk(scope, onExit, readingGen)
            }
        }
    }

    /**
     * Read chunk-by-chunk using speakEmailChunk (no mic, no beep).
     * Same pattern as InboxViewModel.readNextChunk — power button interrupts
     * by incrementing readingGen, causing stale callbacks to be discarded.
     */
    private fun readNextChunk(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit, gen: Int) {
        if (gen != readingGen) return // stale callback — new session is active

        if (currentChunkIndex >= currentPageChunks.size) {
            // Finished reading the page
            handlePageFinished(scope, onExit)
            return
        }

        val chunk = currentPageChunks[currentChunkIndex]
        // IMPORTANT: do NOT advance currentChunkIndex until after this chunk
        // finishes playing.  If we advance before speaking and the user then
        // presses the power button mid-chunk, the saved index would point to
        // the NEXT chunk and the interrupted one would be skipped on resume.
        // Advancing inside the onDone callback (with the stale-gen guard)
        // means a power-button wake leaves currentChunkIndex pointing at the
        // chunk that was interrupted, so resume re-reads it from the start —
        // the "Stop & Restart from current sentence" model.
        voiceCommandEngine.speakEmailChunk(chunk) {
            if (gen != readingGen) return@speakEmailChunk // power-button wake invalidated us
            currentChunkIndex++
            readNextChunk(scope, onExit, gen)
        }
    }

    private fun handlePageFinished(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (readingAllSequentially) {
            val pageEnd = (pageOffset + PAGE_SIZE).coerceAtMost(allResults.size)
            val resultsOnPage = pageEnd - pageOffset
            if (currentResultIndex + 1 < pageEnd) {
                val articleNum = currentResultIndex - pageOffset + 1
                currentResultIndex++
                val nextNum = currentResultIndex - pageOffset + 1
                voiceManager.speak(
                    "That concludes article $articleNum of $resultsOnPage. " +
                        "Now, continuing to number $nextNum."
                ) {
                    openResult(currentResultIndex, scope, onExit)
                }
            } else {
                // Finished all in current page
                readingAllSequentially = false
                val nextPageStart = pageOffset + PAGE_SIZE
                if (nextPageStart < allResults.size) {
                    voiceCommandEngine.speakThenListen(
                        "I've read all the articles in this set. " +
                            "Would you like me to list the next five results? Say 'yes' or 'no'."
                    ) { cmd ->
                        when (cmd) {
                            is VoiceCommand.Confirm -> {
                                pageOffset = nextPageStart
                                presentCurrentPage(scope, onExit)
                            }
                            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> exitFlow(onExit)
                            is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> onExit(cmd)
                            is VoiceCommand.FreeText -> {
                                val lower = cmd.text.trim().lowercase()
                                if (lower == "yes" || lower == "yeah" || lower == "sure" ||
                                    lower == "ok" || lower == "okay") {
                                    pageOffset = nextPageStart
                                    presentCurrentPage(scope, onExit)
                                } else {
                                    voiceCommandEngine.speakThenListen(
                                        "OK. Say a number, 'new search', or 'cancel' to leave browser."
                                    ) { retry -> handleResultSelection(retry, scope, onExit) }
                                }
                            }
                            else -> handleResultSelection(cmd, scope, onExit)
                        }
                    }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "I've read all the search results. Say 'new search' or 'cancel' to leave browser."
                    ) { cmd -> handlePostPageCommand(cmd, scope, onExit) }
                }
            }
        } else {
            // Single article finished
            val prompt = buildString {
                append("End of page. ")
                if (currentResultIndex < allResults.size - 1)
                    append("Say 'next' for the next result, ")
                append("'repeat' to read again, 'new search', or 'cancel'.")
            }
            voiceCommandEngine.speakThenListen(prompt) { cmd ->
                handlePostPageCommand(cmd, scope, onExit)
            }
        }
    }

    // ── Post-page commands ────────────────────────────────────────────────

    private fun handlePostPageCommand(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> exitFlow(onExit)
            is VoiceCommand.GoToSleep, is VoiceCommand.SessionTimeout -> onExit(cmd)
            is VoiceCommand.Next -> {
                if (currentResultIndex < allResults.size - 1) {
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
                readingGen++
                readNextChunk(scope, onExit, readingGen)
            }
            is VoiceCommand.ContinueReading -> {
                // Resume from where we left off
                readingGen++
                readNextChunk(scope, onExit, readingGen)
            }
            is VoiceCommand.Search -> askForSearch(scope, onExit)
            is VoiceCommand.Refresh -> presentCurrentPage(scope, onExit)
            is VoiceCommand.FreeText -> {
                val text = cmd.text.trim().lowercase()
                if (text.contains("more result") || text.contains("next five") || text == "more") {
                    val nextPageStart = pageOffset + PAGE_SIZE
                    if (nextPageStart < allResults.size) {
                        pageOffset = nextPageStart
                        presentCurrentPage(scope, onExit)
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "No more results. Say 'new search' or 'cancel'."
                        ) { retry -> handlePostPageCommand(retry, scope, onExit) }
                    }
                    return
                }
                val number = extractNumber(text)
                if (number != null && number in 1..allResults.size) {
                    currentResultIndex = number - 1
                    readingAllSequentially = false
                    openResult(currentResultIndex, scope, onExit)
                } else if (text.isNotBlank()) {
                    performSearch(text, scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Say 'next', 'new search', a result number, or 'cancel'."
                    ) { retry -> handlePostPageCommand(retry, scope, onExit) }
                }
            }
            else -> {
                val text = when (cmd) {
                    is VoiceCommand.LaunchApp -> cmd.query
                    else -> null
                }
                if (text != null && text.isNotBlank()) {
                    performSearch(text, scope, onExit)
                } else {
                    onExit(cmd)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Clear browser reading state without calling any exit callback.
     * Used when the user issues an inbox command during a wake-pause,
     * abandoning the browser bookmark.
     */
    fun clearState() {
        readingGen++
        currentPageChunks = emptyList()
        currentChunkIndex = 0
        readingAllSequentially = false
        browserRepository.clear()
    }

    private fun exitFlow(onExit: (VoiceCommand) -> Unit) {
        clearState()
        onExit(VoiceCommand.None)
    }

    /**
     * Split text into chunks at sentence boundaries — same as email reading.
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

    private fun extractNumber(text: String): Int? {
        val lower = text.trim().lowercase()
        lower.toIntOrNull()?.let { return it }
        Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val wordNumbers = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5
        )
        return wordNumbers.entries.firstOrNull { lower.contains(it.key) }?.value
    }
}
