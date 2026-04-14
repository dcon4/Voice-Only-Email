package com.example.voicegmail.auth

/**
 * OAuth 2.0 configuration for Gmail access via AppAuth.
 *
 * TODO: Replace CLIENT_ID with your Android OAuth 2.0 Client ID from Google Cloud Console.
 *
 * Setup steps:
 *  1. Go to https://console.cloud.google.com/apis/credentials
 *  2. Create an "OAuth 2.0 Client ID" of type "Android"
 *  3. Set package name to "com.example.voicegmail"
 *  4. Add your app's SHA-1 fingerprint (from `./gradlew signingReport`)
 *  5. Enable the Gmail API under "APIs & Services → Library"
 *  6. Paste the generated client ID below (it looks like: NUMBERS-HASH.apps.googleusercontent.com)
 *
 * IMPORTANT: Do NOT commit your real Client ID to a public repository.
 */
object AuthConfig {
    // Replace with your Android OAuth 2.0 Client ID
    const val CLIENT_ID = "YOUR_ANDROID_OAUTH_CLIENT_ID.apps.googleusercontent.com"

    const val REDIRECT_URI = "com.example.voicegmail:/oauth2redirect"

    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.send"
    )
}
