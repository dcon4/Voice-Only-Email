package com.example.voicegmail.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientSecretBasic
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "VoiceGmail.Auth"
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val accessTokenKey  = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")

    // Owned here so it lives for the whole app session and is properly disposed.
    private val authService = AuthorizationService(context)

    val accessTokenFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[accessTokenKey]
    }

    fun hasAccessToken(): Flow<Boolean> = context.dataStore.data.map { prefs ->
        !prefs[accessTokenKey].isNullOrBlank()
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String?) {
        context.dataStore.edit { prefs ->
            prefs[accessTokenKey] = accessToken
            refreshToken?.let { prefs[refreshTokenKey] = it }
        }
    }

    suspend fun clearTokens() {
        context.dataStore.edit { prefs ->
            prefs.remove(accessTokenKey)
            prefs.remove(refreshTokenKey)
        }
    }

    // ------------------------------------------------------------------
    // Sign-in flow
    // ------------------------------------------------------------------

    fun buildSignInIntent(): android.content.Intent =
        authService.getAuthorizationRequestIntent(buildAuthorizationRequest())

    private fun buildAuthorizationRequest(): AuthorizationRequest {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(AuthConfig.AUTHORIZATION_ENDPOINT),
            Uri.parse(AuthConfig.TOKEN_ENDPOINT)
        )
        return AuthorizationRequest.Builder(
            serviceConfig,
            AuthConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(AuthConfig.REDIRECT_URI)
        )
            .setScopes(AuthConfig.SCOPES)
            .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
            .build()
    }

    /** Exchange the authorization code returned by Google for an access + refresh token pair. */
    suspend fun exchangeCodeForTokens(
        response: AuthorizationResponse
    ): Pair<String?, String?> = suspendCancellableCoroutine { cont ->
        val tokenRequest = response.createTokenExchangeRequest()
        authService.performTokenRequest(tokenRequest) { tokenResponse, ex ->
            when {
                ex != null -> cont.resumeWithException(ex)
                else -> cont.resume(Pair(tokenResponse?.accessToken, tokenResponse?.refreshToken))
            }
        }
    }

    // ------------------------------------------------------------------
    // Silent token refresh
    // ------------------------------------------------------------------

    /**
     * Uses the stored refresh token to silently obtain a new access token from
     * Google. Saves the new tokens to DataStore and returns the fresh access
     * token, or null if no refresh token is stored or the refresh fails.
     *
     * Call this whenever an API request returns 401 Unauthorized.
     */
    suspend fun refreshAccessToken(): String? {
        val refreshToken = context.dataStore.data.map { it[refreshTokenKey] }.first()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "refreshAccessToken: no refresh token stored — user must sign in again")
            return null
        }

        return try {
            val serviceConfig = AuthorizationServiceConfiguration(
                Uri.parse(AuthConfig.AUTHORIZATION_ENDPOINT),
                Uri.parse(AuthConfig.TOKEN_ENDPOINT)
            )
            val refreshRequest = TokenRequest.Builder(serviceConfig, AuthConfig.CLIENT_ID)
                .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                .setRefreshToken(refreshToken)
                .build()

            suspendCancellableCoroutine { cont ->
                authService.performTokenRequest(refreshRequest) { tokenResponse, ex ->
                    when {
                        ex != null -> {
                            Log.e(TAG, "Token refresh failed: ${ex.message}", ex)
                            cont.resume(null)
                        }
                        else -> {
                            val newAccess = tokenResponse?.accessToken
                            // Google may or may not issue a new refresh token — keep the old
                            // one if a new one is not provided.
                            val newRefresh = tokenResponse?.refreshToken ?: refreshToken
                            cont.resume(Pair(newAccess, newRefresh))
                        }
                    }
                }
            }?.let { (newAccess, newRefresh) ->
                if (newAccess != null) {
                    Log.i(TAG, "Token refreshed successfully")
                    saveTokens(newAccess, newRefresh)
                    newAccess
                } else {
                    Log.w(TAG, "Token refresh returned null access token")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during token refresh", e)
            null
        }
    }

    /** Must be called when the owning component is destroyed (e.g. Application.onTerminate). */
    fun dispose() {
        authService.dispose()
    }
}
