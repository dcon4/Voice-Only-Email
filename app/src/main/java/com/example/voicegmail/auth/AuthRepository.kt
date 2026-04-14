package com.example.voicegmail.auth

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")

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

    fun buildAuthorizationRequest(): AuthorizationRequest {
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

    suspend fun exchangeCodeForTokens(
        authService: AuthorizationService,
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
}
