package com.example.voicegmail.gmail

import android.util.Base64
import com.example.voicegmail.auth.AuthRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun listInbox(): List<EmailItem> {
        val auth = authHeader()
        val refs = gmailApiService.listMessages(auth).messages ?: return emptyList()
        return refs.map { ref ->
            gmailApiService.getMessage(auth, ref.id).toEmailItem()
        }
    }

    suspend fun sendEmail(to: String, subject: String, body: String) {
        val auth = authHeader()
        val rawMime = buildMimeMessage(to, subject, body)
        val encoded = Base64.encodeToString(rawMime.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        gmailApiService.sendMessage(auth, SendMessageRequest(raw = encoded))
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
