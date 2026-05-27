package com.example.voicegmail.gmail

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GmailApiService {

    @GET("gmail/v1/users/me/messages")
    suspend fun listMessages(
        @Header("Authorization") auth: String,
        @Query("maxResults") maxResults: Int = 20,
        @Query("labelIds") labelIds: String = "INBOX",
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

    /** Moves the message to the user's Trash. Google's API requires no request body. */
    @POST("gmail/v1/users/me/messages/{id}/trash")
    suspend fun trashMessage(
        @Header("Authorization") auth: String,
        @Path("id") id: String
    ): GmailMessage

    /** Adds or removes labels (e.g. remove UNREAD to mark as read). */
    @POST("gmail/v1/users/me/messages/{id}/modify")
    suspend fun modifyMessage(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Body request: ModifyLabelsRequest
    ): GmailMessage

    /** Downloads the raw (base64url-encoded) bytes of one attachment. */
    @GET("gmail/v1/users/me/messages/{messageId}/attachments/{attachmentId}")
    suspend fun getAttachment(
        @Header("Authorization") auth: String,
        @Path("messageId") messageId: String,
        @Path("attachmentId") attachmentId: String
    ): AttachmentResponse
}
