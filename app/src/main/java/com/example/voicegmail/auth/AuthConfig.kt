package com.example.voicegmail.auth

/**
 * OAuth configuration for Google Sign-In.
 *
 * Before running, replace CLIENT_ID with your Google Cloud OAuth 2.0 Android client ID.
 * See README.md for full setup instructions.
 */
object AuthConfig {
    // TODO: Replace with your Google Cloud OAuth 2.0 Android Client ID
    // Format: XXXXXXXXXX-xxxxxxxxxxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com
    const val CLIENT_ID = "YOUR_CLIENT_ID.apps.googleusercontent.com"

    const val REDIRECT_URI = "com.example.voicegmail:/oauth2redirect"

    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.send"
    )
}
