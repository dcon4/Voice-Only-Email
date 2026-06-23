package com.example.voicegmail.contacts

import com.example.voicegmail.gmail.EmailItem
import java.util.Locale

/**
 * Unified contact record used by the voice-driven compose flow.  Originates
 * from either the user's Google Contacts ("people") or from the unique
 * sender addresses we have seen in the locally-fetched Gmail inbox.
 */
data class Contact(
    val displayName: String,
    val email: String,
    val source: Source
) {
    enum class Source { People, Inbox }
}

/**
 * Pure utilities for the voice-driven recipient resolution flow:
 *
 * 1. [parseDictatedEmail] — converts free-form dictation like "john at
 *    gmail dot com" or "j-o-h-n at gmail dot com" into a canonical email
 *    address.  Used as the letter-by-letter fallback when no contact matches.
 *
 * 2. [extractRecipientQuery] — strips leading commands ("send to", "email",
 *    "to") from the recogniser output so the remainder can be used as a
 *    contact-name query.
 *
 * 3. [rankContacts] — returns the best-scoring contacts for a given query
 *    using a combination of exact-substring, token-prefix, and Levenshtein
 *    similarity.  The top-scoring contact's score is exposed so the caller
 *    can decide whether to confirm-then-send vs enumerate-multiple vs
 *    fall back to letter-by-letter dictation.
 *
 * 4. [extractSendersFromInbox] — pulls unique "Name <email>" sender entries
 *    out of the user's inbox.  Used as the inbox-derived contact source.
 *
 * No external dependencies — everything is implemented in pure Kotlin so
 * it's trivially unit-testable.
 */
object ContactMatcher {

    /** Score returned by [rankContacts.score] is in `0..100`. */
    data class ScoredContact(val contact: Contact, val score: Int)

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Removes politeness prefixes like "send to", "email", "to", "compose to"
     * from the raw recogniser text so the remainder is purely the recipient
     * descriptor.  Always returns a trimmed, lower-cased string.
     */
    fun extractRecipientQuery(raw: String): String {
        var s = raw.trim().lowercase(Locale.US)
        val prefixes = listOf(
            "please send to ", "please send it to ", "send it to ", "send to ",
            "send an email to ", "send email to ", "email to ",
            "compose to ", "compose an email to ", "compose email to ",
            "to ", "for "
        )
        for (p in prefixes) {
            if (s.startsWith(p)) {
                s = s.removePrefix(p)
                break
            }
        }
        return s.trim()
    }

    /**
     * Returns the top [limit] contacts that match [rawQuery], scored 0-100.
     * Returns an empty list if nothing scores above [minScore].
     *
     * The query is first passed through [extractRecipientQuery] so callers
     * can pass the raw recogniser text directly.
     */
    fun rankContacts(
        rawQuery: String,
        contacts: List<Contact>,
        limit: Int = 5,
        minScore: Int = 40
    ): List<ScoredContact> {
        val q = extractRecipientQuery(rawQuery)
        if (q.isEmpty() || contacts.isEmpty()) return emptyList()
        return contacts.asSequence()
            .map { ScoredContact(it, scoreContact(q, it)) }
            .filter { it.score >= minScore }
            .sortedWith(
                compareByDescending<ScoredContact> { it.score }
                    // Tie-break: prefer Inbox source (more recent / more
                    // likely to be the person the user means right now).
                    .thenBy { it.contact.source.ordinal }
                    .thenBy { it.contact.displayName.lowercase(Locale.US) }
            )
            .take(limit)
            .toList()
    }

