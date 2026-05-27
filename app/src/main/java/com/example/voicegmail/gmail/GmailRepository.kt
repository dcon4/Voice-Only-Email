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

    /**
     * Runs [block] with the current access token. If Google returns 401
     * (expired token), silently refreshes and retries exactly once — completely
     * invisible to the user. If the refresh also fails, the exception propagates
     * so the ViewModel can send the user back to the sign-in screen.
     */
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

    suspend fun sendEmail(to: String, subject: String, body: String) = withAutoRefresh { auth ->
        val rawMime = buildMimeMessage(to, subject, body)
        val encoded = Base64.encodeToString(
            rawMime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        gmailApiService.sendMessage(auth, SendMessageRequest(raw = encoded))
    }

    /**
     * Searches the user's mailbox using Gmail query syntax (e.g. "from:david",
     * "subject:meeting", "is:unread"). Returns up to 20 matching messages with
     * full detail, ordered by Gmail's default relevance ranking.
     */
    suspend fun searchEmails(query: String): List<EmailItem> = withAutoRefresh { auth ->
        val refs = gmailApiService.listMessages(
            auth = auth,
            maxResults = 20,
            labelIds = "",  // empty — search all labels, not just INBOX
            query = query
        ).messages ?: return@withAutoRefresh emptyList()
        coroutineScope {
            refs.map { ref ->
                async { gmailApiService.getMessage(auth, ref.id).toEmailItem() }
            }.awaitAll()
        }
    }

    /**
     * Moves [messageId] to the user's Trash. The email disappears from the inbox
     * immediately and can be recovered from Trash for 30 days.
     */
    suspend fun trashEmail(messageId: String) = withAutoRefresh { auth ->
        gmailApiService.trashMessage(auth, messageId)
    }

    private fun buildMimeMessage(to: String, subject: String, body: String): String {
        return buildString {
            append("To: $to\r\n")
            append("Subject: $subject\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("\r\n")
            append(body)
        }
    }
}
