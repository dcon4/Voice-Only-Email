package com.example.voicegmail.browser

import com.example.voicegmail.debug.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates web search and page content extraction.
 *
 * This is the data layer for the voice browser module:
 * - Delegates search to [WebSearchService]
 * - Fetches page HTML via OkHttp (HTTPS only, size-limited)
 * - Passes HTML through [PageExtractor] for readability extraction
 */
@Singleton
class BrowserRepository @Inject constructor(
    private val searchService: WebSearchService,
    private val pageExtractor: PageExtractor,
    private val okHttpClient: OkHttpClient
) {
    private val tag = "BrowserRepository"

    /** Current search results (kept for navigation within the voice flow). */
    var lastResults: List<SearchResult> = emptyList()
        private set

    /** Last extracted page (for re-reading or continuation). */
    var lastPage: ExtractedPage? = null
        private set

    /**
     * Perform a web search. Returns results and caches them.
     */
    suspend fun search(query: String): List<SearchResult> {
        val results = searchService.search(query)
        lastResults = results
        return results
    }

    /**
     * Fetch and extract readable content from a URL.
     * Returns null on failure (network error, non-HTTPS, too large, etc.).
     */
    suspend fun fetchPage(url: String): ExtractedPage? {
        // Security: only allow HTTPS
        if (!url.startsWith("https://")) {
            DebugLogger.log(tag, "Blocked non-HTTPS URL: $url")
            return null
        }

        DebugLogger.log(tag, "Fetching page: $url")

        return try {
            withContext(Dispatchers.IO) {
                val client = okHttpClient.newBuilder()
                    .callTimeout(WebBrowserConfig.PAGE_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", WebBrowserConfig.USER_AGENT)
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                // Check content length if available
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                if (contentLength > WebBrowserConfig.MAX_PAGE_BYTES) {
                    DebugLogger.log(tag, "Page too large: $contentLength bytes")
                    response.close()
                    return@withContext null
                }

                // Only process text/html content
                val contentType = response.header("Content-Type") ?: ""
                if (!contentType.contains("text/html") && !contentType.contains("text/plain")) {
                    DebugLogger.log(tag, "Non-HTML content type: $contentType")
                    response.close()
                    return@withContext null
                }

                val body = response.body?.let { responseBody ->
                    val bytes = responseBody.bytes()
                    if (bytes.size > WebBrowserConfig.MAX_PAGE_BYTES) {
                        DebugLogger.log(tag, "Page body exceeds limit: ${bytes.size} bytes")
                        return@withContext null
                    }
                    String(bytes, Charsets.UTF_8)
                } ?: return@withContext null

                val page = pageExtractor.extract(body, url)
                lastPage = page
                page
            }
        } catch (e: Exception) {
            DebugLogger.log(tag, "Fetch failed for $url: ${e.message}")
            null
        }
    }

    /** Clear cached state. */
    fun clear() {
        lastResults = emptyList()
        lastPage = null
    }
}
