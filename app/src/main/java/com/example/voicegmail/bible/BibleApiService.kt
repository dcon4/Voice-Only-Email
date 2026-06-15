package com.example.voicegmail.bible

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

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
     * [ref] must use the book NAME (not ID) with + for spaces, e.g. "John+3:16".
     * Translation is passed as a query parameter, e.g. /John+3:16?translation=asv
     */
    @GET("{ref}")
    suspend fun getVerse(
        @Path(value = "ref", encoded = true) ref: String,
        @Query("translation") translation: String
    ): ChapterResponse
}

data class BooksResponse(val books: List<BibleBookInfo>)
data class BibleBookInfo(val id: String, val name: String)

data class ChaptersResponse(val chapters: List<ChapterInfo>)
data class ChapterInfo(val book_id: String, val chapter: Int)

data class ChapterResponse(val verses: List<VerseInfo>)
data class VerseInfo(
    val book_id: String,
    @SerializedName(value = "book", alternate = ["book_name"])
    val book: String,
    val chapter: Int,
    val verse: Int,
    val text: String
)
