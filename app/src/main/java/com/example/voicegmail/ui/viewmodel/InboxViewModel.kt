package com.example.voicegmail.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.gmail.EmailItem
import com.example.voicegmail.gmail.GmailRepository
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val gmailRepository: GmailRepository,
    private val voiceManager: VoiceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val uiState: StateFlow<InboxUiState> = _uiState

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn

    private val authService = AuthorizationService(context)

    init {
        viewModelScope.launch {
            authRepository.hasAccessToken().collect { hasToken ->
                _isSignedIn.value = hasToken
                if (hasToken) {
                    loadInbox()
                } else {
                    _uiState.value = InboxUiState.SignedOut
                }
            }
        }
    }

    fun getSignInIntent(): Intent {
        val request = authRepository.buildAuthorizationRequest()
        return authService.getAuthorizationRequestIntent(request)
    }

    fun handleSignInResult(result: ActivityResult) {
        val data = result.data ?: return
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        if (response != null) {
            viewModelScope.launch {
                try {
                    val (accessToken, refreshToken) = authRepository.exchangeCodeForTokens(authService, response)
                    if (accessToken != null) {
                        authRepository.saveTokens(accessToken, refreshToken)
                        loadInbox()
                    } else {
                        _uiState.value = InboxUiState.Error("Sign-in failed: could not get access token")
                    }
                } catch (e: Exception) {
                    _uiState.value = InboxUiState.Error("Sign-in failed: ${e.message}")
                }
            }
        } else {
            _uiState.value = InboxUiState.Error("Sign-in failed: ${exception?.message}")
        }
    }

    fun loadInbox() {
        viewModelScope.launch {
            _uiState.value = InboxUiState.Loading
            try {
                val emails = gmailRepository.listInbox()
                _uiState.value = InboxUiState.Success(emails)
                if (emails.isNotEmpty()) {
                    voiceManager.speak("You have ${emails.size} emails. First email from ${emails.first().from}: ${emails.first().subject}")
                } else {
                    voiceManager.speak("Your inbox is empty.")
                }
            } catch (e: Exception) {
                _uiState.value = InboxUiState.Error(e.message ?: "Failed to load inbox")
            }
        }
    }

    fun readEmailAloud(email: EmailItem) {
        voiceManager.speak("From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}")
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.clearTokens()
            _uiState.value = InboxUiState.SignedOut
        }
    }

    override fun onCleared() {
        super.onCleared()
        authService.dispose()
    }
}

sealed class InboxUiState {
    object Loading : InboxUiState()
    object SignedOut : InboxUiState()
    data class Success(val emails: List<EmailItem>) : InboxUiState()
    data class Error(val message: String) : InboxUiState()
}
