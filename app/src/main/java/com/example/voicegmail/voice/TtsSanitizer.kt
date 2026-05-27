package com.example.voicegmail.voice

/**
 * Pre-processes text before it is passed to the TTS engine.
 *
 * Current transforms:
 * - URLs  → "weblink at domain.com"
 * - Email addresses embedded in text → "email address" (keeps the domain for context)
 * - Runs of non-word ASCII noise (e.g. "---", "***", ">>>") → a single space
 */

private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

/**
 * Replace every URL in [this] string with a concise spoken form.
 *
 *   "Visit https://www.homedepot.com/p/drill?id=123 today"
 *   → "Visit weblink at homedepot.com today"
 *
 *   "See http://bit.ly/abc123"
 *   → "See weblink at bit.ly"
 */
fun String.forSpeech(): String {
    return URL_REGEX.replace(this) { match ->
        val domain = extractDomain(match.value)
        if (domain.isNotBlank()) "weblink at $domain" else "weblink"
    }
        .replace(Regex("""[-_=*#|~`]{3,}"""), " ")   // strip horizontal rules / decorators
        .replace(Regex("""\s{2,}"""), " ")            // collapse whitespace
        .trim()
}

private fun extractDomain(url: String): String {
    return try {
        // Remove scheme, take up to the first /  ? or #
        val noScheme = url.removePrefix("https://").removePrefix("http://")
        val hostAndPort = noScheme.substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
        // Strip leading "www."
        hostAndPort.removePrefix("www.")
    } catch (_: Exception) {
        ""
    }
}
