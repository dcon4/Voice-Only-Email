package com.example.voicegmail.auth

import com.example.voicegmail.BuildConfig

/**
 * OAuth configuration for Google Sign-In.
 *
 * The values come from BuildConfig so debug and release builds can use the
 * correct Google OAuth configuration for their signing certificates.
 */
object AuthConfig {
    val CLIENT_ID: String get() = BuildConfig.OAUTH_CLIENT_ID

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
