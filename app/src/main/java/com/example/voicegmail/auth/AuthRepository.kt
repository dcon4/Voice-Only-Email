package com.example.voicegmail.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.voicegmail.debug.DebugLogger
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
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "VoiceGmail.Auth"
private const val PREFS_AUTH_PENDING = "auth_pending"
private const val PREF_CODE_VERIFIER = "code_verifier"
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

    /**
     * Builds and returns the AppAuth intent that launches AuthorizationManagementActivity,
     * which opens a Chrome Custom Tab at Google's auth endpoint.
     *
     * The PKCE code verifier is persisted to SharedPreferences BEFORE the Chrome Tab
     * opens so it survives a process death (Android may kill the app while Chrome is in
     * the foreground). It is retrieved in [exchangeCodeFromRedirect] when the redirect
     * arrives back at MainActivity.onNewIntent.
     */
    fun buildSignInIntent(): android.content.Intent {
        val clientIdPrefix = AuthConfig.CLIENT_ID.take(12) + "…"
        DebugLogger.log(
            "Auth",
            "Building sign-in intent — " +
                "clientId(prefix)=$clientIdPrefix " +
                "redirectUri=${AuthConfig.REDIRECT_URI} " +
                "scopes=${AuthConfig.SCOPES.joinToString()}"
        )
        val request = buildAuthorizationRequest()
        // Persist the code verifier so it survives process death.
        // When the OAuth redirect arrives at MainActivity.onNewIntent the process may
        // have been killed and restarted, making any in-memory state unavailable.
        val prefs = context.getSharedPreferences(PREFS_AUTH_PENDING, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_CODE_VERIFIER, request.codeVerifier).apply()
        DebugLogger.log("Auth", "Code verifier persisted to SharedPreferences")
        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Exchanges the authorization code carried in [redirectUri] for access + refresh
     * tokens by building a direct TokenRequest — bypassing AppAuth's
     * AuthorizationManagementActivity entirely.
     *
     * This is called from MainActivity.onNewIntent via OAuthRedirectBus and is the
     * only path that works correctly when the app process dies while Chrome is open:
     * AppAuth's setResult() delivery would otherwise be silently dropped because
     * the new AuthorizationManagementActivity instance has no completedIntent.
     */
    suspend fun exchangeCodeFromRedirect(redirectUri: Uri): Pair<String?, String?> {
        val code = redirectUri.getQueryParameter("code")
            ?: error("No authorization code in redirect URI: $redirectUri")

        DebugLogger.log("Auth", "Exchanging code from MainActivity redirect")

        val prefs = context.getSharedPreferences(PREFS_AUTH_PENDING, Context.MODE_PRIVATE)
        val codeVerifier = prefs.getString(PREF_CODE_VERIFIER, null)
        DebugLogger.log("Auth", "Code verifier present=${codeVerifier != null}")

        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(AuthConfig.AUTHORIZATION_ENDPOINT),
            Uri.parse(AuthConfig.TOKEN_ENDPOINT)
        )
        val tokenRequest = TokenRequest.Builder(serviceConfig, AuthConfig.CLIENT_ID)
            .setGrantType(GrantTypeValues.AUTHORIZATION_CODE)
            .setAuthorizationCode(code)
            .setRedirectUri(Uri.parse(AuthConfig.REDIRECT_URI))
            .setCodeVerifier(codeVerifier)
            .build()

        return suspendCancellableCoroutine { cont ->
            authService.performTokenRequest(tokenRequest) { tokenResponse, ex ->
                when {
                    ex != null -> cont.resumeWithException(ex)
                    else -> cont.resume(Pair(tokenResponse?.accessToken, tokenResponse?.refreshToken))
                }
            }
        }
    }

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

    fun dispose() {
        authService.dispose()
    }
}
