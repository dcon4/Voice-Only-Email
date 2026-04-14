package com.example.voicegmail.data

import android.util.Base64
import com.example.voicegmail.auth.TokenStore
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Gmail API implementation of [GmailRepository].
 *
 * Uses the access token from [TokenStore] to authenticate each request via a
 * simple Bearer-token [HttpRequestInitializer].
 */
@Singleton
class GoogleGmailRepository @Inject constructor(
    private val tokenStore: TokenStore
) : GmailRepository {

    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport by lazy { GoogleNetHttpTransport.newTrustedTransport() }

    // Build a Gmail service authenticated with the current bearer token
    private suspend fun buildService(): Gmail {
        val token = tokenStore.getAccessToken()
            ?: throw IllegalStateException("No access token available. Please sign in.")

        val initializer = HttpRequestInitializer { request: HttpRequest ->
            request.headers.authorization = "Bearer $token"
        }

        return Gmail.Builder(httpTransport, jsonFactory, initializer)
            .setApplicationName("Voice Gmail")
            .build()
    }

    override suspend fun getInboxMessages(maxResults: Long): List<EmailMessage> =
        withContext(Dispatchers.IO) {
            val service = buildService()
            val listResponse = service.users().messages()
                .list("me")
                .setLabelIds(listOf("INBOX"))
                .setMaxResults(maxResults)
                .execute()

            val messages = listResponse.messages ?: return@withContext emptyList()

            messages.mapNotNull { stub ->
                runCatching {
                    val msg = service.users().messages()
                        .get("me", stub.id)
                        .setFormat("metadata")
                        .setMetadataHeaders(listOf("From", "Subject", "Date"))
                        .execute()
                    msg.toEmailMessage()
                }.getOrNull()
            }
        }

    override suspend fun getMessage(messageId: String): EmailMessage =
        withContext(Dispatchers.IO) {
            val service = buildService()
            val msg = service.users().messages()
                .get("me", messageId)
                .setFormat("full")
                .execute()
            msg.toEmailMessage()
        }

    override suspend fun sendEmail(to: String, subject: String, body: String) =
        withContext(Dispatchers.IO) {
            val service = buildService()

            // Build MIME message
            val props = Properties()
            val session = Session.getDefaultInstance(props, null)
            val mimeMessage = MimeMessage(session).apply {
                setFrom(InternetAddress("me"))
                addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(to))
                setSubject(subject, "UTF-8")
                setText(body, "UTF-8")
            }

            // Encode to RFC 2822 bytes then Base64url
            val buffer = ByteArrayOutputStream()
            mimeMessage.writeTo(buffer)
            val rawBytes = buffer.toByteArray()
            val encodedEmail = Base64.encodeToString(rawBytes, Base64.URL_SAFE or Base64.NO_WRAP)

            val rawMessage = Message().apply { raw = encodedEmail }
            service.users().messages().send("me", rawMessage).execute()
        }

    // ---- Helpers ----

    private fun Message.toEmailMessage(): EmailMessage {
        val headers = payload?.headers?.associate { it.name to it.value } ?: emptyMap()
        val from = headers["From"] ?: "Unknown"
        val subject = headers["Subject"] ?: "(No Subject)"
        val date = headers["Date"] ?: ""
        val snippet = this.snippet ?: ""
        val isUnread = labelIds?.contains("UNREAD") == true
        val bodyText = extractBodyText(this)

        return EmailMessage(
            id = id ?: "",
            threadId = threadId ?: "",
            from = from,
            subject = subject,
            snippet = snippet,
            bodyText = bodyText,
            date = date,
            isUnread = isUnread
        )
    }

    private fun extractBodyText(message: Message): String {
        val part = message.payload ?: return ""
        // Try to find text/plain part
        if (part.mimeType == "text/plain" && part.body?.data != null) {
            return decodeBase64(part.body.data)
        }
        // Walk multipart
        part.parts?.forEach { p ->
            if (p.mimeType == "text/plain" && p.body?.data != null) {
                return decodeBase64(p.body.data)
            }
        }
        // Fallback to snippet
        return message.snippet ?: ""
    }

    private fun decodeBase64(data: String): String {
        return try {
            String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
