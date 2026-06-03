package com.example.voicegmail.bible

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BibleRepository"

/**
 * Manages Bible Brain API calls and MediaPlayer-based audio streaming.
 * Provides a voice-driven flow: select book → chapter → play audio.
 */
@Singleton
class BibleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: BibleBrainApiService
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    // Cached state
    private var cachedBooks: List<BookInfo>? = null
    private var otFilesetId: String? = null
    private var ntFilesetId: String? = null

    /** True if a Bible Brain API key is configured. */
    fun isConfigured(): Boolean = BibleBrainConfig.API_KEY.isNotBlank()

    // ── Book list ─────────────────────────────────────────────────────────

    /**
     * Fetches available books. Resolves the fileset IDs on first call.
     * Returns the combined OT + NT book list.
     */
    suspend fun getBooks(): List<BookInfo> {
        cachedBooks?.let { return it }

        // First resolve the audio fileset IDs from the Bible metadata
        if (otFilesetId == null && ntFilesetId == null) {
            resolveFilesetIds()
        }

        val books = mutableListOf<BookInfo>()
        otFilesetId?.let { id ->
            val resp = api.getBooks(id, BibleBrainConfig.API_KEY)
            resp.data?.let { books.addAll(it) }
        }
        ntFilesetId?.let { id ->
            val resp = api.getBooks(id, BibleBrainConfig.API_KEY)
            resp.data?.let { books.addAll(it) }
        }

        cachedBooks = books
        DebugLogger.log(TAG, "Loaded ${books.size} books")
        return books
    }

    /**
     * Find the best audio fileset IDs (OT and NT) for the default Bible.
     */
    private suspend fun resolveFilesetIds() {
        val bibleResp = api.getBible(BibleBrainConfig.DEFAULT_BIBLE_ID, BibleBrainConfig.API_KEY)
        val filesets = bibleResp.data?.filesets?.values?.flatten() ?: emptyList()

        DebugLogger.log(TAG, "Available filesets: ${filesets.map { "${it.id}(${it.type},${it.size})" }}")

        // Prefer drama audio, fall back to plain audio
        fun findFileset(size: String): String? {
            return filesets.firstOrNull {
                it.type == BibleBrainConfig.PREFERRED_AUDIO_TYPE && (it.size == size || it.size == "C")
            }?.id ?: filesets.firstOrNull {
                it.type == BibleBrainConfig.FALLBACK_AUDIO_TYPE && (it.size == size || it.size == "C")
            }?.id
        }

        otFilesetId = findFileset("OT") ?: findFileset("C")
        ntFilesetId = findFileset("NT") ?: findFileset("C")

        DebugLogger.log(TAG, "Resolved filesets — OT=$otFilesetId NT=$ntFilesetId")
    }

    // ── Chapter audio ─────────────────────────────────────────────────────

    /**
     * Get the audio URL for a given book and chapter.
     * Returns null if the chapter/book is not available.
     */
    suspend fun getChapterAudioUrl(bookId: String, chapter: Int): String? {
        val filesetId = getFilesetForBook(bookId) ?: return null
        val resp = api.getChapterAudio(filesetId, bookId, chapter, BibleBrainConfig.API_KEY)
        val audioFile = resp.data?.firstOrNull { it.path != null }
        return audioFile?.path
    }

    /**
     * Determine which fileset (OT or NT) contains this book.
     */
    private suspend fun getFilesetForBook(bookId: String): String? {
        val books = getBooks()
        // Check OT first
        if (otFilesetId != null) {
            val otBooks = api.getBooks(otFilesetId!!, BibleBrainConfig.API_KEY)
            if (otBooks.data?.any { it.book_id == bookId } == true) return otFilesetId
        }
        if (ntFilesetId != null) {
            val ntBooks = api.getBooks(ntFilesetId!!, BibleBrainConfig.API_KEY)
            if (ntBooks.data?.any { it.book_id == bookId } == true) return ntFilesetId
        }
        // Fallback: try either one
        return otFilesetId ?: ntFilesetId
    }

    // ── Audio playback ────────────────────────────────────────────────────

    /**
     * Stream and play the audio from the given URL.
     * Calls [onComplete] when playback finishes or [onError] on failure.
     */
    fun playAudio(url: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        stopAudio()
        DebugLogger.log(TAG, "Playing audio: ${url.take(80)}...")

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { mp ->
                    mp.start()
                    this@BibleRepository.isPlaying = true
                    DebugLogger.log(TAG, "Playback started")
                }
                setOnCompletionListener {
                    this@BibleRepository.isPlaying = false
                    DebugLogger.log(TAG, "Playback complete")
                    mainHandler.post(onComplete)
                }
                setOnErrorListener { _, what, extra ->
                    this@BibleRepository.isPlaying = false
                    val msg = "MediaPlayer error: what=$what extra=$extra"
                    Log.e(TAG, msg)
                    DebugLogger.log(TAG, msg)
                    mainHandler.post { onError(msg) }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            val msg = "Failed to start audio: ${e.message}"
            Log.e(TAG, msg, e)
            DebugLogger.log(TAG, msg)
            onError(msg)
        }
    }

    /** Stop any currently playing audio. */
    fun stopAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.reset()
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
    }

    /** Whether audio is currently playing. */
    fun isAudioPlaying(): Boolean = isPlaying

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Map of common spoken book names to their Bible Brain book IDs.
     * Covers all 66 books of the Protestant Bible.
     */
    fun resolveBookId(spokenName: String): String? {
        val lower = spokenName.trim().lowercase()
        return BOOK_NAME_MAP.entries.firstOrNull { (key, _) ->
            lower == key || lower.startsWith(key) || key.startsWith(lower)
        }?.value
    }

    companion object {
        /**
         * Mapping of spoken/common book names → Bible Brain book_id codes.
         */
        val BOOK_NAME_MAP: Map<String, String> = linkedMapOf(
            // Old Testament
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
            // New Testament
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
