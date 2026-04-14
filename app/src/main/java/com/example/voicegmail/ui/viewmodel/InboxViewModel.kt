package com.example.voicegmail.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.gmail.EmailItem
import com.example.voicegmail.gmail.GmailRepository
import com.example.voicegmail.voice.VoiceCommand
import com.example.voicegmail.voice.VoiceCommandEngine
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val voiceManager: VoiceManager,
    private val voiceCommandEngine: VoiceCommandEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val uiState: StateFlow<InboxUiState> = _uiState

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn

    private val _currentEmailIndex = MutableStateFlow(0)
    val currentEmailIndex: StateFlow<Int> = _currentEmailIndex

    /** One-shot navigation events emitted to the UI layer. */
    private val _navigationEvent = MutableSharedFlow<InboxNavEvent>(extraBufferCapacity = 1)
    val navigationEvent: SharedFlow<InboxNavEvent> = _navigationEvent

    private val authService = AuthorizationService(context)

    init {
        viewModelScope.launch {
            authRepository.hasAccessToken().collect { hasToken ->
                _isSignedIn.value = hasToken
                if (hasToken) {
                    loadInbox()
                } else {
                    _uiState.value = InboxUiState.SignedOut
                    voiceManager.speak("Please sign in to access your Gmail inbox.")
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
                        val msg = "Sign-in failed: could not get access token"
                        _uiState.value = InboxUiState.Error(msg)
                        voiceManager.speak(msg)
                    }
                } catch (e: Exception) {
                    val msg = "Sign-in failed: ${e.message}"
                    _uiState.value = InboxUiState.Error(msg)
                    voiceManager.speak(msg)
                }
            }
        } else {
            val msg = "Sign-in failed: ${exception?.message}"
            _uiState.value = InboxUiState.Error(msg)
            voiceManager.speak(msg)
        }
    }

    fun loadInbox() {
        viewModelScope.launch {
            _uiState.value = InboxUiState.Loading
            try {
                val emails = gmailRepository.listInbox()
                _currentEmailIndex.value = 0
                _uiState.value = InboxUiState.Success(emails)
                if (emails.isNotEmpty()) {
                    val prompt = "You have ${emails.size} email${if (emails.size == 1) "" else "s"}. " +
                        "Say 'read' to hear the first one, 'next' to move to the second email, " +
                        "'previous' to go back, 'refresh' to reload, or 'compose' to write a new email."
                    voiceCommandEngine.speakThenListen(prompt) { cmd -> handleCommand(cmd, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Your inbox is empty. Say 'refresh' to reload or 'compose' to write a new email."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load inbox"
                _uiState.value = InboxUiState.Error(msg)
                voiceManager.speak("Error loading inbox. $msg")
            }
        }
    }

    /**
     * Handles a recognised voice command in the context of the inbox email list.
     * After acting on the command, re-arms the microphone so the user can speak
     * the next command without any screen interaction.
     */
    fun handleCommand(command: VoiceCommand, emails: List<EmailItem> = currentEmails()) {
        when (command) {
            is VoiceCommand.Read -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null) {
                    val text = "Email ${_currentEmailIndex.value + 1} of ${emails.size}. " +
                        "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}"
                    voiceCommandEngine.speakThenListen(text) { cmd -> handleCommand(cmd, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No more emails. Say 'refresh' or 'compose'."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.Next -> {
                val nextIndex = _currentEmailIndex.value + 1
                val email = emails.getOrNull(nextIndex)
                if (email != null) {
                    _currentEmailIndex.value = nextIndex
                    voiceCommandEngine.speakThenListen(
                        "Email ${nextIndex + 1} of ${emails.size}. From ${email.from}: ${email.subject}. Say 'read' to hear the full message."
                    ) { cmd -> handleCommand(cmd, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "You are at the last email. Say 'previous', 'refresh', or 'compose'."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.Previous -> {
                val prevIndex = _currentEmailIndex.value - 1
                val email = emails.getOrNull(prevIndex)
                if (email != null) {
                    _currentEmailIndex.value = prevIndex
                    voiceCommandEngine.speakThenListen(
                        "Email ${prevIndex + 1} of ${emails.size}. From ${email.from}: ${email.subject}. Say 'read' to hear the full message."
                    ) { cmd -> handleCommand(cmd, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "You are at the first email. Say 'next', 'read', 'refresh', or 'compose'."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.Repeat -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null) {
                    val text = "Repeating email ${_currentEmailIndex.value + 1}. " +
                        "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}"
                    voiceCommandEngine.speakThenListen(text) { cmd -> handleCommand(cmd, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No email to repeat. Say 'refresh' or 'compose'."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.Refresh -> loadInbox()
            is VoiceCommand.Compose -> {
                viewModelScope.launch { _navigationEvent.emit(InboxNavEvent.NavigateToCompose) }
            }
            else -> {
                // Unrecognised — prompt again
                voiceCommandEngine.speakThenListen(
                    "Sorry, I didn't understand. Say 'read', 'next', 'previous', 'refresh', or 'compose'."
                ) { cmd -> handleCommand(cmd, emails) }
            }
        }
    }

    /** Tap-to-read kept for sighted-fallback interaction. */
    fun readEmailAloud(email: EmailItem) {
        val emails = currentEmails()
        val idx = emails.indexOf(email).takeIf { it >= 0 } ?: _currentEmailIndex.value
        _currentEmailIndex.value = idx
        val text = "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}"
        voiceCommandEngine.speakThenListen(text) { cmd -> handleCommand(cmd, emails) }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.clearTokens()
            _uiState.value = InboxUiState.SignedOut
        }
    }

    /** Stops all audio — useful when navigating away from the inbox. */
    fun stopVoice() = voiceCommandEngine.stopAll()

    private fun currentEmails(): List<EmailItem> =
        (_uiState.value as? InboxUiState.Success)?.emails ?: emptyList()

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

sealed class InboxNavEvent {
    object NavigateToCompose : InboxNavEvent()
}
