package com.example.voicegmail.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_gmail_tokens")

/**
 * Persists OAuth tokens securely using Jetpack DataStore.
 * Tokens are stored in a private DataStore preferences file.
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private companion object {
        val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        val ID_TOKEN_KEY = stringPreferencesKey("id_token")
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String?, idToken: String?) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN_KEY] = accessToken
            if (refreshToken != null) prefs[REFRESH_TOKEN_KEY] = refreshToken
            if (idToken != null) prefs[ID_TOKEN_KEY] = idToken
        }
    }

    suspend fun getAccessToken(): String? =
        context.dataStore.data.map { it[ACCESS_TOKEN_KEY] }.firstOrNull()

    suspend fun getRefreshToken(): String? =
        context.dataStore.data.map { it[REFRESH_TOKEN_KEY] }.firstOrNull()

    suspend fun clearTokens() {
        context.dataStore.edit { it.clear() }
    }
}
