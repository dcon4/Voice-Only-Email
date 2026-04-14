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
        @Query("labelIds") labelIds: String = "INBOX"
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
}
