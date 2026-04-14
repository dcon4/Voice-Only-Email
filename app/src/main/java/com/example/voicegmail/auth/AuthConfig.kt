package com.example.voicegmail.auth

import com.example.voicegmail.BuildConfig

/**
 * OAuth configuration for Google Sign-In.
 *
 * Before running, you must make two edits:
 *
 * 1. Replace CLIENT_ID below with your Google Cloud OAuth 2.0 Android client ID.
 *    Format: XXXXXXXXXX-xxxxxxxxxxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com
 *
 * 2. In app/build.gradle.kts, replace YOUR_CLIENT_ID_PREFIX with the part of
 *    your client ID that comes *before* ".apps.googleusercontent.com".
 *    Example: if CLIENT_ID = "123-abc.apps.googleusercontent.com"
 *             then the prefix = "123-abc"
 *
 * See README.md for full step-by-step setup instructions.
 */
object AuthConfig {
    // TODO: Replace with your Google Cloud OAuth 2.0 Android Client ID
    // Example: "123456789000-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com"
    const val CLIENT_ID = "YOUR_CLIENT_ID_PREFIX.apps.googleusercontent.com"

    // Derived from the manifest placeholder set in app/build.gradle.kts.
    // The reverse-client-ID scheme (com.googleusercontent.apps.<prefix>) is the
    // only redirect URI that Google accepts for Android OAuth clients.
    val REDIRECT_URI: String get() = "${BuildConfig.OAUTH_REDIRECT_SCHEME}:/oauth2redirect"

    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.send"
    )
}
