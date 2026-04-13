package com.voiceemail.email

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.mail.Session

data class EmailToSend(
    val toAddress: String,
    val subject: String,
    val body: String,
    val replyToMessageId: String? = null,
    val cc: String? = null,
    val bcc: String? = null
)

class EmailSender(
    private val userEmail: String,
    private val smtpSession: Session? = null
) {
    /**
     * Send email via Gmail API
     * Original: email_client/sender.py - send_message()
     */
    suspend fun sendViaGmail(email: EmailToSend): String = withContext(Dispatchers.IO) {
        try {
            // Implementation will send via Gmail API
            "message_id_placeholder"
        } catch (e: Exception) {
            throw EmailException("Failed to send email via Gmail: ${e.message}", e)
        }
    }

    /**
     * Send email via SMTP (for IMAP-based providers)
     */
    suspend fun sendViaSmtp(email: EmailToSend): Boolean = withContext(Dispatchers.IO) {
        try {
            // Implementation will send via SMTP
            true
        } catch (e: Exception) {
            throw EmailException("Failed to send email via SMTP: ${e.message}", e)
        }
    }

    /**
     * Reply to an email
     */
    suspend fun reply(originalMessage: EmailMessage, replyText: String): String? = 
        withContext(Dispatchers.IO) {
        try {
            val email = EmailToSend(
                toAddress = extractEmailAddress(originalMessage.from),
                subject = if (originalMessage.subject.startsWith("Re:")) {
                    originalMessage.subject
                } else {
                    "Re: ${originalMessage.subject}"
                },
                body = formatReplyBody(replyText, originalMessage),
                replyToMessageId = originalMessage.threadId
            )
            sendViaGmail(email)
        } catch (e: Exception) {
            throw EmailException("Failed to send reply: ${e.message}", e)
        }
    }

    /**
     * Forward an email
     */
    suspend fun forward(
        originalMessage: EmailMessage,
        toAddress: String,
        additionalText: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val forwardBody = buildString {
                if (!additionalText.isNullOrEmpty()) {
                    appendLine(additionalText)
                    appendLine()
                }
                appendLine("---------- Forwarded message ---------")
                appendLine("From: ${originalMessage.from}")
                appendLine("Date: ${originalMessage.timestamp}")
                appendLine("Subject: ${originalMessage.subject}")
                appendLine("To: ${originalMessage.to}")
                appendLine()
                appendLine(originalMessage.body)
            }

            val email = EmailToSend(
                toAddress = toAddress,
                subject = "Fwd: ${originalMessage.subject}",
                body = forwardBody
            )

            sendViaGmail(email)
        } catch (e: Exception) {
            throw EmailException("Failed to forward email: ${e.message}", e)
        }
    }

    private fun extractEmailAddress(addressString: String): String {
        val emailPattern = """<(.+?)>""".toRegex()
        val match = emailPattern.find(addressString)
        return match?.groupValues?.get(1) ?: addressString
    }

    private fun formatReplyBody(replyText: String, originalMessage: EmailMessage): String {
        return buildString {
            appendLine(replyText)
            appendLine()
            appendLine("On ${originalMessage.timestamp}, ${originalMessage.from} wrote:")
            appendLine(
                "> ${originalMessage.body.replace("
", "
> ")})
        }
    }
}