package com.example.voicegmail.gmail

import android.util.Base64
import android.util.Log
import com.example.voicegmail.auth.AuthRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceGmail.Gmail"

@Singleton
class GmailRepository @Inject constructor(
    private val gmailApiService: GmailApiService,
    private val authRepository: AuthRepository
) {
    private suspend fun authHeader(): String {
        val token = authRepository.accessTokenFlow.first()
            ?: throw IllegalStateException("Not authenticated")
        return "Bearer $token"
    }

    private suspend fun <T> withAutoRefresh(block: suspend (auth: String) -> T): T {
        return try {
            block(authHeader())
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                Log.w(TAG, "Got ${e.code()} — attempting silent token refresh")
                val newToken = authRepository.refreshAccessToken()
                if (newToken != null) {
                    Log.i(TAG, "Token refreshed — retrying request")
                    block("Bearer $newToken")
                } else {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    suspend fun listInbox(): List<EmailItem> = withAutoRefresh { auth ->
        val refs = gmailApiService.listMessages(auth).messages ?: return@withAutoRefresh emptyList()
        coroutineScope {
            refs.map { ref ->
                async { gmailApiService.getMessage(auth, ref.id).toEmailItem() }
            }.awaitAll()
        }
    }

    suspend fun sendEmail(
        to: String,
        subject: String,
        body: String,
        attachments: List<OutgoingAttachment> = emptyList()
    ) = withAutoRefresh { auth ->
        val rawMime = buildMimeMessage(to, subject, body, attachments)
        val encoded = Base64.encodeToString(
            rawMime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        gmailApiService.sendMessage(auth, SendMessageRequest(raw = encoded))
    }

    /**
     * Searches the user's entire mailbox (not just INBOX) using Gmail query syntax.
     * Passing [labelIds] = null omits the parameter so Gmail searches all labels.
     */
    suspend fun searchEmails(query: String): List<EmailItem> = withAutoRefresh { auth ->
        val refs = gmailApiService.listMessages(
            auth       = auth,
            maxResults = 20,
            labelIds   = null,   // null → Retrofit omits the param → all-mailbox search
            query      = query
        ).messages ?: return@withAutoRefresh emptyList()
        coroutineScope {
            refs.map { ref ->
                async { gmailApiService.getMessage(auth, ref.id).toEmailItem() }
            }.awaitAll()
        }
    }

    /**
     * Removes the UNREAD label, marking the message as read.
     * Requires the gmail.modify OAuth scope.
     */
    suspend fun markAsRead(messageId: String) = withAutoRefresh { auth ->
        gmailApiService.modifyMessage(
            auth    = auth,
            id      = messageId,
            request = ModifyLabelsRequest(removeLabelIds = listOf("UNREAD"))
        )
    }

    suspend fun downloadAttachmentData(messageId: String, attachmentId: String): ByteArray =
        withAutoRefresh { auth ->
            val response = gmailApiService.getAttachment(auth, messageId, attachmentId)
            Base64.decode(response.data, Base64.URL_SAFE)
        }

    suspend fun trashEmail(messageId: String) = withAutoRefresh { auth ->
        gmailApiService.trashMessage(auth, messageId)
    }

    private fun buildMimeMessage(
        to: String,
        subject: String,
        body: String,
        attachments: List<OutgoingAttachment>
    ): String {
        if (attachments.isEmpty()) {
            return buildString {
                append("To: $to\r\n")
                append("Subject: $subject\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("\r\n")
                append(body)
            }
        }
        val boundary = "----voicegmail_${System.currentTimeMillis()}"
        return buildString {
            append("MIME-Version: 1.0\r\n")
            append("To: $to\r\n")
            append("Subject: $subject\r\n")
            append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n")
            append("\r\n")
            append("--$boundary\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("\r\n")
            append(body)
            append("\r\n")
            for (att in attachments) {
                append("--$boundary\r\n")
                append("Content-Type: ${att.mimeType}; name=\"${att.filename}\"\r\n")
                append("Content-Disposition: attachment; filename=\"${att.filename}\"\r\n")
                append("Content-Transfer-Encoding: base64\r\n")
                append("\r\n")
                append(Base64.encodeToString(att.bytes, Base64.DEFAULT))
                append("\r\n")
            }
            append("--$boundary--\r\n")
        }
    }
}
