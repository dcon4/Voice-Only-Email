package com.example.voicegmail.browser

import com.example.voicegmail.debug.DebugLogger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts readable text content from raw HTML using Jsoup.
 *
 * Uses a readability heuristic approach:
 * 1. Remove known non-content elements (nav, footer, ads, scripts, styles)
 * 2. Look for semantic article/main elements
 * 3. Fall back to largest text-dense block
 * 4. Clean and format for TTS consumption
 */
@Singleton
class PageExtractor @Inject constructor() {

    private val tag = "PageExtractor"

    /**
     * Elements to remove before content extraction — these are never useful content.
     */
    private val removeSelectors = listOf(
        "script", "style", "noscript", "iframe", "svg", "canvas",
        "nav", "header", "footer", "aside",
        "form", "button", "input", "select", "textarea",
        "[role=navigation]", "[role=banner]", "[role=contentinfo]",
        "[aria-hidden=true]",
        ".nav", ".navbar", ".menu", ".sidebar", ".footer", ".header",
        ".ad", ".ads", ".advertisement", ".social", ".share",
        ".cookie", ".popup", ".modal", ".overlay",
        ".comments", ".comment-section", "#comments",
        ".related", ".recommended", ".trending"
    )

    /**
     * Extract readable text from HTML.
     * Returns the page title and extracted body text.
     */
    fun extract(html: String, url: String): ExtractedPage {
        val doc = Jsoup.parse(html, url)
        val title = doc.title().takeIf { it.isNotBlank() } ?: "Untitled page"

        // Remove non-content elements
        for (selector in removeSelectors) {
            doc.select(selector).remove()
        }

        // Try semantic content containers in priority order
        val contentText = extractFromSemanticElements(doc)
            ?: extractFromLargestBlock(doc)
            ?: extractAllParagraphs(doc)

        val cleaned = cleanForTts(contentText)
        val truncated = if (cleaned.length > WebBrowserConfig.MAX_READ_CHARS) {
            cleaned.take(WebBrowserConfig.MAX_READ_CHARS) + "... Content truncated."
        } else {
            cleaned
        }

        DebugLogger.log(tag, "Extracted ${truncated.length} chars from $url (title: $title)")
        return ExtractedPage(title = title, text = truncated, url = url)
    }

    /**
     * Try to extract from <article>, <main>, [role=main], or #content elements.
     */
    private fun extractFromSemanticElements(doc: Document): String? {
        val candidates = listOf(
            "article", "main", "[role=main]", "#content", "#article",
            ".article", ".post-content", ".entry-content", ".article-body",
            ".story-body", ".content-body"
        )
        for (selector in candidates) {
            val el = doc.selectFirst(selector)
            if (el != null) {
                val text = el.text().trim()
                if (text.length > 200) return text // Reasonable content threshold
            }
        }
        return null
    }

    /**
     * Find the largest text-dense element in the body.
     * Heuristic: score elements by text length vs child count ratio.
     */
    private fun extractFromLargestBlock(doc: Document): String? {
        val body = doc.body() ?: return null
        var bestElement: Element? = null
        var bestScore = 0

        // Score div/section/td elements by text density
        for (el in body.select("div, section, td")) {
            val text = el.ownText().length + el.select("p").sumOf { it.text().length }
            val links = el.select("a").size
            // Penalize link-heavy blocks (likely navigation)
            val score = text - (links * 30)
            if (score > bestScore) {
                bestScore = score
                bestElement = el
            }
        }

        return bestElement?.text()?.trim()?.takeIf { it.length > 100 }
    }

    /**
     * Fallback: concatenate all <p> elements.
     */
    private fun extractAllParagraphs(doc: Document): String {
        val paragraphs = doc.select("p")
            .map { it.text().trim() }
            .filter { it.length > 20 } // Skip tiny fragments
        return paragraphs.joinToString(". ")
    }

    /**
     * Clean extracted text for TTS readability.
     */
    private fun cleanForTts(text: String): String {
        return text
            // Collapse whitespace
            .replace(Regex("\\s+"), " ")
            // Remove common non-speech artifacts
            .replace(Regex("\\[\\d+\\]"), "") // footnote references [1]
            .replace(Regex("\\{[^}]*\\}"), "") // CSS/JSON fragments
            .replace(Regex("<[^>]*>"), "") // any residual HTML tags
            // Fix spacing around punctuation
            .replace(Regex("\\s+\\."), ".")
            .replace(Regex("\\s+,"), ",")
            .trim()
    }
}

/**
 * Represents a page that has been fetched and extracted for reading.
 */
data class ExtractedPage(
    val title: String,
    val text: String,
    val url: String
)
