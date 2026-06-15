package com.example.voicegmail.bible

import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.voice.TtsSettingsRepository
import com.example.voicegmail.voice.VoiceManager
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BibleRepository"

/**
 * Fetches Bible text from bible-api.com and reads it aloud via TTS.
 * Supports offline cache via [BibleOfflineStorage].
 */
@Singleton
class BibleRepository @Inject constructor(
    private val api: BibleApiService,
    private val voiceManager: VoiceManager,
    private val ttsSettings: TtsSettingsRepository,
    private val offlineStorage: BibleOfflineStorage
) {
    private val gson = Gson()

    // ── Book list ─────────────────────────────────────────────────────────

    private var cachedBooks: MutableMap<String, List<BibleBookInfo>> = mutableMapOf()

    suspend fun getBooks(): List<BibleBookInfo> {
        val trans = ttsSettings.getBibleTranslation()
        cachedBooks[trans]?.let { return it }
        val resp = api.getBooks(trans)
        cachedBooks[trans] = resp.books
        DebugLogger.log(TAG, "Loaded ${resp.books.size} books (translation=$trans)")
        return resp.books
    }

    // ── Chapter list ──────────────────────────────────────────────────────

    private var cachedChapters: MutableMap<String, List<Int>> = mutableMapOf()

    suspend fun getChapters(bookId: String): List<Int> {
        val trans = ttsSettings.getBibleTranslation()
        val key = "$trans:$bookId"
        cachedChapters[key]?.let { return it }
        val resp = api.getChapters(trans, bookId)
        val chapters = resp.chapters.map { it.chapter }
        cachedChapters[key] = chapters
        return chapters
    }

    suspend fun getMaxChapter(bookId: String): Int {
        val chapters = getChapters(bookId)
        return chapters.maxOrNull() ?: 1
    }

    // ── Chapter text ──────────────────────────────────────────────────────

    private val chapterTextCache = mutableMapOf<String, String>()

    suspend fun getChapterText(bookId: String, chapter: Int, bookName: String): String {
        val trans = ttsSettings.getBibleTranslation()
        val cacheKey = "$trans/$bookId/$chapter"

        chapterTextCache[cacheKey]?.let { return it }

        // Try disk cache
        val cached = offlineStorage.readChapter(trans, bookId, chapter)
        if (cached != null) {
            try {
                val resp = gson.fromJson(cached, ChapterResponse::class.java)
                val text = formatChapterText(resp, bookName, chapter)
                chapterTextCache[cacheKey] = text
                return text
            } catch (e: Exception) {
                DebugLogger.log(TAG, "Error parsing cached chapter: ${e.message}")
            }
        }

        // Fetch from API
        val resp = api.getChapter(trans, bookId, chapter)
        offlineStorage.saveChapter(trans, bookId, chapter, gson.toJson(resp))

        val text = formatChapterText(resp, bookName, chapter)
        chapterTextCache[cacheKey] = text
        return text
    }

    private fun formatChapterText(resp: ChapterResponse, bookName: String, chapter: Int): String {
        val sb = StringBuilder()
        sb.append("$bookName chapter $chapter. ")
        val showVerseNumbers = ttsSettings.getBibleVerseNumbers()
        for (v in resp.verses) {
            if (showVerseNumbers) {
                sb.append("${v.verse} ")
            }
            sb.append("${v.text.trim()} ")
        }
        return sb.toString().replace(Regex("\\s+"), " ")
    }

    // ── Verse text ─────────────────────────────────────────────────────────

    suspend fun getVerseText(bookName: String, chapter: Int, verse: Int): String {
        val trans = ttsSettings.getBibleTranslation()
        val ref = bookName.replace(" ", "+") + "+$chapter:$verse"
        try {
            val resp = api.getVerse(trans, ref)
            return resp.verses.firstOrNull()?.text?.trim() ?: "Verse $verse not found."
        } catch (e: Exception) {
            // Fall back to cached chapter data
            val bookId = resolveBookId(bookName.lowercase())
            if (bookId != null) {
                val cached = offlineStorage.readChapter(trans, bookId, chapter)
                if (cached != null) {
                    try {
                        val resp = gson.fromJson(cached, ChapterResponse::class.java)
                        return resp.verses.find { it.verse == verse }?.text?.trim()
                            ?: "Verse $verse not found."
                    } catch (parseError: Exception) {
                        DebugLogger.log(TAG, "Error parsing cached chapter for verse: ${parseError.message}")
                    }
                }
            }
            throw e
        }
    }

    // ── TTS reading ────────────────────────────────────────────────────────

    fun speakChapter(text: String, onDone: () -> Unit) {
        if (!voiceManager.isTtsReady()) {
            DebugLogger.log(TAG, "TTS not ready")
            onDone()
            return
        }
        val bibleVoice = voiceManager.bibleVoiceName
        if (bibleVoice.isNotBlank()) {
            voiceManager.speakInChunksWithVoice(text, bibleVoice, 1000, onDone)
        } else {
            voiceManager.speakInChunks(text, 1000, onDone)
        }
    }

    fun stopReading() {
        voiceManager.stopTts()
    }

    // ── Offline helpers ────────────────────────────────────────────────────

    /** Check if a chapter is available offline. */
    fun isChapterOffline(translation: String, bookId: String, chapter: Int): Boolean {
        return offlineStorage.isChapterCached(translation, bookId, chapter)
    }

    /** Count how many chapters are cached for a translation. */
    fun countCachedChapters(translation: String): Int {
        return offlineStorage.countCachedChapters(translation)
    }

    // ── Book name resolution ──────────────────────────────────────────────

    fun tryParseVerseReference(text: String): Triple<String?, Int?, Int?> {
        val lower = text.trim().lowercase()
            .replace(":", " ")
            .replace(Regex("\\bchapter\\b"), "")
            .replace(Regex("\\bverse\\b"), "")
            .trim()
        val words = lower.split("\\s+".toRegex()).filter { it.isNotBlank() }
        DebugLogger.verbose(TAG, "tryParseVerseReference: input='$text' words=$words")
        if (words.isEmpty()) return Triple(null, null, null)

        for (bookWordCount in minOf(words.size - 1, 3) downTo 1) {
            val bookParts = words.take(bookWordCount).joinToString(" ")
            val bookId = resolveBookId(bookParts)
            if (bookId != null) {
                val numbers = words.drop(bookWordCount).mapNotNull { it.toIntOrNull() }
                val chapter = numbers.getOrNull(0)
                val verse = numbers.getOrNull(1)
                DebugLogger.verbose(TAG, "tryParseVerseReference: match bookId=$bookId (bookParts='$bookParts') ch=$chapter v=$verse")
                return Triple(bookId, chapter, verse)
            }
        }
        DebugLogger.verbose(TAG, "tryParseVerseReference: no match for '$text'")
        return Triple(null, null, null)
    }

    /** Extract just the book name from a reference like "John 3:16" -> "John". */
    fun extractBookName(text: String): String? {
        val words = text.trim().lowercase()
            .replace(":", " ")
            .replace(Regex("\\bchapter\\b"), "")
            .replace(Regex("\\bverse\\b"), "")
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        for (bookWordCount in minOf(words.size - 1, 3) downTo 1) {
            val bookParts = words.take(bookWordCount).joinToString(" ")
            if (resolveBookId(bookParts) != null) {
                return bookParts.replaceFirstChar { it.uppercase() }
            }
        }
        return null
    }

    fun isBibleReference(text: String): Boolean {
        val (bookId, _, _) = tryParseVerseReference(text)
        return bookId != null
    }

    fun resolveBookId(spokenName: String): String? {
        val lower = spokenName.trim().lowercase()
        return BOOK_NAME_MAP.entries.firstOrNull { (key, _) ->
            lower == key || lower == key.replace(" ", "")
        }?.value
    }

    /**
     * Look up a book by its display name (e.g. "Genesis", "John") in the
     * API's book list for the current translation.  Returns the translation-
     * specific book ID (which may differ from BOOK_NAME_MAP entries).
     */
    suspend fun resolveApiBookId(spokenName: String): Pair<String, String>? {
        val books = getBooks()
        // Extract just the book name part (e.g. "Matthew 5" -> "matthew", "Genesis" -> "genesis")
        val cleanName = extractBookName(spokenName)?.lowercase()
            ?: spokenName.trim().lowercase()
        // Match by name in the API list (case-insensitive)
        val match = books.find { it.name.lowercase() == cleanName }
        if (match != null) return match.id to match.name
        // Fallback: try BOOK_NAME_MAP then look up by ID
        val bookId = resolveBookId(spokenName)
        if (bookId != null) {
            val byId = books.find { it.id == bookId }
            if (byId != null) return byId.id to byId.name
        }
        return null
    }

    companion object {
        val BOOK_NAME_MAP: Map<String, String> = linkedMapOf(
            "genesis" to "GEN", "gen" to "GEN",
            "exodus" to "EXO", "exo" to "EXO",
            "leviticus" to "LEV", "lev" to "LEV",
            "numbers" to "NUM", "num" to "NUM",
            "deuteronomy" to "DEU", "deut" to "DEU",
            "joshua" to "JOS", "josh" to "JOS",
            "judges" to "JDG",
            "ruth" to "RUT",
            "1 samuel" to "1SA", "first samuel" to "1SA",
            "2 samuel" to "2SA", "second samuel" to "2SA",
            "1 kings" to "1KI", "first kings" to "1KI",
            "2 kings" to "2KI", "second kings" to "2KI",
            "1 chronicles" to "1CH", "first chronicles" to "1CH",
            "2 chronicles" to "2CH", "second chronicles" to "2CH",
            "ezra" to "EZR",
            "nehemiah" to "NEH",
            "esther" to "EST",
            "job" to "JOB",
            "psalms" to "PSA", "psalm" to "PSA",
            "proverbs" to "PRO",
            "ecclesiastes" to "ECC",
            "song of solomon" to "SNG", "song of songs" to "SNG",
            "isaiah" to "ISA",
            "jeremiah" to "JER",
            "lamentations" to "LAM",
            "ezekiel" to "EZK",
            "daniel" to "DAN",
            "hosea" to "HOS",
            "joel" to "JOL",
            "amos" to "AMO",
            "obadiah" to "OBA",
            "jonah" to "JON",
            "micah" to "MIC",
            "nahum" to "NAM",
            "habakkuk" to "HAB",
            "zephaniah" to "ZEP",
            "haggai" to "HAG",
            "zechariah" to "ZEC",
            "malachi" to "MAL",
            "matthew" to "MAT", "matt" to "MAT",
            "mark" to "MRK",
            "luke" to "LUK",
            "john" to "JHN",
            "acts" to "ACT", "acts of the apostles" to "ACT",
            "romans" to "ROM",
            "1 corinthians" to "1CO", "first corinthians" to "1CO",
            "2 corinthians" to "2CO", "second corinthians" to "2CO",
            "galatians" to "GAL",
            "ephesians" to "EPH",
            "philippians" to "PHP",
            "colossians" to "COL",
            "1 thessalonians" to "1TH", "first thessalonians" to "1TH",
            "2 thessalonians" to "2TH", "second thessalonians" to "2TH",
            "1 timothy" to "1TI", "first timothy" to "1TI",
            "2 timothy" to "2TI", "second timothy" to "2TI",
            "titus" to "TIT",
            "philemon" to "PHM",
            "hebrews" to "HEB",
            "james" to "JAS",
            "1 peter" to "1PE", "first peter" to "1PE",
            "2 peter" to "2PE", "second peter" to "2PE",
            "1 john" to "1JN", "first john" to "1JN",
            "2 john" to "2JN", "second john" to "2JN",
            "3 john" to "3JN", "third john" to "3JN",
            "jude" to "JUD",
            "revelation" to "REV", "revelations" to "REV"
        )
    }
}
