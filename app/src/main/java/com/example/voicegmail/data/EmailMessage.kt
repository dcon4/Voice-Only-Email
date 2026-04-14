package com.example.voicegmail.data

/**
 * Represents a single email message returned from the Gmail API.
 */
data class EmailMessage(
    val id: String,
    val threadId: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val bodyText: String,
    val date: String,
    val isUnread: Boolean
)
