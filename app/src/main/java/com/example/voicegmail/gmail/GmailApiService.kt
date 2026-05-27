package com.example.voicegmail.gmail

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GmailApiService {

    /**
     * Lists messages in the user's mailbox.
     *
     * [labelIds] filters to a specific label (e.g. "INBOX"). Pass **null** to
     * search across all labels (required for the search feature so results are
     * not restricted to the inbox).
     *
     * [query] is a Gmail search query string (e.g. `from:david is:unread`).
     * Retrofit omits null @Query parameters, so callers that don't need a
     * filter should leave it at the default null.
     */
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

    /** Adds or removes labels (e.g. remove UNREAD to mark as read). Requires gmail.modify scope. */
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
}
