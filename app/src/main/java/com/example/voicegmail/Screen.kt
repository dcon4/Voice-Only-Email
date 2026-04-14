package com.example.voicegmail

/** Navigation route constants for the Compose NavHost. */
object Screen {
    const val SIGN_IN = "sign_in"
    const val INBOX = "inbox"
    const val MESSAGE_DETAIL = "message_detail/{messageId}"
    const val COMPOSE = "compose"
    const val SETTINGS = "settings"

    fun messageDetail(messageId: String) = "message_detail/$messageId"
    fun composeWithTo(to: String) = "$COMPOSE?to=$to"
}
