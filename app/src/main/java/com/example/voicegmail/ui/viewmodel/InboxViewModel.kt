package com.example.voicegmail.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.BuildConfig
import com.example.voicegmail.auth.AuthConfig
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.auth.OAuthDiagnostics
import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.debug.toDebugString
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

private const val TAG = "VoiceGmail.Auth"

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

    private val _isAuthInProgress = MutableStateFlow(false)
    val isAuthInProgress: StateFlow<Boolean> = _isAuthInProgress

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

    fun launchSignIn(launchActivityIntent: (Intent) -> Unit) {
        DebugLogger.log("Auth", "Sign-in requested — isAuthInProgress=${_isAuthInProgress.value}")
        if (_isAuthInProgress.value) {
            DebugLogger.log("Auth", "Duplicate sign-in launch blocked")
            return
        }

        try {
            setAuthInProgress(true, "sign-in intent requested")
            launchActivityIntent(getSignInIntent())
        } catch (e: Exception) {
            setAuthInProgress(false, "sign-in intent creation failed")
            DebugLogger.logException("Auth", "Failed to create sign-in intent — verify OAuth configuration", e)
        }
    }

    private fun getSignInIntent(): Intent {
        val request = authRepository.buildAuthorizationRequest()
        DebugLogger.log(
            "Auth",
            "Auth request launched — package=${context.packageName}, debug=${BuildConfig.DEBUG}, " +
                "redirect=${request.redirectUri}, scopeCount=${request.scope?.split(' ')?.size ?: 0}, " +
                "scopes=${request.scope}, codeVerifierPresent=${!request.codeVerifier.isNullOrBlank()}, " +
                "clientIdConfigured=${AuthConfig.CLIENT_ID.isNotBlank()}"
        )
        return authService.getAuthorizationRequestIntent(request)
    }

    fun handleSignInResult(result: ActivityResult) {
        val data = result.data
        DebugLogger.log(
            "Auth",
            "Sign-in activity result — resultCode=${result.resultCode}, isAuthInProgress=${_isAuthInProgress.value}, hasData=${data != null}, " +
                data.toDebugString()
        )
        if (data == null) {
            DebugLogger.log("Auth", "Sign-in result returned without intent data")
            Log.w(TAG, "handleSignInResult: result.data is null (resultCode=${result.resultCode}); sign-in flow was interrupted or canceled")
            setAuthInProgress(false, "sign-in result missing intent data")
            val msg = "Sign-in was canceled. Tap 'Sign in with Google' to try again."
            _uiState.value = InboxUiState.Error(msg, isAuthError = true)
            voiceManager.speak(msg)
            return
        }

        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        DebugLogger.log(
            "Auth",
            "Redirect received — response=${response != null}, hasAuthCode=${!response?.authorizationCode.isNullOrBlank()}, " +
                "hasState=${!response?.state.isNullOrBlank()}, exception=${exception?.toDebugString()}"
        )
        Log.d(TAG, "handleSignInResult: hasResponse=${response != null} hasException=${exception != null}")

        when {
            response != null -> {
                viewModelScope.launch {
                    try {
                        DebugLogger.log(
                            "Auth",
                            "Starting token exchange — grantType=authorization_code, hasAuthCode=${!response.authorizationCode.isNullOrBlank()}"
                        )
                        Log.d(TAG, "Exchanging authorization code for tokens")
                        val (accessToken, refreshToken) = authRepository.exchangeCodeForTokens(authService, response)
                        if (accessToken != null) {
                            DebugLogger.log(
                                "Auth",
                                "Token exchange success — accessTokenPresent=true, refreshTokenPresent=${refreshToken != null}"
                            )
                            Log.i(TAG, "Token exchange succeeded; saving tokens and loading inbox")
                            setAuthInProgress(false, "token exchange succeeded")
                            authRepository.saveTokens(accessToken, refreshToken)
                            loadInbox()
                        } else {
                            DebugLogger.log("Auth", "Token exchange failed — null access token")
                            Log.e(TAG, "Token exchange returned no access token (hasRefreshToken=${refreshToken != null})")
                            setAuthInProgress(false, "token exchange returned null access token")
                            val msg = "Sign-in failed: Google returned no access token. " +
                                "Verify that the Gmail API is enabled and the OAuth scopes " +
                                "are approved in Google Cloud Console."
                            _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                            voiceManager.speak(msg)
                        }
                    } catch (e: AuthorizationException) {
                        setAuthInProgress(false, "token exchange authorization exception")
                        DebugLogger.logException("Auth", "Token exchange AuthorizationException", e)
                        val msg = OAuthDiagnostics.friendlyMessage(e)
                        _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                        voiceManager.speak(msg)
                    } catch (e: Exception) {
                        setAuthInProgress(false, "token exchange threw exception")
                        DebugLogger.logException("Auth", "Token exchange exception", e)
                        Log.e(TAG, "Unexpected error during token exchange", e)
                        val msg = "Sign-in failed: ${e.message ?: "unexpected error during token exchange"}. Please try again."
                        _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                        voiceManager.speak(msg)
                    }
                }
            }

            exception != null -> {
                DebugLogger.log("Auth", "Sign-in failed — no auth response: ${exception.toDebugString()}")
                setAuthInProgress(false, "auth callback returned authorization exception")
                val msg = OAuthDiagnostics.friendlyMessage(exception)
                _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                voiceManager.speak(msg)
            }

            else -> {
                DebugLogger.log("Auth", "Sign-in failed — unexpected state with neither auth response nor exception")
                Log.e(TAG, "handleSignInResult: unexpected state with neither response nor exception; ${data.toDebugString()}")
                setAuthInProgress(false, "auth callback missing authorization response and exception")
                val msg = "Sign-in failed: no response received from Google. Please try again."
                _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                voiceManager.speak(msg)
            }
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

    /**
     * Builds a share [Intent] for the debug log file.
     * Returns null in release builds (where [DebugLogger.getLogFile] is always null)
     * or when the log file does not yet exist.
     */
    fun getShareLogIntent(): Intent? {
        val file = DebugLogger.getLogFile()?.takeIf { it.exists() } ?: return null
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.debug.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun currentEmails(): List<EmailItem> =
        (_uiState.value as? InboxUiState.Success)?.emails ?: emptyList()

    override fun onCleared() {
        super.onCleared()
        authService.dispose()
    }

    private fun setAuthInProgress(inProgress: Boolean, reason: String) {
        val previous = _isAuthInProgress.value
        _isAuthInProgress.value = inProgress
        DebugLogger.log(
            "Auth",
            "Auth in progress changed — previous=$previous, current=$inProgress, reason=$reason"
        )
    }
}

sealed class InboxUiState {
    object Loading : InboxUiState()
    object SignedOut : InboxUiState()
    data class Success(val emails: List<EmailItem>) : InboxUiState()
    data class Error(val message: String, val isAuthError: Boolean = false) : InboxUiState()
}

sealed class InboxNavEvent {
    object NavigateToCompose : InboxNavEvent()
}
