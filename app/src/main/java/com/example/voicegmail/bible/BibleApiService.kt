package com.example.voicegmail.bible

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Retrofit interface for bible-api.com (no API key needed).
 * Base URL: https://bible-api.com/
 *
 * Reference: https://github.com/wldeh/bible-api
 */
interface BibleApiService {

    @GET("data/{translation}")
    suspend fun getBooks(@Path("translation") translation: String): BooksResponse

    @GET("data/{translation}/{bookId}")
    suspend fun getChapters(
        @Path("translation") translation: String,
        @Path("bookId") bookId: String
    ): ChaptersResponse

    @GET("data/{translation}/{bookId}/{chapter}")
    suspend fun getChapter(
        @Path("translation") translation: String,
        @Path("bookId") bookId: String,
        @Path("chapter") chapter: Int
    ): ChapterResponse

    /**
     * Fetch a single verse.
     * [ref] must be the literal path segment e.g. "3:16" (colon preserved).
     */
    @GET("{translation}/{bookId}/{ref}")
    suspend fun getVerse(
        @Path("translation") translation: String,
        @Path("bookId") bookId: String,
        @Path(value = "ref", encoded = true) ref: String
    ): ChapterResponse
}

data class BooksResponse(val books: List<BibleBookInfo>)
data class BibleBookInfo(val id: String, val name: String)

data class ChaptersResponse(val chapters: List<ChapterInfo>)
data class ChapterInfo(val book_id: String, val chapter: Int)

data class ChapterResponse(val verses: List<VerseInfo>)
data class VerseInfo(
    val book_id: String,
    val book: String,
    val chapter: Int,
    val verse: Int,
    val text: String
)
