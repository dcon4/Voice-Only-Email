package com.example.voicegmail.auth

import android.util.Log
import com.example.voicegmail.debug.DebugLogger
import net.openid.appauth.AuthorizationException

private const val TAG = "VoiceGmail.Auth"

/**
 * Maps AppAuth [AuthorizationException] values to user-friendly diagnostic
 * messages and logs structured error details so failures can be triaged from
 * both logcat and the on-device DebugLogger file without needing a debugger.
 *
 * Common root causes and their error codes:
 *  - `access_denied`  → the Gmail account is not in the OAuth test-user list
 *  - `invalid_client` → package name or SHA-1 fingerprint mismatch in Google Cloud
 *  - `invalid_grant`  → auth code already used / PKCE failure; sign-in again
 *  - USER_CANCELED    → user pressed back in the browser tab
 */
internal object OAuthDiagnostics {

    /**
     * Returns a plain-language message suitable for display in the UI and for
     * being spoken aloud, and emits a structured entry to both logcat and the
     * on-device DebugLogger file.
     */
    fun friendlyMessage(ex: AuthorizationException): String {
        val typeName = when (ex.type) {
            AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR -> "authorization"
            AuthorizationException.TYPE_OAUTH_TOKEN_ERROR         -> "token_exchange"
            AuthorizationException.TYPE_GENERAL_ERROR             -> "general"
            else                                                  -> "unknown(${ex.type})"
        }
        val detail = "type=$typeName code=${ex.code} " +
            "error='${ex.error ?: "none"}' " +
            "description='${ex.errorDescription ?: "none"}'"

        Log.e(TAG, "OAuth failure — $detail", ex)
        DebugLogger.logException("Auth", "OAuth failure — $detail", ex)

        val msg = when {
            isUserCanceled(ex) ->
                "Sign-in was canceled. Tap 'Sign in with Google' to try again."

            // access_denied from the authorization endpoint — most commonly the
            // Gmail account has not been added as a Test User on the OAuth consent
            // screen while the app is in "Testing" publishing status.
            ex.type == AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR &&
                ex.error == "access_denied" ->
                "Google denied access. Make sure your Gmail address is added as a " +
                    "Test User in the Google Cloud Console OAuth consent screen, or " +
                    "publish the consent screen."

            // invalid_client from the token endpoint — the most common cause is a
            // package name or SHA-1 fingerprint mismatch between the installed APK
            // and the Android OAuth client registered in Google Cloud Console.
            ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
                ex.error == "invalid_client" ->
                "Sign-in rejected (invalid_client). The package name or SHA-1 " +
                    "fingerprint of this APK does not match the Android OAuth client " +
                    "in Google Cloud Console. Verify both values, then uninstall and " +
                    "reinstall the app."

            // invalid_grant — authorization code already used, PKCE verifier mismatch,
            // or the user revoked access between the authorization and token steps.
            ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
                ex.error == "invalid_grant" ->
                "Authorization code was rejected (invalid_grant). Please sign in again."

            // Network error during any part of the flow.
            ex.type == AuthorizationException.TYPE_GENERAL_ERROR &&
                ex.errorDescription?.contains("network", ignoreCase = true) == true ->
                "A network error occurred during sign-in. Check your internet " +
                    "connection and try again."

            // Fallback: include all available technical detail so the user can
            // copy-paste or read it to a developer.
            else -> buildFallbackMessage(ex)
        }

        DebugLogger.log("Auth", "OAuth spoken message: $msg")
        return msg
    }

    // -------------------------------------------------------------------------

    private fun isUserCanceled(ex: AuthorizationException): Boolean =
        ex == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW ||
            (ex.type == AuthorizationException.TYPE_GENERAL_ERROR && ex.code == 1)

    private fun buildFallbackMessage(ex: AuthorizationException): String {
        val typeName = when (ex.type) {
            AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR -> "authorization"
            AuthorizationException.TYPE_OAUTH_TOKEN_ERROR -> "token exchange"
            else -> "OAuth (type=${ex.type}, code=${ex.code})"
        }
        val errorPart = ex.error?.let { " ($it)" } ?: ""
        val descPart = ex.errorDescription
            ?: ex.message?.takeIf { it.isNotBlank() }
            ?: "no description"
        return "Sign-in failed during $typeName$errorPart: $descPart. " +
            "Check the app's package name and SHA-1 fingerprint in Google Cloud Console, " +
            "verify the OAuth test-user list, and reinstall after any credential change."
    }
}
