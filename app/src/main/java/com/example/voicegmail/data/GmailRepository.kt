package com.example.voicegmail.data

/**
 * Contract for Gmail inbox operations.
 */
interface GmailRepository {

    /**
     * Returns the most recent [maxResults] messages from the inbox.
     */
    suspend fun getInboxMessages(maxResults: Long = 20): List<EmailMessage>

    /**
     * Returns full message details for the given [messageId].
     */
    suspend fun getMessage(messageId: String): EmailMessage

    /**
     * Sends an email from the authenticated user's account.
     */
    suspend fun sendEmail(to: String, subject: String, body: String)
}
