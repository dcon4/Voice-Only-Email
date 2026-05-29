package com.example.voicegmail.bible

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the Bible Brain (Digital Bible Platform v4) REST API.
 * Base URL: https://4.dbt.io/api/
 * Auth: API key passed as query parameter "key" on every request.
 *
 * Reference: https://www.faithcomesbyhearing.com/bible-brain/developer-documentation
 */
interface BibleBrainApiService {

    /**
     * Get detailed information about a specific Bible, including its filesets.
     * Example: /api/bibles/ENGKJV?v=4&key=...
     */
    @GET("bibles/{bible_id}")
    suspend fun getBible(
        @Path("bible_id") bibleId: String,
        @Query("key") apiKey: String,
        @Query("v") version: Int = 4
    ): BibleResponse

    /**
     * List books available in a given fileset.
     * Example: /api/bibles/filesets/ENGKJVO2DA/books?v=4&key=...
     */
    @GET("bibles/filesets/{fileset_id}/books")
    suspend fun getBooks(
        @Path("fileset_id") filesetId: String,
        @Query("key") apiKey: String,
        @Query("v") version: Int = 4
    ): BooksResponse

    /**
     * Get the audio file URLs for a specific book and chapter.
     * Returns a list of audio file objects, one per chapter (or verse-level for some filesets).
     * Example: /api/bibles/filesets/ENGKJVO2DA/GEN/1?v=4&key=...
     */
    @GET("bibles/filesets/{fileset_id}/{book_id}/{chapter}")
    suspend fun getChapterAudio(
        @Path("fileset_id") filesetId: String,
        @Path("book_id") bookId: String,
        @Path("chapter") chapter: Int,
        @Query("key") apiKey: String,
        @Query("v") version: Int = 4
    ): ChapterAudioResponse
}

// ─── Response models ──────────────────────────────────────────────────────────

data class BibleResponse(
    val data: BibleData?
)

data class BibleData(
    val abbr: String?,          // e.g. "ENGKJV"
    val name: String?,          // e.g. "King James Version"
    val filesets: Map<String, List<FilesetInfo>>?  // grouped by type key
)

data class FilesetInfo(
    val id: String?,            // e.g. "ENGKJVO2DA"
    val type: String?,          // e.g. "audio_drama", "audio", "text_plain"
    val size: String?           // e.g. "OT", "NT", "C" (complete)
)

data class BooksResponse(
    val data: List<BookInfo>?
)

data class BookInfo(
    val book_id: String?,       // e.g. "GEN", "EXO", "MAT"
    val name: String?,          // e.g. "Genesis", "Exodus", "Matthew"
    val chapters: List<Int>?    // e.g. [1, 2, 3, ..., 50]
)

/** Wrapper — the API returns { data: [...] } */
typealias ChapterAudioResponse = ChapterAudioWrapper

data class ChapterAudioWrapper(
    val data: List<AudioFile>?
)

data class AudioFile(
    val book_id: String?,
    val book_name: String?,
    val chapter_start: Int?,
    val chapter_end: Int?,
    val verse_start: Int?,
    val verse_end: Int?,
    val path: String?           // Full URL to the MP3/audio file
)
