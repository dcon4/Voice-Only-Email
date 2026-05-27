package com.example.voicegmail.gmail

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface GmailApiService {

    @GET("gmail/v1/users/me/messages")
    suspend fun listMessages(
        @Header("Authorization") auth: String,
        @Query("maxResults") maxResults: Int = 20,
        @Query("labelIds") labelIds: String? = "INBOX",
        @Query("q") query: String? = null
    ): ListMessagesResponse

    @GET("gmail/v1/users/me/messages/{id}")
    suspend fun getMessage(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Query("format") format: String = "full"
    ): GmailMessage

    @POST("gmail/v1/users/me/messages/send")
    suspend fun sendMessage(
        @Header("Authorization") auth: String,
        @Body message: SendMessageRequest
    ): GmailMessage

    @POST("gmail/v1/users/me/messages/{id}/trash")
    suspend fun trashMessage(
        @Header("Authorization") auth: String,
        @Path("id") id: String
    ): GmailMessage

    @POST("gmail/v1/users/me/messages/{id}/modify")
    suspend fun modifyMessage(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Body request: ModifyLabelsRequest
    ): GmailMessage

    @GET("gmail/v1/users/me/messages/{messageId}/attachments/{attachmentId}")
    suspend fun getAttachment(
        @Header("Authorization") auth: String,
        @Path("messageId") messageId: String,
        @Path("attachmentId") attachmentId: String
    ): AttachmentResponse

    // ── Draft endpoints ───────────────────────────────────────────────────

    /** Create a new draft. Returns the saved draft with its Gmail id. */
    @POST("gmail/v1/users/me/drafts")
    suspend fun createDraft(
        @Header("Authorization") auth: String,
        @Body draft: DraftCreateRequest
    ): DraftResponse

    /**
     * List all drafts.
     * The list response only contains sparse message refs (id + threadId).
     * Use [getDraft] to obtain the full body for each one.
     */
    @GET("gmail/v1/users/me/drafts")
    suspend fun listDrafts(
        @Header("Authorization") auth: String,
        @Query("maxResults") maxResults: Int = 50
    ): ListDraftsResponse

    /** Fetch one draft with full message content (headers + body). */
    @GET("gmail/v1/users/me/drafts/{id}")
    suspend fun getDraft(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Query("format") format: String = "full"
    ): DraftResponse

    /** Replace the content of an existing draft. */
    @PUT("gmail/v1/users/me/drafts/{id}")
    suspend fun updateDraft(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Body draft: DraftCreateRequest
    ): DraftResponse

    /**
     * Send an existing draft.
     * This promotes the draft to a Sent message and removes the DRAFT label.
     */
    @POST("gmail/v1/users/me/drafts/send")
    suspend fun sendDraft(
        @Header("Authorization") auth: String,
        @Body request: SendDraftRequest
    ): GmailMessage

    /** Permanently delete a draft. */
    @DELETE("gmail/v1/users/me/drafts/{id}")
    suspend fun deleteDraft(
        @Header("Authorization") auth: String,
        @Path("id") id: String
    )
}