    /**
     * Runs [rankContacts] over EVERY recognizer hypothesis and, in addition,
     * tries to parse each hypothesis as an email address via
     * [parseDictatedEmail] (with phonetic variants of the local-part).
     *
     * This is the parser the Compose To-field actually uses, because Android
     * SpeechRecognizer often gets the right answer as candidate #2 or #3 —
     * not #1.  Without this we'd be throwing away the recogniser's better
     * guesses.
     *
     * Returns the deduplicated best-scoring matches across all candidates.
     */
    fun rankAcrossCandidates(
        candidates: List<String>,
        contacts: List<Contact>,
        limit: Int = 5,
        minScore: Int = 40
    ): List<ScoredContact> {
        if (candidates.isEmpty() || contacts.isEmpty()) return emptyList()
        // Email-keyed accumulator so the same contact reached via several
        // candidates is reported once with its highest score.
        val byEmail = HashMap<String, ScoredContact>()
        for (raw in candidates) {
            if (raw.isBlank()) continue
            // 1. Name match.
            for (sc in rankContacts(raw, contacts, limit = limit, minScore = minScore)) {
                val key = sc.contact.email.lowercase(Locale.US)
                val existing = byEmail[key]
                if (existing == null || sc.score > existing.score) byEmail[key] = sc
            }
            // 2. Email-shaped parse — direct contact lookup + phonetic
            //    variants of the local part to recover from common ASR
            //    confusions (e.g. "decon 2000" → "dcon2000").
            val parsed = parseDictatedEmail(raw) ?: continue
            val local  = parsed.substringBefore('@')
            val domain = parsed.substringAfter('@')
            val variants = phoneticVariants(local).map { "$it@$domain" } + parsed
            for (v in variants) {
                val match = contacts.firstOrNull { it.email.equals(v, ignoreCase = true) }
                if (match != null) {
                    val score = if (v == parsed) 100 else 95
                    val key = match.email.lowercase(Locale.US)
                    val existing = byEmail[key]
                    if (existing == null || score > existing.score) {
                        byEmail[key] = ScoredContact(match, score)
                    }
                }
            }
        }
        return byEmail.values
            .sortedWith(
                compareByDescending<ScoredContact> { it.score }
                    .thenBy { it.contact.source.ordinal }
                    .thenBy { it.contact.displayName.lowercase(Locale.US) }
            )
            .take(limit)
    }

    /**
     * Parses each recogniser candidate as an email address and returns the
     * distinct successful parses.  Used as the "no contact match" fallback —
     * the user picks from the list of literal address parses rather than
     * being asked to spell from scratch.
     */
    fun parseEmailCandidates(candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (raw in candidates) {
            parseDictatedEmail(raw)?.let { seen.add(it) }
        }
        return seen.toList()
    }

    /**
     * Generates phonetic neighbours for a local-part string to recover from
     * common Android SpeechRecognizer confusions.  Best-effort and
     * intentionally small — adding too many variants increases false
     * positives.  Returns a unique list including the original.
     */
    fun phoneticVariants(local: String): List<String> {
        val seeds = LinkedHashSet<String>()
        seeds.add(local)
        seeds.add(local.replace(" ", ""))            // strip word-boundary spaces
        seeds.add(local.replace(Regex("[\\s_.-]+"), "")) // strip all separators

        // Build all combinations of common letter-sound confusions.
        // Each substitution rule: ASR-output → likely intent.
        val substitutions = listOf(
            "decon"  to "dcon",
            "deakon" to "dcon",
            "deekon" to "dcon",
            "deacon" to "dcon",
            "dakon"  to "dcon",
            "deekan" to "dcon",
            "ekon"   to "kon",
            "see"    to "c",
            "sea"    to "c",
            "kay"    to "k",
            "gee"    to "g",
            "bea"    to "b",
            "bee"    to "b",
            "are"    to "r",
            "you"    to "u",
            "why"    to "y",
            "queue"  to "q",
            "double you" to "w",
            "double u"   to "w",
            "ex"     to "x",
            "zee"    to "z",
            "zed"    to "z"
        )
        val expanded = LinkedHashSet<String>(seeds)
        for (seed in seeds) {
            for ((from, to) in substitutions) {
                if (seed.contains(from)) expanded.add(seed.replace(from, to))
            }
        }
        return expanded.toList()
    }

