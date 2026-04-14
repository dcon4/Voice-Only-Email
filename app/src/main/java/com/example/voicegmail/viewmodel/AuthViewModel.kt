package com.example.voicegmail.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.auth.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenStore: TokenStore
) : ViewModel() {

    sealed class AuthState {
        object Idle : AuthState()
        object Loading : AuthState()
        object Authenticated : AuthState()
        data class Error(val message: String) : AuthState()
    }

    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state

    /** Check whether a token is already stored (persisted from a previous session). */
    fun checkExistingToken() {
        viewModelScope.launch {
            _state.value = if (authRepository.hasAccessToken()) {
                AuthState.Authenticated
            } else {
                AuthState.Idle
            }
        }
    }

    /** Build the OAuth authorization Intent to pass to startActivityForResult. */
    fun buildAuthIntent(context: Context): Intent {
        return authRepository.buildAuthIntent(context)
    }

    /** Handle the result from the OAuth browser redirect. */
    fun handleAuthResult(context: Context, resultIntent: Intent?) {
        if (resultIntent == null) {
            _state.value = AuthState.Error("Authentication cancelled")
            return
        }
        _state.value = AuthState.Loading
        viewModelScope.launch {
            runCatching {
                val tokenResponse = authRepository.exchangeCode(context, resultIntent)
                tokenStore.saveTokens(
                    accessToken = tokenResponse.accessToken ?: "",
                    refreshToken = tokenResponse.refreshToken,
                    idToken = tokenResponse.idToken
                )
            }.onSuccess {
                _state.value = AuthState.Authenticated
            }.onFailure { e ->
                _state.value = AuthState.Error(e.message ?: "Authentication failed")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _state.value = AuthState.Idle
        }
    }
}
