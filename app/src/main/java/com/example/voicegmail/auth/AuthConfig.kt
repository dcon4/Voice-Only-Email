package com.example.voicegmail.auth

import com.example.voicegmail.BuildConfig

object AuthConfig {
    val CLIENT_ID: String get() = BuildConfig.OAUTH_CLIENT_ID

    val REDIRECT_URI: String get() = "${BuildConfig.OAUTH_REDIRECT_SCHEME}:/oauth2redirect"

    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT          = "https://oauth2.googleapis.com/token"

    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.send",
        // Required for modifyMessage (mark as read / remove UNREAD label).
        "https://www.googleapis.com/auth/gmail.modify"
    )
}
