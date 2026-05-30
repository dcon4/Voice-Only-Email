package com.example.voicegmail.browser

/**
 * Configuration for the in-app voice browser module.
 *
 * This module fetches web pages via OkHttp, parses them with Jsoup,
 * and reads content aloud via TTS. No WebView, no JavaScript execution.
 */
object WebBrowserConfig {
    /**
     * DuckDuckGo HTML search URL template.
     * We use the HTML version and parse results with Jsoup.
     * The `{query}` placeholder is URL-encoded at call time.
     */
    const val SEARCH_URL_TEMPLATE = "https://html.duckduckgo.com/html/?q={query}"

    /** User-Agent sent with all requests (mimics a simple browser). */
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36 VoiceGmail/1.0"

    /** Maximum number of search results to fetch from search engine. */
    const val MAX_SEARCH_RESULTS = 20

    /** Maximum page body size to fetch (2 MB). Prevents OOM on huge pages. */
    const val MAX_PAGE_BYTES = 2 * 1024 * 1024

    /** Timeout for fetching a page, in seconds. */
    const val PAGE_FETCH_TIMEOUT_SECONDS = 15L

    /** Maximum characters of extracted text to read aloud per page. */
    const val MAX_READ_CHARS = 15_000

    /** Schemes we allow. Block file://, javascript:, data:, etc. */
    val ALLOWED_SCHEMES = setOf("https")
}
