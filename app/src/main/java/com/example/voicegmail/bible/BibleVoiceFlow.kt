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
 * Voice-driven Bible reading flow using bible-api.com text + TTS.
 *
 * No API key required. Reads each chapter as TTS-synthesized speech.
 * Supports separate Bible voice via [VoiceManager.bibleVoiceName].
 *
 * Flow:
 * 1. Ask which book
 * 2. Ask which chapter
 * 3. Fetch chapter text from bible-api.com and read via TTS
 * 4. On completion, offer next chapter / repeat / cancel
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

    /**
     * Entry point — start the Bible voice menu.
     * [scope] is used for suspend calls (API fetches).
     * [onExit] is called when the user leaves the Bible flow.
     */
    fun start(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        DebugLogger.log(TAG, "Bible flow started")

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
                bibleRepository.stopReading()
                onExit(VoiceCommand.None)
            }
            is VoiceCommand.GoToSleep -> {
                bibleRepository.stopReading()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                bibleRepository.stopReading()
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

                scope.launch {
                    try {
                        maxChapter = bibleRepository.getMaxChapter(bookId)
                        val books = bibleRepository.getBooks()
                        val bookInfo = books.find { it.id == bookId }
                        currentBookName = bookInfo?.name ?: currentBookName

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
                bibleRepository.stopReading()
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
                bibleRepository.stopReading()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                bibleRepository.stopReading()
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
                    readCurrentChapter(scope, onExit)
                }
            }
            else -> {
                bibleRepository.stopReading()
                onExit(cmd)
            }
        }
    }

    // ── TTS Reading ────────────────────────────────────────────────────────

    private fun readCurrentChapter(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val bookId = currentBookId ?: return
        val bookName = currentBookName ?: return

        voiceManager.speak("Reading $bookName, chapter $currentChapter.")

        scope.launch {
            try {
                val chapterText = bibleRepository.getChapterText(bookId, currentChapter, bookName)
                bibleRepository.speakChapter(chapterText) {
                    handleReadingComplete(scope, onExit)
                }
            } catch (e: Exception) {
                DebugLogger.log(TAG, "Error fetching chapter text: ${e.message}")
                voiceCommandEngine.speakThenListen(
                    "Error loading chapter. ${e.message ?: "Unknown error."}. " +
                        "Say 'try again', another chapter, or 'cancel'."
                ) { cmd -> handlePostReadingCommand(cmd, scope, onExit) }
            }
        }
    }

    private fun handleReadingComplete(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
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
            handlePostReadingCommand(cmd, scope, onExit)
        }
    }

    private fun handlePostReadingCommand(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
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
                bibleRepository.stopReading()
                voiceCommandEngine.speakThenListen(
                    "Leaving Bible mode. Say a command."
                ) { exitCmd -> onExit(exitCmd) }
            }
            is VoiceCommand.GoToSleep -> {
                bibleRepository.stopReading()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                bibleRepository.stopReading()
                onExit(cmd)
            }
            is VoiceCommand.FreeText -> {
                val chapter = extractChapterNumber(cmd.text)
                if (chapter != null && chapter in 1..maxChapter) {
                    currentChapter = chapter
                    readCurrentChapter(scope, onExit)
                } else {
                    val bookId = bibleRepository.resolveBookId(cmd.text)
                    if (bookId != null) {
                        currentBookId = bookId
                        currentBookName = cmd.text.replaceFirstChar { it.uppercase() }
                        scope.launch {
                            try {
                                val books = bibleRepository.getBooks()
                                val bookInfo = books.find { it.id == bookId }
                                maxChapter = bookInfo?.let { bibleRepository.getMaxChapter(it.id) } ?: 1
                                currentBookName = books.find { it.id == bookId }?.name ?: currentBookName
                                askForChapter(scope, onExit)
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
                bibleRepository.stopReading()
                onExit(cmd)
            }
        }
    }

    /** Stop reading if active (e.g. on wake event or when leaving the app). */
    fun stop() {
        bibleRepository.stopReading()
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
