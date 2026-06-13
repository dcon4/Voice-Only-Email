package com.example.voicegmail.bible

import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.voice.VoiceManager
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BibleRepository"
private const val DEFAULT_TRANSLATION = "web"

/**
 * Fetches Bible text from bible-api.com and reads it aloud via TTS.
 * No API key required — rate limited to 15 requests per 30 seconds.
 */
@Singleton
class BibleRepository @Inject constructor(
    private val api: BibleApiService,
    private val voiceManager: VoiceManager
) {
    var translation: String = DEFAULT_TRANSLATION

    // ── Book list ─────────────────────────────────────────────────────────

    private var cachedBooks: List<BibleBookInfo>? = null

    suspend fun getBooks(): List<BibleBookInfo> {
        cachedBooks?.let { return it }
        val resp = api.getBooks(translation)
        cachedBooks = resp.books
        DebugLogger.log(TAG, "Loaded ${resp.books.size} books")
        return resp.books
    }

    // ── Chapter list ──────────────────────────────────────────────────────

    private var cachedChapters: MutableMap<String, List<Int>> = mutableMapOf()

    suspend fun getChapters(bookId: String): List<Int> {
        cachedChapters[bookId]?.let { return it }
        val resp = api.getChapters(translation, bookId)
        val chapters = resp.chapters.map { it.chapter }
        cachedChapters[bookId] = chapters
        return chapters
    }

    suspend fun getMaxChapter(bookId: String): Int {
        val chapters = getChapters(bookId)
        return chapters.maxOrNull() ?: 1
    }

    // ── Chapter text + TTS reading ────────────────────────────────────────

    /**
     * Fetches all verses for [bookId] chapter [chapter] and returns them as
     * a single formatted string suitable for TTS.
     */
    suspend fun getChapterText(bookId: String, chapter: Int, bookName: String): String {
        val resp = api.getChapter(translation, bookId, chapter)
        val sb = StringBuilder()
        sb.append("$bookName chapter $chapter. ")
        for (v in resp.verses) {
            sb.append("Verse ${v.verse}: ${v.text.trim()} ")
        }
        return sb.toString().replace(Regex("\\s+"), " ")
    }

    /**
     * Speaks [text] via TTS and invokes [onDone] when complete.
     * Uses the Bible voice if one has been configured.
     */
    fun speakChapter(text: String, onDone: () -> Unit) {
        if (!voiceManager.isTtsReady()) {
            DebugLogger.log(TAG, "TTS not ready")
            onDone()
            return
        }
        val bibleVoice = voiceManager.bibleVoiceName
        if (bibleVoice.isNotBlank()) {
            voiceManager.speakWithVoice(text, bibleVoice, onDone)
        } else {
            voiceManager.speakInChunks(text, 1000, onDone)
        }
    }

    /** Cancel any ongoing TTS Bible reading. */
    fun stopReading() {
        voiceManager.stopTts()
    }

    // ── Book name resolution ──────────────────────────────────────────────

    fun resolveBookId(spokenName: String): String? {
        val lower = spokenName.trim().lowercase()
        return BOOK_NAME_MAP.entries.firstOrNull { (key, _) ->
            lower == key || lower.startsWith(key) || key.startsWith(lower)
        }?.value
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
