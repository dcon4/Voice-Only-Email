package com.example.voicegmail.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles OAuth 2.0 Authorization Code + PKCE flow using AppAuth.
 *
 * Usage:
 *  1. Call [buildAuthIntent] and launch it via ActivityResultLauncher.
 *  2. On result, call [exchangeCode] with the returned Intent.
 *  3. Tokens are returned; persist them via [TokenStore].
 */
@Singleton
class AuthRepository @Inject constructor(
    private val tokenStore: TokenStore
) {

    /** Build the authorization Intent to open the browser for sign-in. */
    fun buildAuthIntent(context: Context): Intent {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(AuthConfig.AUTH_ENDPOINT),
            Uri.parse(AuthConfig.TOKEN_ENDPOINT)
        )

        val codeVerifier = CodeVerifierUtil.generateRandomCodeVerifier()
        val codeChallenge = CodeVerifierUtil.deriveCodeVerifierChallenge(codeVerifier)

        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            AuthConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(AuthConfig.REDIRECT_URI)
        )
            .setScope(AuthConfig.SCOPES.joinToString(" "))
            .setCodeVerifier(codeVerifier, codeChallenge, "S256")
            .build()

        val authService = AuthorizationService(context)
        return authService.getAuthorizationRequestIntent(authRequest)
    }

    /**
     * Exchange the authorization code (from the redirect Intent) for access + refresh tokens.
     * Returns [TokenResponse] on success or throws on error.
     */
    suspend fun exchangeCode(context: Context, resultIntent: Intent): TokenResponse {
        val authResponse = AuthorizationResponse.fromIntent(resultIntent)
            ?: throw IllegalStateException("No authorization response in intent")

        val authException = AuthorizationException.fromIntent(resultIntent)
        if (authException != null) throw authException

        val tokenRequest = authResponse.createTokenExchangeRequest()

        return suspendCancellableCoroutine { cont ->
            val authService = AuthorizationService(context)
            authService.performTokenRequest(tokenRequest) { response, ex ->
                authService.dispose()
                when {
                    response != null -> cont.resume(response)
                    ex != null -> cont.resumeWithException(ex)
                    else -> cont.resumeWithException(
                        IllegalStateException("Token exchange returned no response")
                    )
                }
            }
        }
    }

    /** @return true if an access token is currently stored. */
    suspend fun hasAccessToken(): Boolean = tokenStore.getAccessToken() != null

    /** Clear stored tokens (sign out). */
    suspend fun signOut() = tokenStore.clearTokens()
}
