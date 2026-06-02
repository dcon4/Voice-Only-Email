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

    // ── Inbox ─────────────────────────────────────────────────────────────

    /**
     * Most-recently-fetched inbox.  Updated on every successful [listInbox]
     * call and exposed here so [com.example.voicegmail.contacts.ContactsRepository]
     * can derive inbox-sender contacts for the voice-driven recipient
     * matcher without paying for another full inbox round-trip.
     */
    @Volatile var lastLoadedInbox: List<EmailItem> = emptyList()
        private set

    suspend fun listInbox(): List<EmailItem> = withAutoRefresh { auth ->
        val refs = gmailApiService.listMessages(auth).messages ?: return@withAutoRefresh emptyList()
        val emails = coroutineScope {
            refs.map { ref ->
                async { gmailApiService.getMessage(auth, ref.id).toEmailItem() }
            }.awaitAll()
        }
        lastLoadedInbox = emails
        emails
    }

    // ── Send ──────────────────────────────────────────────────────────────

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

    // ── Search ────────────────────────────────────────────────────────────

    suspend fun searchEmails(query: String): List<EmailItem> = withAutoRefresh { auth ->
        val refs = gmailApiService.listMessages(
            auth       = auth,
            maxResults = 20,
            labelIds   = null,
            query      = query
        ).messages ?: return@withAutoRefresh emptyList()
        coroutineScope {
            refs.map { ref ->
                async { gmailApiService.getMessage(auth, ref.id).toEmailItem() }
            }.awaitAll()
        }
    }

    // ── Modify ────────────────────────────────────────────────────────────

    suspend fun markAsRead(messageId: String) = withAutoRefresh { auth ->
        gmailApiService.modifyMessage(
            auth    = auth,
            id      = messageId,
            request = ModifyLabelsRequest(removeLabelIds = listOf("UNREAD"))
        )
    }

    suspend fun markAsUnread(messageId: String) = withAutoRefresh { auth ->
        gmailApiService.modifyMessage(
            auth    = auth,
            id      = messageId,
            request = ModifyLabelsRequest(addLabelIds = listOf("UNREAD"))
        )
    }

    suspend fun trashEmail(messageId: String) = withAutoRefresh { auth ->
        gmailApiService.trashMessage(auth, messageId)
    }

    // ── Attachments ───────────────────────────────────────────────────────

    suspend fun downloadAttachmentData(messageId: String, attachmentId: String): ByteArray =
        withAutoRefresh { auth ->
            val response = gmailApiService.getAttachment(auth, messageId, attachmentId)
            Base64.decode(response.data, Base64.URL_SAFE)
        }

    // ── Drafts ────────────────────────────────────────────────────────────

    /**
     * Save a new draft.
     * Returns the [DraftItem] as stored by Gmail, including its assigned id.
     */
    suspend fun saveDraft(
        to: String,
        subject: String,
        body: String
    ): DraftItem = withAutoRefresh { auth ->
        val rawMime = buildMimeMessage(to, subject, body)
        val encoded = Base64.encodeToString(
            rawMime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val response = gmailApiService.createDraft(
            auth  = auth,
            draft = DraftCreateRequest(message = SendMessageRequest(raw = encoded))
        )
        response.toDraftItem() ?: DraftItem(id = response.id, to = to, subject = subject, body = body)
    }

    /**
     * List all drafts, fetching each one's full content in parallel.
     * Returns an empty list (not an error) when the mailbox has no drafts.
     */
    suspend fun listDrafts(): List<DraftItem> = withAutoRefresh { auth ->
        val refs = gmailApiService.listDrafts(auth).drafts ?: return@withAutoRefresh emptyList()
        coroutineScope {
            refs.map { ref ->
                async {
                    runCatching { gmailApiService.getDraft(auth, ref.id).toDraftItem() }
                        .getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Replace the body of an existing draft.
     * Caller must supply the same [to] and [subject] if they haven't changed.
     */
    suspend fun updateDraft(
        draftId: String,
        to: String,
        subject: String,
        body: String
    ): DraftItem = withAutoRefresh { auth ->
        val rawMime = buildMimeMessage(to, subject, body)
        val encoded = Base64.encodeToString(
            rawMime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val response = gmailApiService.updateDraft(
            auth  = auth,
            id    = draftId,
            draft = DraftCreateRequest(message = SendMessageRequest(raw = encoded))
        )
        response.toDraftItem() ?: DraftItem(id = draftId, to = to, subject = subject, body = body)
    }

    /**
     * Send an existing draft using Gmail's send-draft API.
     * This moves the draft to Sent and removes the DRAFT label.
     */
    suspend fun sendDraft(draftId: String) = withAutoRefresh { auth ->
        gmailApiService.sendDraft(auth, SendDraftRequest(id = draftId))
    }

    /** Permanently delete a draft. */
    suspend fun deleteDraft(draftId: String) = withAutoRefresh { auth ->
        gmailApiService.deleteDraft(auth, draftId)
    }

    // ── MIME builder ──────────────────────────────────────────────────────

    private fun buildMimeMessage(
        to: String,
        subject: String,
        body: String,
        attachments: List<OutgoingAttachment> = emptyList()
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
