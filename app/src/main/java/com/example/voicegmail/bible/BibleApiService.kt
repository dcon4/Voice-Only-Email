package com.example.voicegmail.bible

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Minimal Retrofit service for https://bible-api.com/
 * Example request: GET https://bible-api.com/John%203:16
 *
 * The path is provided encoded (spaces encoded as %20) so we mark encoded=true.
 */
interface BibleApiService {
    @GET("{reference}")
    suspend fun getPassage(@Path(value = "reference", encoded = true) reference: String): BibleApiResponse
}

data class BibleApiResponse(
    val text: String?,          // the passage text returned by bible-api
    val reference: String? = null,
    val verses: List<Any>? = null,  // unused, present for robustness
    val translation_id: String? = null
)
