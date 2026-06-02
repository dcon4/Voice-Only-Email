package com.example.voicegmail.auth

import com.example.voicegmail.BuildConfig

object AuthConfig {
    val CLIENT_ID: String get() = BuildConfig.OAUTH_CLIENT_ID

    val REDIRECT_URI: String get() = "${BuildConfig.OAUTH_REDIRECT_SCHEME}:/oauth2redirect"

    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT          = "https://oauth2.googleapis.com/token"

    /**
     * Incremented every time [SCOPES] changes.  [AuthRepository] persists the
     * granted version in DataStore; if a returning user's stored version is
     * older than this constant, the app forces a single re-consent so the
     * new scope set is granted.  See AuthRepository.isReConsentRequired().
     */
    const val SCOPE_VERSION = 2

    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.send",
        // Required for modifyMessage (mark as read / remove UNREAD label).
        "https://www.googleapis.com/auth/gmail.modify",
        // SCOPE_VERSION 2: required for the voice-driven recipient matcher
        // in the compose flow to look up names against the user's Google
        // Contacts via the People API.
        "https://www.googleapis.com/auth/contacts.readonly"
    )
}
