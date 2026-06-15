package com.example.voicegmail.bible

import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.voice.VoiceCommand
import com.example.voicegmail.voice.VoiceCommandEngine
import com.example.voicegmail.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BibleVoiceFlow"
private const val CHUNK_SIZE = 800

/**
 * Voice-driven Bible reading flow using bible-api.com text + TTS.
 *
 * No API key required. Reads each chapter as TTS-synthesized speech.
 * Supports separate Bible voice via [VoiceManager.bibleVoiceName].
 *
 * Chapters flow continuously with only a "Chapter X" announcement.
 * Single-verse references (e.g. "John 3:16") read only that verse.
 * Supports pause/resume: power button saves position; "continue" resumes.
 */
@Singleton
class BibleVoiceFlow @Inject constructor(
    private val bibleRepository: BibleRepository,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val voiceManager: VoiceManager
) {
    private var currentBookId: String? = null
    private var currentBookName: String? = null
    private var currentChapter: Int = 1
    private var maxChapter: Int = 1
    private var currentVerse: Int? = null

    private val isSingleVerse: Boolean get() = currentVerse != null

    // ── Chunked reading state ──────────────────────────────────────────────
    private var readingGen: Int = 0
    private var currentChunks: List<String> = emptyList()
    private var currentChunkIndex: Int = 0
    private var isReadingActive: Boolean = false
    private var _isPaused: Boolean = false
    val isPaused: Boolean get() = _isPaused

    /** Delegates to [BibleRepository.isBibleReference]. */
    fun isBibleReference(text: String): Boolean = bibleRepository.isBibleReference(text)

    /**
     * Entry point — start the Bible voice menu from the book prompt.
     */
    fun start(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        DebugLogger.log(TAG, "Bible flow started")
        clearState()
        voiceCommandEngine.speakThenListen(
            "Bible. Which book would you like to hear? " +
                "Say a book name like 'Genesis' or 'John', " +
                "or say 'cancel' to go back."
        ) { cmd -> handleBookSelection(cmd, scope, onExit) }
    }

    /**
     * Start Bible flow directly with a spoken reference (e.g. "John 3:16").
     * Parses the reference and skips book/chapter prompts.
     */
    fun startWithReference(text: String, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        DebugLogger.log(TAG, "startWithReference: $text")
        clearState()
        val (bookId, chapter, verse) = bibleRepository.tryParseVerseReference(text)
        DebugLogger.log(TAG, "startWithReference: parsed -> bookId=$bookId ch=$chapter v=$verse")
        if (bookId == null) {
            voiceCommandEngine.speakThenListen(
                "I couldn't recognise '$text' as a Bible reference. " +
                    "Say a book name like 'Genesis' or 'John 3 16'."
            ) { cmd -> if (cmd is VoiceCommand.FreeText) {
                startWithReference(cmd.text, scope, onExit)
            } else {
                onExit(cmd)
            }}
            return
        }
        currentBookId = bookId
        currentVerse = verse

        scope.launch {
            try {
                val resolved = bibleRepository.resolveApiBookId(text)
                currentBookId = resolved?.first ?: bookId
                currentBookName = resolved?.second ?: text
                maxChapter = bibleRepository.getMaxChapter(currentBookId!!)

                if (chapter != null && chapter in 1..maxChapter) {
                    currentChapter = chapter
                    if (verse != null) {
                        readSingleVerse(scope, onExit)
                    } else {
                        readCurrentChapter(scope, onExit)
                    }
                } else {
                    // Chapter out of range or not given — ask
                    if (maxChapter == 1) {
                        currentChapter = 1
                        if (verse != null) readSingleVerse(scope, onExit)
                        else readCurrentChapter(scope, onExit)
                    } else if (chapter == null) {
                        askForChapter(scope, onExit)
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "$currentBookName has $maxChapter chapters. " +
                                "Which chapter? Say a number."
                        ) { cmd -> handleChapterSelection(cmd, scope, onExit) }
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log(TAG, "Error in startWithReference: ${e.message}")
                start(scope, onExit)
            }
        }
    }

    // ── Book selection ─────────────────────────────────────────────────────

    private fun handleBookSelection(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                stop()
                onExit(VoiceCommand.None)
            }
            is VoiceCommand.GoToSleep -> {
                stop()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                stop()
                onExit(cmd)
            }
            is VoiceCommand.FreeText -> {
                val spoken = cmd.text.trim()
                if (spoken.isBlank()) {
                    voiceCommandEngine.speakThenListen(
                        "I didn't hear a book name. Say a book like 'Genesis', 'Psalms', or 'Matthew'. " +
                            "Or say 'cancel' to go back."
                    ) { retry -> handleBookSelection(retry, scope, onExit) }
                    return
                }

                // Try verse reference first (e.g. "John 3:16")
                val (bookId, chapter, verse) = bibleRepository.tryParseVerseReference(spoken)
                if (bookId != null) {
                    currentBookId = bookId
                    currentBookName = spoken.replaceFirstChar { it.uppercase() }
                    currentVerse = verse
                    DebugLogger.log(TAG, "Book selected: $currentBookName ($bookId) ch=$chapter v=$verse")
                    scope.launch {
                        try {
                            val resolved = bibleRepository.resolveApiBookId(spoken)
                            currentBookId = resolved?.first ?: bookId
                            currentBookName = resolved?.second ?: currentBookName
                            maxChapter = bibleRepository.getMaxChapter(currentBookId!!)

                            if (chapter != null && chapter in 1..maxChapter) {
                                currentChapter = chapter
                                if (verse != null) readSingleVerse(scope, onExit)
                                else readCurrentChapter(scope, onExit)
                            } else if (maxChapter == 1) {
                                currentChapter = 1
                                if (verse != null) readSingleVerse(scope, onExit)
                                else readCurrentChapter(scope, onExit)
                            } else {
                                askForChapter(scope, onExit)
                            }
                        } catch (e: Exception) {
                            DebugLogger.log(TAG, "Error loading chapters: ${e.message}")
                            voiceCommandEngine.speakThenListen(
                                "I had trouble loading Bible data. ${e.message ?: "Please try again."}. " +
                                    "Say a book name or 'cancel'."
                            ) { retry -> handleBookSelection(retry, scope, onExit) }
                        }
                    }
                    return
                }

                // Legacy parsing — book name only
                val parsed = parseBookAndChapter(spoken)
                if (parsed.first == null) {
                    voiceCommandEngine.speakThenListen(
                        "I didn't recognise '$spoken' as a Bible book. " +
                            "Try saying the full name, like 'Genesis' or 'First Corinthians'. " +
                            "Or say 'cancel'."
                    ) { retry -> handleBookSelection(retry, scope, onExit) }
                    return
                }

                currentBookId = parsed.first
                currentBookName = spoken.replaceFirstChar { it.uppercase() }
                currentVerse = null
                DebugLogger.log(TAG, "Book selected: $currentBookName (${parsed.first})")

                scope.launch {
                    try {
                        val resolved = bibleRepository.resolveApiBookId(spoken)
                        currentBookId = resolved?.first ?: parsed.first
                        currentBookName = resolved?.second ?: currentBookName
                        maxChapter = bibleRepository.getMaxChapter(currentBookId!!)

                        val chapterFromSpeech = parsed.second
                        if (chapterFromSpeech != null && chapterFromSpeech in 1..maxChapter) {
                            currentChapter = chapterFromSpeech
                            readCurrentChapter(scope, onExit)
                        } else if (maxChapter == 1) {
                            currentChapter = 1
                            readCurrentChapter(scope, onExit)
                        } else {
                            askForChapter(scope, onExit)
                        }
                    } catch (e: Exception) {
                        DebugLogger.log(TAG, "Error loading chapters: ${e.message}")
                        voiceCommandEngine.speakThenListen(
                            "I had trouble loading Bible data. ${e.message ?: "Please try again."}. " +
                                "Say a book name or 'cancel'."
                        ) { retry -> handleBookSelection(retry, scope, onExit) }
                    }
                }
            }
            else -> {
                stop()
                onExit(cmd)
            }
        }
    }

    // ── Chapter selection ──────────────────────────────────────────────────

    private fun askForChapter(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        voiceCommandEngine.speakThenListen(
            "$currentBookName has $maxChapter chapters. Which chapter? Say a number, or 'chapter 1'."
        ) { cmd -> handleChapterSelection(cmd, scope, onExit) }
    }

    private fun handleChapterSelection(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                voiceCommandEngine.speakThenListen(
                    "OK. Which book would you like to hear? Or say 'cancel' to leave Bible mode."
                ) { retry -> handleBookSelection(retry, scope, onExit) }
            }
            is VoiceCommand.GoToSleep -> {
                stop()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                stop()
                onExit(cmd)
            }
            is VoiceCommand.FreeText -> {
                val chapter = extractChapterNumber(cmd.text)
                if (chapter == null || chapter < 1 || chapter > maxChapter) {
                    voiceCommandEngine.speakThenListen(
                        "Please say a chapter number between 1 and $maxChapter. Or say 'cancel'."
                    ) { retry -> handleChapterSelection(retry, scope, onExit) }
                } else {
                    currentChapter = chapter
                    currentVerse = null
                    readCurrentChapter(scope, onExit)
                }
            }
            else -> {
                stop()
                onExit(cmd)
            }
        }
    }

    // ── Single-Verse Reading ───────────────────────────────────────────────

    private fun readSingleVerse(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val bookId = currentBookId
        val bookName = currentBookName
        val verse = currentVerse
        DebugLogger.log(TAG, "readSingleVerse: bookId=$bookId bookName=$bookName ch=$currentChapter v=$verse")
        if (bookId == null || bookName == null || verse == null) {
            start(scope, onExit); return
        }

        voiceManager.speak("$bookName $currentChapter, verse $verse.")

        scope.launch {
            try {
                val verseText = bibleRepository.getVerseText(bookName, currentChapter, verse)
                val bibleVoice = voiceManager.bibleVoiceName
                val speak: (String, () -> Unit) -> Unit = if (bibleVoice.isNotBlank())
                    { t, done -> voiceManager.speakWithVoice(t, bibleVoice, done) }
                else
                    { t, done -> voiceManager.speak(t, done) }
                speak(verseText) {
                    voiceCommandEngine.speakThenListen(
                        "End of $bookName $currentChapter verse $verse. " +
                            "Say 'repeat' or 'cancel'."
                    ) { cmd -> handlePostReadingCommand(cmd, scope, onExit) }
                }
            } catch (e: Exception) {
                DebugLogger.log(TAG, "Error fetching verse: ${e.message}")
                voiceCommandEngine.speakThenListen(
                    "Error loading verse. ${e.message ?: "Unknown error."}. " +
                        "Say 'try again', another chapter, or 'cancel'."
                ) { cmd -> handlePostReadingCommand(cmd, scope, onExit) }
            }
        }
    }

    // ── Chunked TTS Reading ────────────────────────────────────────────────

    private fun readCurrentChapter(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit, startChunk: Int = 0) {
        val bookId = currentBookId
        val bookName = currentBookName
        DebugLogger.log(TAG, "readCurrentChapter: bookId=$bookId bookName=$bookName ch=$currentChapter")
        if (bookId == null || bookName == null) {
            start(scope, onExit); return
        }

        isReadingActive = true
        _isPaused = false
        readingGen++
        val gen = readingGen

        voiceManager.speak("Reading $bookName, chapter $currentChapter.")

        scope.launch {
            try {
                val chapterText = bibleRepository.getChapterText(bookId, currentChapter, bookName)
                currentChunks = splitTextIntoChunks(chapterText, CHUNK_SIZE)
                currentChunkIndex = startChunk.coerceIn(0, (currentChunks.size - 1).coerceAtLeast(0))
                readNextChunk(scope, onExit, gen)
            } catch (e: Exception) {
                DebugLogger.log(TAG, "Error fetching chapter text: ${e.message}")
                voiceCommandEngine.speakThenListen(
                    "Error loading chapter. ${e.message ?: "Unknown error."}. " +
                        "Say 'try again', another chapter, or 'cancel'."
                ) { cmd -> handlePostReadingCommand(cmd, scope, onExit) }
            }
        }
    }

    private fun readNextChunk(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit, gen: Int) {
        if (gen != readingGen) {
            DebugLogger.verbose(TAG, "readNextChunk: stale gen=$gen != readingGen=$readingGen — discarding")
            return
        }
        if (currentChunkIndex >= currentChunks.size) {
            isReadingActive = false
            handleReadingComplete(scope, onExit)
            return
        }

        val chunk = currentChunks[currentChunkIndex]
        val bibleVoice = voiceManager.bibleVoiceName

        val speak: (String, () -> Unit) -> Unit = if (bibleVoice.isNotBlank())
            { text, done -> voiceManager.speakWithVoice(text, bibleVoice, done) }
        else
            { text, done -> voiceManager.speak(text, done) }

        speak(chunk) {
            if (gen != readingGen) return@speak
            currentChunkIndex++
            readNextChunk(scope, onExit, gen)
        }
    }

    // ── Continuous chapter flow (always on — no end-of-chapter prompt) ─────

    private fun handleReadingComplete(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        currentVerse = null
        autoAdvanceChapter(scope, onExit)
    }

    private fun autoAdvanceChapter(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (currentChapter < maxChapter) {
            currentChapter++
            voiceManager.speak("Chapter $currentChapter") {
                readCurrentChapter(scope, onExit)
            }
        } else {
            scope.launch {
                try {
                    val books = bibleRepository.getBooks()
                    val currentIndex = books.indexOfFirst { it.id == currentBookId }
                    if (currentIndex >= 0 && currentIndex < books.size - 1) {
                        val nextBook = books[currentIndex + 1]
                        currentBookId = nextBook.id
                        currentBookName = nextBook.name
                        currentChapter = 1
                        maxChapter = bibleRepository.getMaxChapter(nextBook.id)
                        voiceManager.speak("${nextBook.name}, chapter 1") {
                            readCurrentChapter(scope, onExit)
                        }
                    } else {
                        voiceManager.speak("End of the Bible.") {
                            onExit(VoiceCommand.None)
                        }
                    }
                } catch (e: Exception) {
                    DebugLogger.log(TAG, "Error advancing to next book: ${e.message}")
                    onExit(VoiceCommand.None)
                }
            }
        }
    }

    // ── Pause / Resume ─────────────────────────────────────────────────────

    fun handleWakeInterrupt(): Boolean {
        val wasReading = isReadingActive
        if (wasReading || _isPaused) {
            DebugLogger.log(TAG, "handleWakeInterrupt: pausing at chunk $currentChunkIndex/${currentChunks.size}")
            readingGen++
            isReadingActive = false
            _isPaused = true
            bibleRepository.stopReading()
        }
        return wasReading
    }

    fun resumeReading(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (!_isPaused || currentBookId == null) {
            DebugLogger.log(TAG, "resumeReading: nothing to resume (_isPaused=$_isPaused bookId=$currentBookId) — starting fresh")
            clearState()
            start(scope, onExit)
            return
        }
        val savedChunkIndex = currentChunkIndex
        DebugLogger.log(TAG, "resumeReading: resuming book=$currentBookName ch=$currentChapter v=$currentVerse chunk=$savedChunkIndex/${currentChunks.size} isSingleVerse=$isSingleVerse")
        _isPaused = false
        if (isSingleVerse) {
            readSingleVerse(scope, onExit)
        } else {
            readCurrentChapter(scope, onExit, savedChunkIndex)
        }
    }

    fun repeatChapter(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (currentBookId == null || currentBookName == null) {
            clearState()
            start(scope, onExit)
            return
        }
        _isPaused = false
        if (isSingleVerse) {
            readSingleVerse(scope, onExit)
        } else {
            readCurrentChapter(scope, onExit, 0)
        }
    }

    fun stop() {
        readingGen++
        isReadingActive = false
        _isPaused = false
        currentChunks = emptyList()
        currentChunkIndex = 0
        bibleRepository.stopReading()
    }

    private fun clearState() {
        readingGen = 0
        currentChunks = emptyList()
        currentChunkIndex = 0
        isReadingActive = false
        _isPaused = false
        currentVerse = null
        currentChapter = 1
    }

    // ── Post-reading commands ──────────────────────────────────────────────

    private fun handlePostReadingCommand(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        // Single-verse mode: no chapter navigation
        if (isSingleVerse) {
            handleSingleVersePostCommand(cmd, scope, onExit)
            return
        }
        when (cmd) {
            is VoiceCommand.Next -> {
                if (currentChapter < maxChapter) {
                    currentChapter++
                    readCurrentChapter(scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "That was the last chapter of $currentBookName. " +
                            "Say a book name to switch, or 'cancel' to leave Bible mode."
                    ) { retry -> handleBookSelection(retry, scope, onExit) }
                }
            }
            is VoiceCommand.Previous -> {
                if (currentChapter > 1) {
                    currentChapter--
                    readCurrentChapter(scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "You're at chapter 1 of $currentBookName. Say 'next', a chapter number, or 'cancel'."
                    ) { retry -> handlePostReadingCommand(retry, scope, onExit) }
                }
            }
            is VoiceCommand.Repeat -> readCurrentChapter(scope, onExit)
            is VoiceCommand.TryAgain -> readCurrentChapter(scope, onExit)
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                stop()
                voiceCommandEngine.speakThenListen(
                    "Leaving Bible mode. Say a command."
                ) { exitCmd -> onExit(exitCmd) }
            }
            is VoiceCommand.GoToSleep -> {
                stop()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                stop()
                onExit(cmd)
            }
            is VoiceCommand.FreeText -> {
                val chapter = extractChapterNumber(cmd.text)
                if (chapter != null && chapter in 1..maxChapter) {
                    currentChapter = chapter
                    currentVerse = null
                    readCurrentChapter(scope, onExit)
                } else {
                    val (bookId, ch, v) = bibleRepository.tryParseVerseReference(cmd.text)
                    if (bookId != null) {
                        currentChapter = ch ?: 1
                        currentVerse = v
                        scope.launch {
                            try {
                                val resolved = bibleRepository.resolveApiBookId(cmd.text)
                                currentBookId = resolved?.first ?: bookId
                                currentBookName = resolved?.second ?: cmd.text.replaceFirstChar { it.uppercase() }
                                maxChapter = bibleRepository.getMaxChapter(currentBookId!!)
                                if (v != null) readSingleVerse(scope, onExit)
                                else readCurrentChapter(scope, onExit)
                            } catch (e: Exception) {
                                voiceCommandEngine.speakThenListen(
                                    "Error loading book info. Say a command or 'cancel'."
                                ) { retry -> handlePostReadingCommand(retry, scope, onExit) }
                            }
                        }
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "I didn't understand '${cmd.text}'. " +
                                "Say 'next', 'repeat', a chapter number, a book name, or 'cancel'."
                        ) { retry -> handlePostReadingCommand(retry, scope, onExit) }
                    }
                }
            }
            else -> {
                stop()
                onExit(cmd)
            }
        }
    }

    private fun handleSingleVersePostCommand(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Repeat, is VoiceCommand.TryAgain -> readSingleVerse(scope, onExit)
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                stop()
                voiceCommandEngine.speakThenListen(
                    "Leaving Bible mode. Say a command."
                ) { exitCmd -> onExit(exitCmd) }
            }
            is VoiceCommand.GoToSleep -> { stop(); onExit(cmd) }
            is VoiceCommand.SessionTimeout -> { stop(); onExit(cmd) }
            is VoiceCommand.FreeText -> {
                val (bookId, ch, v) = bibleRepository.tryParseVerseReference(cmd.text)
                if (bookId != null) {
                    currentChapter = ch ?: 1
                    currentVerse = v
                    scope.launch {
                        try {
                            val resolved = bibleRepository.resolveApiBookId(cmd.text)
                            currentBookId = resolved?.first ?: bookId
                            currentBookName = resolved?.second ?: cmd.text.replaceFirstChar { it.uppercase() }
                            maxChapter = bibleRepository.getMaxChapter(currentBookId!!)
                            if (v != null) readSingleVerse(scope, onExit)
                            else readCurrentChapter(scope, onExit)
                        } catch (e: Exception) {
                            voiceCommandEngine.speakThenListen(
                                "Error. Say 'cancel' to leave Bible mode."
                            ) { retry -> handleSingleVersePostCommand(retry, scope, onExit) }
                        }
                    }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Say 'repeat', a Bible reference like 'Psalm 23', or 'cancel'."
                    ) { retry -> handleSingleVersePostCommand(retry, scope, onExit) }
                }
            }
            else -> {
                stop()
                onExit(cmd)
            }
        }
    }

    // ── Chunk splitting ────────────────────────────────────────────────────

    private fun splitTextIntoChunks(text: String, maxSize: Int): List<String> {
        if (text.length <= maxSize) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxSize).coerceAtMost(text.length)
            if (end < text.length) {
                val breakAt = text.lastIndexOf(". ", end - 1)
                if (breakAt > start) {
                    end = breakAt + 1
                }
            }
            result.add(text.substring(start, end))
            start = end
        }
        return result
    }

    // ── Parsing helpers ───────────────────────────────────────────────────

    private fun parseBookAndChapter(spoken: String): Pair<String?, Int?> {
        val lower = spoken.trim().lowercase()

        val numberAtEnd = Regex("(\\d+)\\s*$").find(lower)
        val chapterStr = lower.replace(Regex("\\bchapter\\b"), "").trim()

        if (numberAtEnd != null) {
            val chapter = numberAtEnd.groupValues[1].toIntOrNull()
            val bookPart = lower.substring(0, numberAtEnd.range.first).trim()
                .replace(Regex("\\bchapter\\b"), "").trim()
            val bookId = bibleRepository.resolveBookId(bookPart)
            if (bookId != null) return Pair(bookId, chapter)
        }

        val bookId = bibleRepository.resolveBookId(lower)
        return Pair(bookId, null)
    }

    private fun extractChapterNumber(text: String): Int? {
        val lower = text.trim().lowercase()
        lower.toIntOrNull()?.let { return it }
        Regex("chapter\\s+(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val wordNumbers = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
            "nineteen" to 19, "twenty" to 20
        )
        return wordNumbers[lower]
    }
}
