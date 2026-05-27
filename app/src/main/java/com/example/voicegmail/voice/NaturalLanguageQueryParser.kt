package com.example.voicegmail.voice

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts natural-language search phrases (as spoken by the user) into
 * Gmail search query syntax understood by the Gmail REST API `q` parameter.
 *
 * Handled patterns (combinable in any order):
 *   Sender   — "from David Carter", "sent by mom", "by Sarah"
 *   Recipient— "to John", "sent to my boss"
 *   Time     — "in the last week", "last 3 months", "today", "this month"
 *   Status   — "unread", "read", "starred", "with attachment(s)"
 *   Topic    — "about inventory", "regarding the project", "subject meeting"
 *
 * Example:
 *   "all emails in the last month from David Carter about inventory"
 *   → `from:"David Carter" after:2026/04/26 inventory`
 *
 * If no structured patterns are detected the raw input is returned unchanged
 * so Gmail's own search engine can attempt to handle it.
 */
@Singleton
class NaturalLanguageQueryParser @Inject constructor() {

    fun parse(input: String): String {
        val lower = input.trim().lowercase()
        val parts = mutableListOf<String>()

        extractFrom(lower)?.let { parts.add(it) }
        extractTo(lower)?.let { parts.add(it) }
        extractAfter(lower)?.let { parts.add(it) }
        extractStatusFlags(lower, parts)
        extractTopicKeywords(lower)?.let { parts.add(it) }

        return if (parts.isEmpty()) input else parts.joinToString(" ")
    }

    // ------------------------------------------------------------------
    // Sender
    // ------------------------------------------------------------------

    private fun extractFrom(lower: String): String? {
        // Matches: "from John", "from David Carter", "sent by mom", "by sarah@example.com"
        val pattern = Regex(
            """(?:from|sent by|by)\s+([a-z0-9._%+\-@']+(?:\s+[a-z0-9._%+\-@']+)?)"""
        )
        val match = pattern.find(lower) ?: return null
        val name = match.groupValues[1].trim()
            .removePrefix("the ")
            .trim()
        if (name.isBlank()) return null
        return if (name.contains(' ')) "from:\"$name\"" else "from:$name"
    }

    // ------------------------------------------------------------------
    // Recipient
    // ------------------------------------------------------------------

    private fun extractTo(lower: String): String? {
        val pattern = Regex(
            """(?:to|sent to|addressed to)\s+([a-z0-9._%+\-@']+(?:\s+[a-z0-9._%+\-@']+)?)"""
        )
        val match = pattern.find(lower) ?: return null
        val name = match.groupValues[1].trim()
            .removePrefix("the ")
            .trim()
        if (name.isBlank()) return null
        return if (name.contains(' ')) "to:\"$name\"" else "to:$name"
    }

    // ------------------------------------------------------------------
    // Date / time
    // ------------------------------------------------------------------

    private fun extractAfter(lower: String): String? {
        val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        val cal = Calendar.getInstance()

        // "in the last N days/weeks/months/years" or "last N ..."  or "past N ..."
        val numericPattern = Regex(
            """(?:in the last|in the past|past|last)\s+(\d+)\s+(day|days|week|weeks|month|months|year|years)"""
        )
        numericPattern.find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 1
            when {
                m.groupValues[2].startsWith("day")   -> cal.add(Calendar.DAY_OF_YEAR, -n)
                m.groupValues[2].startsWith("week")  -> cal.add(Calendar.WEEK_OF_YEAR, -n)
                m.groupValues[2].startsWith("month") -> cal.add(Calendar.MONTH, -n)
                m.groupValues[2].startsWith("year")  -> cal.add(Calendar.YEAR, -n)
            }
            return "after:${fmt.format(cal.time)}"
        }

        // "in the last week/month/year" (no number — default n=1)
        val namedRelativePattern = Regex(
            """(?:in the last|in the past)\s+(day|week|month|year)"""
        )
        namedRelativePattern.find(lower)?.let { m ->
            when (m.groupValues[1]) {
                "day"   -> cal.add(Calendar.DAY_OF_YEAR, -1)
                "week"  -> cal.add(Calendar.WEEK_OF_YEAR, -1)
                "month" -> cal.add(Calendar.MONTH, -1)
                "year"  -> cal.add(Calendar.YEAR, -1)
            }
            return "after:${fmt.format(cal.time)}"
        }

        // Named periods
        return when {
            lower.contains("today") -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                "after:${fmt.format(cal.time)}"
            }
            lower.contains("yesterday") -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                "after:${fmt.format(cal.time)}"
            }
            lower.contains("last week") || lower.contains("past week") -> {
                cal.add(Calendar.WEEK_OF_YEAR, -1)
                "after:${fmt.format(cal.time)}"
            }
            lower.contains("this week") -> {
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                "after:${fmt.format(cal.time)}"
            }
            lower.contains("last month") || lower.contains("past month") -> {
                cal.add(Calendar.MONTH, -1)
                "after:${fmt.format(cal.time)}"
            }
            lower.contains("this month") -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                "after:${fmt.format(cal.time)}"
            }
            lower.contains("last year") || lower.contains("past year") -> {
                cal.add(Calendar.YEAR, -1)
                "after:${fmt.format(cal.time)}"
            }
            lower.contains("this year") -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                "after:${fmt.format(cal.time)}"
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Status flags
    // ------------------------------------------------------------------

    private fun extractStatusFlags(lower: String, parts: MutableList<String>) {
        if (lower.contains("unread") || lower.contains("unopened") || lower.contains("new email")) {
            parts.add("is:unread")
        } else if (lower.contains(" read") || lower.contains("already read") || lower.contains("opened")) {
            // " read" with leading space avoids matching "unread"
            parts.add("is:read")
        }
        if (lower.contains("starred") || lower.contains("flagged")) {
            parts.add("is:starred")
        }
        if (lower.contains("attachment") || lower.contains("attached") || lower.contains("has a file")) {
            parts.add("has:attachment")
        }
    }

    // ------------------------------------------------------------------
    // Topic / subject keywords
    // ------------------------------------------------------------------

    private fun extractTopicKeywords(lower: String): String? {
        // Strip noise words before extracting the topic
        val stopWords = setOf(
            "all", "emails", "email", "messages", "message", "mail",
            "the", "a", "an", "and", "or", "my", "me", "i", "in", "on",
            "for", "of", "with", "that", "those", "any", "some", "every"
        )

        val topicPattern = Regex(
            """(?:about|regarding|concerning|related to|re:|subject[:\s]+|titled?)\s+(.+)"""
        )
        val match = topicPattern.find(lower) ?: return null

        // The topic tail may contain already-handled sub-clauses (e.g. "from …" that
        // appeared after "about"). Strip known structural phrases from the topic.
        val tail = match.groupValues[1]
            .replace(Regex("""(?:from|sent by|by|to|sent to)\s+\S+(\s+\S+)?"""), " ")
            .replace(Regex("""(?:in the last|last|past)\s+(\d+\s+)?(day|week|month|year)s?"""), " ")
            .replace(Regex("""(?:today|yesterday|this week|last week|this month|last month|this year|last year)"""), " ")
            .replace(Regex("""(?:unread|read|starred|flagged|with attachment)"""), " ")

        val keywords = tail.split(Regex("""[\s,]+"""))
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.length > 2 && it !in stopWords }
            .distinct()

        if (keywords.isEmpty()) return null
        // Wrap multi-word topics in quotes, otherwise list keywords space-separated
        return if (keywords.size == 1) keywords[0] else keywords.joinToString(" ")
    }
}