    // NATO phonetic alphabet + common letter-like words → single letter.
    // Used so "alpha bravo charlie at gmail dot com" → "abc@gmail.com".
    private val phoneticLetterMap: Map<String, String> by lazy {
        mapOf(
            "alpha" to "a", "bravo" to "b", "charlie" to "c", "delta" to "d",
            "echo" to "e", "foxtrot" to "f", "golf" to "g", "hotel" to "h",
            "india" to "i", "juliett" to "j", "juliet" to "j", "kilo" to "k",
            "lima" to "l", "mike" to "m", "november" to "n", "oscar" to "o",
            "papa" to "p", "quebec" to "q", "romeo" to "r", "sierra" to "s",
            "tango" to "t", "uniform" to "u", "victor" to "v", "whiskey" to "w",
            "whisky" to "w", "xray" to "x", "x-ray" to "x", "yankee" to "y",
            "zulu" to "z"
        )
    }

    // Common English short words we should NOT split into individual letters.
    private val COMMON_SHORT_WORDS: Set<String> = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can",
        "had", "her", "was", "one", "our", "out", "has", "had", "been",
        "they", "this", "that", "with", "from", "your", "which", "their",
        "will", "would", "could", "should", "what", "when", "where",
        "there", "each", "she", "his", "him", "who", "how", "its", "may",
        "say", "get", "way", "like", "know", "than", "then", "them",
        "some", "more", "just", "come", "over", "such", "these", "those",
        "into", "upon", "here", "very", "much", "many", "most",
        "never", "ever", "even", "still", "well", "down", "back", "only",
        "often", "once", "also", "though",
        "in", "at", "to", "on", "by", "of", "it", "is", "be", "he", "she",
        "my", "me", "we", "or", "an", "as", "up", "us", "go", "no", "if",
        "do", "so", "am", "ok"
    )

    /**
     * Splits short all-alpha tokens that look like ASR-merged individual
     * letters (e.g. "dco" → "d c o") and, in a spelling context, maps the
     * common ASR misrecognition "in" → "n".
     *
     * Run this BEFORE [collapseSingleLetterRuns] so the expanded single
     * letters can be properly collapsed.
     */
    private fun expandLetterRuns(input: String): String {
        val tokens = input.split(Regex("(?<=\\s)|(?=\\s)"))
        // First pass: split 2-5 letter words that aren't common English
        val expanded = tokens.map { token ->
            val trimmed = token.trim()
            if (trimmed.length in 2..5 && trimmed.all { it.isLetter() }
                && trimmed !in COMMON_SHORT_WORDS) {
                token.replace(trimmed, trimmed.toCharArray().joinToString(" "))
            } else token
        }.toMutableList()

        // Second pass: "in" → "n" when the context suggests spelling
        for (i in expanded.indices) {
            val trimmed = expanded[i].trim()
            if (trimmed == "in") {
                val prev = expanded.getOrNull(i - 1)?.trim() ?: ""
                val next = expanded.getOrNull(i + 1)?.trim() ?: ""
                val prevIsLetter = prev.length == 1 && prev[0].isLetter()
                val nextIsLetterOrDigit = next.length == 1 && next[0].isLetterOrDigit()
                val nextIsEmail = next.contains("@")
                if (prevIsLetter || nextIsLetterOrDigit || nextIsEmail) {
                    expanded[i] = expanded[i].replace("in", "n")
                }
            }
        }
        return expanded.joinToString("")
    }

    // Filler phrases that users often prefix the address with.
    private val fillerPattern: Regex by lazy {
        Regex(
            "\\b(my email is|email is|my email address is|email address is|" +
                "it's|it is|that is|that's|the email is|i need|i want|" +
                "send to|send it to|please send to|please send it to)\\b",
            RegexOption.IGNORE_CASE
        )
    }

    // Common domain auto-corrections for when the ASR omits the TLD.
    private val domainMap: Map<String, String> by lazy { mapOf(
        "gmail" to "gmail.com", "googlemail" to "gmail.com",
        "yahoo" to "yahoo.com", "ymail" to "yahoo.com",
        "outlook" to "outlook.com", "hotmail" to "outlook.com",
        "live" to "live.com", "icloud" to "icloud.com",
        "me" to "me.com", "mac" to "me.com",
        "protonmail" to "protonmail.com", "proton" to "protonmail.com",
        "aol" to "aol.com", "att" to "att.net",
        "verizon" to "verizon.net", "comcast" to "comcast.net",
        "cox" to "cox.net"
    )}

    /**
     * Attempts to parse free-form dictation like "john at gmail dot com"
     * into a canonical RFC-5321-shaped email address.  Returns `null` if
     * the result still doesn't look like a valid email address.
     *
     * Supports the typical speech-to-text artefacts:
     *   "at"             → "@"
     *   "dot" / "period" / "point" → "."
     *   "underscore"     → "_"
     *   "dash"           → "-"
     *   single-letter spellings: "j-o-h-n" or "j o h n" → "john" when the
     *   sequence consists only of single letters separated by spaces/dashes.
     *   NATO phonetic alphabet: "alpha bravo charlie" → "abc"
     *   filler phrases stripped: "my email is", "send to", etc.
     *   common domain names expanded: just "gmail" → "gmail.com"
     */
    fun parseDictatedEmail(raw: String): String? {
        if (raw.isBlank()) return null
        var s = raw.lowercase(Locale.US).trim()

        // Strip filler phrases ("my email is", "send to", etc.)
        s = s.replace(fillerPattern, "").trim()

        // Map NATO phonetic words to single letters *before* the punctuation
        // replacements so "alpha bravo charlie at gmail dot com" is processed
        // token-by-token.
        s = mapPhoneticLetters(s)

        // Verbal punctuation → ASCII.
        s = s
            .replace(Regex("\\s+at\\s+"), "@")
            .replace(Regex("\\s+@\\s*"), "@")
            .replace(Regex("\\s*@\\s+"), "@")
            .replace(Regex("\\s+dot\\s+"),    ".")
            .replace(Regex("\\s+period\\s+"), ".")
            .replace(Regex("\\s+point\\s+"),  ".")
            .replace(Regex("\\s+underscore\\s+"), "_")
            .replace(Regex("\\s+dash\\s+"),       "-")
            .replace(Regex("\\s+hyphen\\s+"),     "-")

        // Trailing/leading "dot com" without a space because the recogniser
        // sometimes concatenates ("dotcom").  Best-effort.
        s = s.replace(Regex("\\bdotcom\\b"), ".com")
             .replace(Regex("\\bdotorg\\b"), ".org")
             .replace(Regex("\\bdotnet\\b"), ".net")

        // Auto-correct bare domain names to full domains where the user
        // omitted the TLD (e.g. "just gmail" → "gmail.com").  Only replace
        // when the token stands alone so "gmail.com" is not doubled.
        for ((short, full) in domainMap) {
            s = s.replace(Regex("\\b${Regex.escape(short)}\\b(?!\\.)"), full)
        }

        // Expand ASR-merged letter runs ("dco" → "d c o") and map "in" → "n"
        // in spelling context, BEFORE single-letter collapse.
        s = expandLetterRuns(s)

        // Collapse "j-o-h-n" or "j o h n" sequences into "john" — but ONLY
        // when EVERY token is a single letter, so we don't mangle real words.
        s = collapseSingleLetterRuns(s)

        // Remove any whitespace that survived.
        s = s.replace(Regex("\\s+"), "")

        // Strip dashes inside the local-part if they're clearly recognizer
        // artefacts ("j-o-h-n@gmail.com" was already collapsed above; this
        // catches the rare "jo-hn@gmail.com" pattern that probably should be
        // "john@gmail.com").  We keep dashes in the domain because hyphenated
        // domain names are common.
        val atIdx = s.indexOf('@')
        if (atIdx > 0) {
            val local = s.substring(0, atIdx).replace("-", "")
            val domain = s.substring(atIdx)
            s = local + domain
        }

        return if (looksLikeEmail(s)) s else null
    }

    /**
     * Aggressively attempts to extract an email-shaped string from arbitrary
     * recognizer output using the full phonetic letter map, token→symbol,
     * and single-letter concatenation.  Unlike [parseDictatedEmail] this
     * does NOT require "at" or "dot" to be present — it treats every token
     * as a potential address component.
     *
     * Used as the fallback inside the compose spell flow when the standard
     * parser returns null.
     */
    fun tryParseSpelledOut(input: String): String? {
        if (input.isBlank()) return null
        var s = input.lowercase(Locale.US).trim()
        s = s.replace(fillerPattern, "").trim()
        s = mapPhoneticLetters(s)
        s = expandLetterRuns(s)

        val tokens = s.split(Regex("[\\s,;:!?]+"))
        val out = StringBuilder()
        var hadAt = false
        var hadDot = false
        for (t in tokens) {
            when {
                // Already a punctuation symbol or short token.
                t == "@" || t == "at" -> { out.append('@'); hadAt = true }
                t == "." || t == "dot" || t == "period" || t == "point" -> { out.append('.'); hadDot = true }
                t == "_" || t == "underscore" -> out.append('_')
                t == "-" || t == "dash" || t == "hyphen" -> out.append('-')
                // Single letter or digit — append directly.
                t.length == 1 && (t[0].isLetterOrDigit()) -> out.append(t)
                // Known domain — append with .TLD if no dot yet.
                !hadAt && domainMap.containsKey(t) -> out.append(domainMap[t])
                // Token contains @ or . — keep it raw.
                t.contains("@") || t.contains(".") -> out.append(t)
                // Token looks like a known TLD.
                t in listOf("com", "org", "net", "edu", "gov", "io", "co", "uk") ->
                    if (!hadDot) { out.append(".$t"); hadDot = true } else out.append(t)
            }
        }
        val result = out.toString()
            .replace(Regex("\\.{2,}"), ".")
            .replace(Regex("@+"), "@")
            .trim('.', '@', '-', '_')
        return if (looksLikeEmail(result)) result else null
    }

    /**
     * Walks the string token-by-token and replaces NATO phonetic-alphabet
     * words with their single-letter equivalents.  Operates token-wise so
     * "alpha bravo charlie" becomes "a b c", which [collapseSingleLetterRuns]
     * can then fold into "abc".
     */
    private fun mapPhoneticLetters(input: String): String {
        val tokens = input.split(Regex("(?<=\\s)|(?=\\s)"))
        return tokens.joinToString("") { t ->
            val stripped = t.trim()
            val mapped = phoneticLetterMap[stripped]
            if (mapped != null) t.replace(stripped, mapped) else t
        }
    }

    /**
     * Extracts unique sender entries from the user's inbox.  Parses the raw
     * `From:` header value which is typically `"Display Name" <user@host>`
     * but may also be just `user@host`.
     */
    fun extractSendersFromInbox(emails: List<EmailItem>): List<Contact> {
        val seen = HashSet<String>()
        val out = ArrayList<Contact>()
        for (email in emails) {
            val (name, addr) = parseFromHeader(email.from) ?: continue
            val key = addr.lowercase(Locale.US)
            if (seen.add(key)) {
                out += Contact(
                    displayName = name.ifBlank { addr.substringBefore('@') },
                    email = addr,
                    source = Contact.Source.Inbox
                )
            }
        }
        return out
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Parses an RFC-5322-style `From` header value into (displayName, email).
     * Returns null if no email-looking substring is present.
     */
    private fun parseFromHeader(raw: String): Pair<String, String>? {
        if (raw.isBlank()) return null
        val angleStart = raw.indexOf('<')
        val angleEnd   = raw.indexOf('>', startIndex = angleStart + 1)
        return if (angleStart >= 0 && angleEnd > angleStart) {
            val name = raw.substring(0, angleStart).trim().trim('"').trim()
            val addr = raw.substring(angleStart + 1, angleEnd).trim()
            if (looksLikeEmail(addr)) name to addr else null
        } else {
            val candidate = raw.trim().trim('<', '>')
            if (looksLikeEmail(candidate)) "" to candidate else null
        }
    }

    private fun looksLikeEmail(s: String): Boolean {
        if (s.length < 5 || s.length > 254) return false
        val at = s.indexOf('@')
        if (at <= 0 || at == s.length - 1) return false
        if (s.indexOf('@', at + 1) >= 0) return false // more than one @
        val dotAfterAt = s.indexOf('.', at + 2)
        if (dotAfterAt < 0 || dotAfterAt == s.length - 1) return false
        // Cheap character class check — full RFC compliance is overkill here.
        return s.all { c ->
            c.isLetterOrDigit() || c in "._%+-@"
        }
    }

    /**
     * Folds runs of single-letter tokens into a single word.  Examples:
     *   "j o h n at gmail" → "john at gmail"
     *   "j-o-h-n at gmail" → "john at gmail"   (after dash → space)
     *   "hi john at gmail" → "hi john at gmail"   (NOT folded — "hi"/"john" are multi-letter)
     */
    private fun collapseSingleLetterRuns(input: String): String {
        // First normalise dashes between single letters to spaces.
        val spaced = input.replace(
            Regex("(?<=\\b[a-z])-(?=[a-z]\\b)"),
            " "
        )
        val tokens = spaced.split(' ').filter { it.isNotEmpty() }
        val out = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            // A "run" needs ≥ 2 consecutive single-letter tokens to fold.
            if (tok.length == 1 && tok[0].isLetter()) {
                var j = i + 1
                while (j < tokens.size && tokens[j].length == 1 && tokens[j][0].isLetter()) j++
                if (j - i >= 2) {
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    for (k in i until j) out.append(tokens[k])
                    out.append(' ')
                    i = j
                    continue
                }
            }
            if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            out.append(tok)
            i++
        }
        return out.toString().trim()
    }

    /**
     * Scores a contact 0..100 for the given normalised query.  Higher = better.
     *
     * Heuristics, in priority order:
     *  - Query exactly equals the email address               → 100
     *  - Query exactly equals the displayName (case-insens)   → 98
     *  - Query equals the email local-part                    → 92
     *  - Query equals the first/given name                    → 90
     *  - displayName starts-with query                        → 80 - lengthPenalty
     *  - displayName contains query as a whole word           → 70 - lengthPenalty
     *  - Levenshtein-normalised similarity                    → 0..65
     *  - Otherwise                                            → 0
     */
    private fun scoreContact(query: String, contact: Contact): Int {
        val name  = contact.displayName.lowercase(Locale.US)
        val email = contact.email.lowercase(Locale.US)
        val local = email.substringBefore('@')
        val first = name.substringBefore(' ').trim()

        if (query == email) return 100
        if (query == name)  return 98
        if (query == local) return 92
        if (first.isNotEmpty() && query == first) return 90

        // Multi-token "first last" matching, regardless of order.
        val queryTokens = query.split(' ').filter { it.length > 1 }.toSet()
        val nameTokens  = name.split(' ', '.', '_', '-').filter { it.length > 1 }.toSet()
        if (queryTokens.isNotEmpty() && nameTokens.containsAll(queryTokens)) return 88

        if (name.startsWith(query)) {
            return (80 - lengthDistance(query, name)).coerceAtLeast(60)
        }
        if (local.startsWith(query)) {
            return (78 - lengthDistance(query, local)).coerceAtLeast(58)
        }
        if (containsAsWord(name, query)) {
            return (70 - lengthDistance(query, name)).coerceAtLeast(55)
        }

        // Falls through to Levenshtein-based similarity on the better of
        // (displayName, local-part).  Already normalised so it's cheap.
        val bestSim = maxOf(similarity(query, name), similarity(query, local), similarity(query, first))
        return (bestSim * 65 / 100).coerceIn(0, 65)
    }

    private fun lengthDistance(a: String, b: String): Int =
        kotlin.math.abs(a.length - b.length).coerceAtMost(10)

    private fun containsAsWord(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val pattern = Regex("\\b" + Regex.escape(needle) + "\\b")
        return pattern.containsMatchIn(haystack)
    }

    /** Returns Levenshtein-based similarity in `0..100`. */
    private fun similarity(a: String, b: String): Int {
        if (a.isEmpty() && b.isEmpty()) return 100
        if (a.isEmpty() || b.isEmpty()) return 0
        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return ((maxLen - dist) * 100 / maxLen).coerceAtLeast(0)
    }

    private fun levenshtein(a: String, b: String): Int {
        // Classic two-row implementation — O(min(|a|,|b|)) space.
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
