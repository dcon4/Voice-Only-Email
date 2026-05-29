package com.example.voicegmail.browser

import com.example.voicegmail.debug.DebugLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search result returned from DuckDuckGo HTML parsing.
 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Performs web searches via DuckDuckGo's HTML endpoint and parses
 * results using Jsoup. No API key required.
 *
 * This is intentionally a thin layer — it fetches the DDG HTML page,
 * extracts result links/titles/snippets, and returns them as data objects.
 */
@Singleton
class WebSearchService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val tag = "WebSearchService"

    /**
     * Search DuckDuckGo and return up to [WebBrowserConfig.MAX_SEARCH_RESULTS] results.
     * Returns an empty list on failure (network error, parse error, etc.).
     */
    suspend fun search(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = WebBrowserConfig.SEARCH_URL_TEMPLATE.replace("{query}", encoded)

        DebugLogger.log(tag, "Searching: '$query'")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WebBrowserConfig.USER_AGENT)
            .get()
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()

            val results = parseDuckDuckGoHtml(body)
            DebugLogger.log(tag, "Found ${results.size} results for '$query'")
            results.take(WebBrowserConfig.MAX_SEARCH_RESULTS)
        } catch (e: Exception) {
            DebugLogger.log(tag, "Search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse DuckDuckGo HTML search results page.
     * DDG uses <a class="result__a"> for titles and <a class="result__snippet"> for snippets.
     */
    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()

        // DuckDuckGo HTML results are in <div class="result results_links results_links_deep web-result">
        val resultElements = doc.select("div.result")

        for (element in resultElements) {
            val linkEl = element.selectFirst("a.result__a") ?: continue
            val title = linkEl.text().trim()
            var href = linkEl.attr("href").trim()

            // DDG sometimes wraps URLs in a redirect — extract the actual URL
            if (href.contains("uddg=")) {
                val decoded = java.net.URLDecoder.decode(
                    href.substringAfter("uddg=").substringBefore("&"), "UTF-8"
                )
                href = decoded
            }

            // Only allow HTTPS URLs
            if (!href.startsWith("https://")) continue

            val snippetEl = element.selectFirst("a.result__snippet")
                ?: element.selectFirst("td.result__snippet")
            val snippet = snippetEl?.text()?.trim() ?: ""

            if (title.isNotBlank() && href.isNotBlank()) {
                results.add(SearchResult(title = title, url = href, snippet = snippet))
            }
        }

        return results
    }
}
