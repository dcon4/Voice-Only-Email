package com.example.voicegmail.gmail

import com.google.gson.annotations.SerializedName

data class ListMessagesResponse(
    @SerializedName("messages") val messages: List<MessageRef>? = null,
    @SerializedName("nextPageToken") val nextPageToken: String? = null
)

data class MessageRef(
    @SerializedName("id") val id: String,
    @SerializedName("threadId") val threadId: String
)

data class GmailMessage(
    @SerializedName("id") val id: String = "",
    @SerializedName("threadId") val threadId: String = "",
    @SerializedName("labelIds") val labelIds: List<String>? = null,
    @SerializedName("snippet") val snippet: String = "",
    @SerializedName("payload") val payload: MessagePayload? = null
)

data class MessagePayload(
    @SerializedName("headers") val headers: List<MessageHeader>? = null,
    @SerializedName("body") val body: MessageBody? = null,
    @SerializedName("parts") val parts: List<MessagePart>? = null
)

data class MessageHeader(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: String
)

data class MessageBody(
    @SerializedName("data") val data: String? = null,
    @SerializedName("size") val size: Int = 0
)

data class MessagePart(
    @SerializedName("mimeType") val mimeType: String = "",
    @SerializedName("body") val body: MessageBody? = null,
    @SerializedName("parts") val parts: List<MessagePart>? = null
)

data class SendMessageRequest(
    @SerializedName("raw") val raw: String
)

data class EmailItem(
    val id: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val body: String
)

fun GmailMessage.toEmailItem(): EmailItem {
    val headers = payload?.headers ?: emptyList()
    val from = headers.find { it.name.equals("From", ignoreCase = true) }?.value ?: "Unknown"
    val subject = headers.find { it.name.equals("Subject", ignoreCase = true) }?.value ?: "(no subject)"
    val body = extractBody(payload)
    return EmailItem(
        id = id,
        from = from,
        subject = subject,
        snippet = snippet,
        body = body
    )
}

private fun extractBody(payload: MessagePayload?): String {
    if (payload == null) return ""
    // Try direct body first
    payload.body?.data?.let { data ->
        if (data.isNotBlank()) return decodeBase64(data)
    }
    // Try text/plain part
    payload.parts?.forEach { part ->
        if (part.mimeType == "text/plain") {
            part.body?.data?.let { data ->
                if (data.isNotBlank()) return decodeBase64(data)
            }
        }
    }
    // Try nested parts
    payload.parts?.forEach { part ->
        val nested = extractBody(MessagePayload(body = part.body, parts = part.parts))
        if (nested.isNotBlank()) return nested
    }
    return ""
}

private fun decodeBase64(encoded: String): String {
    return try {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE)
        String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}
