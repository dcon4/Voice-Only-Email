package com.example.voicegmail.bible

import android.util.Log
import com.example.voicegmail.debug.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val TAG = "BibleTextRepository"

/**
 * Fetch Bible passage text from bible-api.com.
 *
 * Simple, self-contained repository using Retrofit. Exposes suspend function
 * getPassageText("John 3:16") → String? (null on error).
 */
@Singleton
class BibleTextRepository @Inject constructor() {

    // Create a private Retrofit instance for bible-api.com
    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://bible-api.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service: BibleApiService = retrofit.create(BibleApiService::class.java)

    /**
     * Returns the passage plain text, or null on failure.
     * [reference] examples: "John 3:16", "Psalms 23", "Romans 8:28-30"
     */
    suspend fun getPassageText(reference: String): String? {
        if (reference.isBlank()) return null
        // Encode reference for URL path usage (spaces -> %20, # -> %23, etc.)
        val encoded = URLEncoder.encode(reference.trim(), StandardCharsets.UTF_8.toString())
        return try {
            withContext(Dispatchers.IO) {
                val resp = service.getPassage(encoded)
                val text = resp.text
                DebugLogger.log(TAG, "Fetched passage for '$reference' length=${text?.length ?: 0}")
                text?.trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch passage: ${e.message}", e)
            null
        }
    }
}
