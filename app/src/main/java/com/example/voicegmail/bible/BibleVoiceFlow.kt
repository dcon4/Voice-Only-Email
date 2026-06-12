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

/**
 * Voice-driven Bible audio flow.
 *
 * When the user says "Bible", this flow takes over the voice loop:
 * 1. Asks which book to play (or allows "Genesis chapter 3" in one shot)
 * 2. Asks which chapter
 * 3. Streams the audio via MediaPlayer
 * 4. On completion, offers next chapter or return to inbox
 *
 * The flow calls [onExit] when the user says "cancel" / "go back" so the
 * caller (InboxViewModel) can resume its normal command loop.
 */
@Singleton
class BibleVoiceFlow @Inject constructor(
    private val bibleRepository: BibleRepository,
    private val bibleTextRepository: BibleTextRepository,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val voiceManager: VoiceManager
) {
    private var currentBookId: String? = null
    private var currentBookName: String? = null
    private var currentChapter: Int = 1
    private var maxChapter: Int = 1

    /**
     * Entry point — start the Bible voice menu.
     * [scope] is used for suspend calls (API fetches).
     * [onExit] is called when the user leaves the Bible flow.
     */
    fun start(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        DebugLogger.log(TAG, "Bible flow started")

        if (!bibleRepository.isConfigured()) {
            // Bible audio unavailable — offer text-reading fallback using bible-api.com
            voiceCommandEngine.speakThenListen(
                "Bible audio is not configured. I can still read Bible passages using text. " +
                    "Say a book and chapter like 'John chapter 3' or say 'cancel' to go back."
            ) { cmd ->
                when (cmd) {
                    is VoiceCommand.Cancel, is VoiceCommand.GoBack -> onExit(VoiceCommand.None)
                    is VoiceCommand.FreeText -> {
                        val spoken = cmd.text.trim()
                        if (spoken.isBlank()) {
                            // re-prompt
                            voiceCommandEngine.speakThenListen(
                                "I didn't hear that. Say a book and chapter like 'John 3', or say 'cancel'."
                            ) { retry -> start(scope, onExit) }
                            return@speakThenListen
                        }

                        // Try parse as book+chapter (reuse parseBookAndChapter helper)
                        val parsed = parseBookAndChapter(spoken)
                        val bookId = parsed.first
                        val chapFromSpeech = parsed.second

                        // Build a reference string. If parsing found a book id and chapter,
                        // prefer the raw spoken text as it's likely to contain the chapter.
                        val reference = if (bookId != null && chapFromSpeech != null) {
                            spoken // let the text-based API parse it (e.g. "John 3:16" or "John chapter 3")
                        } else {
                            // Use the spoken phrase directly — bible-api can accept various forms
                            spoken
                        }

                        // Launch coroutine to fetch text and speak it
                        scope.launch {
                            try {
                                val passage = bibleTextRepository.getPassageText(reference)
                                if (passage == null || passage.isBlank()) {
                                    voiceCommandEngine.speakThenListen(
                                        "I couldn't fetch that passage. Try 'John 3:16' or 'Psalm 23', or say 'cancel'."
                                    ) { retry -> start(scope, onExit) }
                                    return@launch
                                }

                                // Chunk the returned text and read aloud using existing TTS helpers
                                val chunks = chunkTextForTts(passage)
                                readChunksSequentially(chunks, scope) {
                                    // after reading, offer to read another or exit
                                    voiceCommandEngine.speakThenListen(
                                        "Read another passage or say 'cancel' to go back."
                                    ) { nextCmd ->
                                        when (nextCmd) {
                                            is VoiceCommand.FreeText -> start(scope, onExit)
                                            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> onExit(VoiceCommand.None)
                                            else -> start(scope, onExit)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                voiceCommandEngine.speakThenListen(
                                    "Error fetching passage: ${e.message ?: "unknown"}. Say a command."
                                ) { cmd2 -> onExit(cmd2) }
                            }
                        }
                    }
                    else -> onExit(cmd)
                }
            }
            return
        }

        voiceCommandEngine.speakThenListen(
            "Bible. Which book would you like to hear? " +
                "Say a book name like 'Genesis' or 'John', " +
                "or say 'cancel' to go back."
        ) { cmd -> handleBookSelection(cmd, scope, onExit) }
    }

    // ── Book selection ─────────────────────────────────────────────────────

    private fun handleBookSelection(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                bibleRepository.stopAudio()
                onExit(VoiceCommand.None)
            }
            is VoiceCommand.GoToSleep -> {
                bibleRepository.stopAudio()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                bibleRepository.stopAudio()
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

                // Try to parse "book chapter" in one shot (e.g. "Genesis 3" or "John chapter 5")
                val parsed = parseBookAndChapter(spoken)
                val bookId = parsed.first
                val chapterFromSpeech = parsed.second

                if (bookId == null) {
                    voiceCommandEngine.speakThenListen(
                        "I didn't recognize '$spoken' as a Bible book. " +
                            "Try saying the full name, like 'Genesis' or 'First Corinthians'. " +
                            "Or say 'cancel'."
                    ) { retry -> handleBookSelection(retry, scope, onExit) }
                    return
                }

                currentBookId = bookId
                currentBookName = spoken.replaceFirstChar { it.uppercase() }
                DebugLogger.log(TAG, "Book selected: $currentBookName ($bookId)")

                // Find chapter count for this book
                scope.launch {
                    try {
                        val books = bibleRepository.getBooks()
                        val bookInfo = books.find { it.book_id == bookId }
                        maxChapter = bookInfo?.chapters?.maxOrNull() ?: 1
                        currentBookName = bookInfo?.name ?: currentBookName

                        if (chapterFromSpeech != null && chapterFromSpeech in 1..maxChapter) {
                            // User said book + chapter together
                            currentChapter = chapterFromSpeech
                            playCurrentChapter(scope, onExit)
                        } else if (maxChapter == 1) {
                            // Only one chapter (e.g. Obadiah, Philemon, Jude)
                            currentChapter = 1
                            playCurrentChapter(scope, onExit)
                        } else {
                            askForChapter(scope, onExit)
                        }
                    } catch (e: Exception) {
                        DebugLogger.log(TAG, "Error loading books: ${e.message}")
                        voiceCommandEngine.speakThenListen(
                            "I had trouble loading Bible data. ${e.message ?: "Please try again."}. " +
                                "Say a book name or 'cancel'."
                        ) { retry -> handleBookSelection(retry, scope, onExit) }
                    }
                }
            }
            else -> {
                // Any other command — exit Bible flow and let inbox handle it
                bibleRepository.stopAudio()
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
                // Go back to book selection
                voiceCommandEngine.speakThenListen(
                    "OK. Which book would you like to hear? Or say 'cancel' to leave Bible mode."
                ) { retry -> handleBookSelection(retry, scope, onExit) }
            }
            is VoiceCommand.GoToSleep -> {
                bibleRepository.stopAudio()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                bibleRepository.stopAudio()
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
                    playCurrentChapter(scope, onExit)
                }
            }
            else -> {
                bibleRepository.stopAudio()
                onExit(cmd)
            }
        }
    }

    // ── Playback ─────────────────────────────────────────────────────────�[...]

    private fun playCurrentChapter(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val bookId = currentBookId ?: return
        voiceManager.speak("Playing $currentBookName chapter $currentChapter.")

        scope.launch {
            try {
                val url = bibleRepository.getChapterAudioUrl(bookId, currentChapter)
                if (url == null) {
                    voiceCommandEngine.speakThenListen(
                        "Sorry, audio for $currentBookName chapter $currentChapter is not available. " +
                            "Say another chapter number, 'cancel', or a different book."
                    ) { cmd -> handleChapterSelection(cmd, scope, onExit) }
                    return@launch
                }

                bibleRepository.playAudio(
                    url = url,
                    onComplete = { handlePlaybackComplete(scope, onExit) },
                    onError = { msg ->
                        voiceCommandEngine.speakThenListen(
                            "Audio playback failed. $msg. " +
                                "Say 'try again', another chapter, or 'cancel'."
                        ) { cmd -> handlePostPlaybackCommand(cmd, scope, onExit) }
                    }
                )
            } catch (e: Exception) {
                DebugLogger.log(TAG, "Error getting chapter audio: ${e.message}")
                voiceCommandEngine.speakThenListen(
                    "Error loading audio. ${e.message ?: "Unknown error."}. " +
                        "Say 'try again', another chapter, or 'cancel'."
                ) { cmd -> handlePostPlaybackCommand(cmd, scope, onExit) }
            }
        }
    }

    private fun handlePlaybackComplete(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val hasNext = currentChapter < maxChapter
        val prompt = if (hasNext) {
            "End of $currentBookName chapter $currentChapter. " +
                "Say 'next' for chapter ${currentChapter + 1}, " +
                "say a chapter number, 'repeat', or 'cancel' to leave Bible mode."
        } else {
            "End of $currentBookName chapter $currentChapter. That's the last chapter. " +
                "Say 'repeat', choose another book, or 'cancel' to leave Bible mode."
        }
        voiceCommandEngine.speakThenListen(prompt) { cmd ->
            handlePostPlaybackCommand(cmd, scope, onExit)
        }
    }

    private fun handlePostPlaybackCommand(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        when (cmd) {
            is VoiceCommand.Next -> {
                if (currentChapter < maxChapter) {
                    currentChapter++
                    playCurrentChapter(scope, onExit)
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
                    playCurrentChapter(scope, onExit)
                } else {
                    voiceCommandEngine.speakThenListen(
                        "You're at chapter 1 of $currentBookName. Say 'next', a chapter number, or 'cancel'."
                    ) { retry -> handlePostPlaybackCommand(retry, scope, onExit) }
                }
            }
            is VoiceCommand.Repeat -> playCurrentChapter(scope, onExit)
            is VoiceCommand.TryAgain -> playCurrentChapter(scope, onExit)
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                bibleRepository.stopAudio()
                voiceCommandEngine.speakThenListen(
                    "Leaving Bible mode. Say a command."
                ) { exitCmd -> onExit(exitCmd) }
            }
            is VoiceCommand.GoToSleep -> {
                bibleRepository.stopAudio()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                bibleRepository.stopAudio()
                onExit(cmd)
            }
            is VoiceCommand.FreeText -> {
                // Could be a chapter number or a new book name
                val chapter = extractChapterNumber(cmd.text)
                if (chapter != null && chapter in 1..maxChapter) {
                    currentChapter = chapter
                    playCurrentChapter(scope, onExit)
                } else {
                    // Maybe they said a new book name
                    val bookId = bibleRepository.resolveBookId(cmd.text)
                    if (bookId != null) {
                        currentBookId = bookId
                        currentBookName = cmd.text.replaceFirstChar { it.uppercase() }
                        scope.launch {
                            try {
                                val books = bibleRepository.getBooks()
                                val bookInfo = books.find { it.book_id == bookId }
                                maxChapter = bookInfo?.chapters?.maxOrNull() ?: 1
                                currentBookName = bookInfo?.name ?: currentBookName
                                askForChapter(scope, onExit)
                            } catch (e: Exception) {
                                voiceCommandEngine.speakThenListen(
                                    "Error loading book info. Say a command or 'cancel'."
                                ) { retry -> handlePostPlaybackCommand(retry, scope, onExit) }
                            }
                        }
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "I didn't understand '${cmd.text}'. " +
                                "Say 'next', 'repeat', a chapter number, a book name, or 'cancel'."
                        ) { retry -> handlePostPlaybackCommand(retry, scope, onExit) }
                    }
                }
            }
            else -> {
                bibleRepository.stopAudio()
                onExit(cmd)
            }
        }
    }

    /** Stop playback if active (e.g. on wake event or when leaving the app). */
    fun stop() {
        bibleRepository.stopAudio()
    }

    // ── Parsing helpers ───────────────────────────────────────────────────

    /**
     * Chunk text into TTS-friendly pieces. Similar logic used elsewhere in the app.
     */
    private fun chunkTextForTts(text: String, maxChars: Int = 240): List<String> {
        val sentences = Regex("(?<=[.!?])\\s+")
            .split(text.trim())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (s in sentences) {
            if (current.isEmpty()) {
                current.append(s)
            } else if (current.length + 1 + s.length > maxChars) {
                chunks.add(current.toString())
                current = StringBuilder(s)
            } else {
                current.append(" ").append(s)
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    /**
     * Read a list of chunks sequentially using the engine's no-listen chunk reader.
     */
    private fun readChunksSequentially(chunks: List<String>, scope: CoroutineScope, onDone: () -> Unit) {
        if (chunks.isEmpty()) { onDone(); return }
        var idx = 0
        fun speakNext() {
            if (idx >= chunks.size) { onDone(); return }
            voiceCommandEngine.speakEmailChunk(chunks[idx]) {
                idx++
                speakNext()
            }
        }
        speakNext()
    }

    /**
     * Parse a spoken phrase like "Genesis 3", "John chapter 5", "first corinthians 13".
     * Returns (bookId, chapter?) — chapter is null if only a book was spoken.
     */
    private fun parseBookAndChapter(spoken: String): Pair<String?, Int?> {
        val lower = spoken.trim().lowercase()

        // Try to extract trailing number: "genesis 3", "psalm 23"
        val numberAtEnd = Regex("(\\d+)\\s*$").find(lower)
        val chapterStr = lower.replace(Regex("\\bchapter\\b"), "").trim()

        if (numberAtEnd != null) {
            val chapter = numberAtEnd.groupValues[1].toIntOrNull()
            val bookPart = lower.substring(0, numberAtEnd.range.first).trim()
                .replace(Regex("\\bchapter\\b"), "").trim()
            val bookId = bibleRepository.resolveBookId(bookPart)
            if (bookId != null) return Pair(bookId, chapter)
        }

        // No number — just a book name
        val bookId = bibleRepository.resolveBookId(lower)
        return Pair(bookId, null)
    }

    /**
     * Extract a chapter number from speech like "3", "chapter 5", "12".
     */
    private fun extractChapterNumber(text: String): Int? {
        val lower = text.trim().lowercase()
        // Direct number
        lower.toIntOrNull()?.let { return it }
        // "chapter 5"
        Regex("chapter\\s+(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        // Any number in the string
        Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        // Word numbers
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
