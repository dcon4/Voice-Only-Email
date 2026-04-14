package com.example.voicegmail.auth

import com.example.voicegmail.BuildConfig

/**
 * OAuth configuration for Google Sign-In.
 *
 * CLIENT_ID and REDIRECT_URI are injected at build time from the OAUTH_CLIENT_ID
 * environment variable (see app/build.gradle.kts).
 *
 * For local development set the env var before building:
 *   export OAUTH_CLIENT_ID="123456789000-abcdefghijklmnop.apps.googleusercontent.com"
 *
 * For CI add a repository secret named OAUTH_CLIENT_ID in
 *   Settings → Secrets and variables → Actions.
 *
 * See README.md for full setup instructions.
 */
object AuthConfig {
    // Injected via buildConfigField from the OAUTH_CLIENT_ID environment variable.
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
