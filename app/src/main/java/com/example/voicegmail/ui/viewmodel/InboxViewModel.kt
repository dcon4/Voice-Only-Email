package com.example.voicegmail.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.BuildConfig
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.auth.OAuthDiagnostics
import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.gmail.EmailItem
import com.example.voicegmail.gmail.GmailRepository
import com.example.voicegmail.voice.ForwardDraft
import com.example.voicegmail.voice.NaturalLanguageQueryParser
import com.example.voicegmail.voice.VoiceCommand
import com.example.voicegmail.voice.VoiceCommandEngine
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import javax.inject.Inject

private const val TAG = "VoiceGmail.Auth"

@HiltViewModel
class InboxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val gmailRepository: GmailRepository,
    private val voiceManager: VoiceManager,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val queryParser: NaturalLanguageQueryParser,
    private val forwardDraft: ForwardDraft
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

    init {
        viewModelScope.launch {
            val hasToken = authRepository.hasAccessToken().first()
            _isSignedIn.value = hasToken
            if (hasToken) {
                loadInbox()
            } else {
                _uiState.value = InboxUiState.SignedOut
                voiceManager.speak("Please sign in to access your Gmail inbox.")
            }
        }
    }

    fun getSignInIntent(): Intent {
        DebugLogger.log("Auth", "Sign-in button pressed")
        return authRepository.buildSignInIntent()
    }

    fun handleSignInResult(result: ActivityResult) {
        if (result.data == null) {
            Log.w(TAG, "handleSignInResult: result.data is null (resultCode=${result.resultCode})")
            DebugLogger.log("Auth", "Sign-in flow interrupted — result.data is null")
            val msg = "Sign-in was canceled. Tap 'Sign in with Google' to try again."
            _uiState.value = InboxUiState.Error(msg, isAuthError = true)
            voiceManager.speak(msg)
            return
        }

        val data = result.data!!
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)

        DebugLogger.log("Auth", "Redirect received — hasResponse=${response != null}, hasException=${exception != null}")

        Log.d(TAG, "handleSignInResult: hasResponse=${response != null} hasException=${exception != null}")

        when {
            response != null -> {
                viewModelScope.launch {
                    try {
                        Log.d(TAG, "Exchanging authorization code for tokens")
                        DebugLogger.log("Auth", "Exchanging authorization code for tokens...")
                        val (accessToken, refreshToken) = authRepository.exchangeCodeForTokens(response)
                        if (accessToken != null) {
                            DebugLogger.log("Auth", "Token exchange success")
                            authRepository.saveTokens(accessToken, refreshToken)
                            _isSignedIn.value = true
                            loadInbox()
                        } else {
                            val msg = "Sign-in failed: Google returned no access token. " +
                                "Verify that the Gmail API is enabled and the OAuth scopes " +
                                "are approved in Google Cloud Console."
                            _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                            voiceManager.speak(msg)
                        }
                    } catch (e: AuthorizationException) {
                        val msg = OAuthDiagnostics.friendlyMessage(e)
                        _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                        voiceManager.speak(msg)
                    } catch (e: Exception) {
                        val msg = "Sign-in failed: ${e.message ?: "unexpected error"}. Please try again."
                        _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                        voiceManager.speak(msg)
                    }
                }
            }
            exception != null -> {
                val msg = OAuthDiagnostics.friendlyMessage(exception)
                _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                voiceManager.speak(msg)
            }
            else -> {
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
                        "Say 'read' to hear the first one, 'next' to move to the next, " +
                        "'reply' to reply, 'delete' to delete, 'refresh' to reload, or 'compose' to write a new email."
                    voiceCommandEngine.speakThenListen(prompt) { cmd -> handleCommand(cmd, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Your inbox is empty. Say 'refresh' to reload or 'compose' to write a new email."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load inbox"
                val isAuthError = msg.contains("401", ignoreCase = true) ||
                    msg.contains("403", ignoreCase = true) ||
                    msg.contains("Not authenticated", ignoreCase = true) ||
                    msg.contains("Unauthorized", ignoreCase = true)
                if (isAuthError) {
                    authRepository.clearTokens()
                    _isSignedIn.value = false
                    val authMsg = "Your session has expired. Please sign in again."
                    _uiState.value = InboxUiState.Error(authMsg, isAuthError = true)
                    voiceManager.speak(authMsg)
                } else {
                    _uiState.value = InboxUiState.Error(msg)
                    voiceManager.speak("Error loading inbox. $msg")
                }
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
                    val unreadPrefix = if (email.isUnread) "Unread. " else ""
                    val text = "${unreadPrefix}Email ${_currentEmailIndex.value + 1} of ${emails.size}. " +
                        "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}. " +
                        "Say 'reply' to reply, 'forward' to forward, 'delete' to delete, " +
                        (if (email.isUnread) "'mark as read' to clear the unread badge, " else "") +
                        "'next' for the next email, or 'repeat' to hear this again."
                    voiceCommandEngine.speakThenListen(text) { cmd -> handleCommand(cmd, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No more emails. Say 'refresh' or 'compose'."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.Reply -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null) {
                    viewModelScope.launch {
                        _navigationEvent.emit(InboxNavEvent.NavigateToCompose(replyTo = email.from))
                    }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No email selected to reply to. Say 'read' to hear the current email first."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.Delete -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null) {
                    // Ask for confirmation — accidental deletion is serious for a blind user.
                    voiceCommandEngine.speakThenListen(
                        "Delete email from ${email.from}, subject: ${email.subject}? " +
                            "Say 'yes' to confirm or 'cancel' to go back."
                    ) { cmd -> handleDeleteConfirmation(cmd, email, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No email selected to delete. Say 'read' to hear an email first."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.Next -> {
                val nextIndex = _currentEmailIndex.value + 1
                val email = emails.getOrNull(nextIndex)
                if (email != null) {
                    _currentEmailIndex.value = nextIndex
                    voiceCommandEngine.speakThenListen(
                        "Email ${nextIndex + 1} of ${emails.size}. From ${email.from}: ${email.subject}. " +
                            "Say 'read' to hear the full message, 'reply' to reply, or 'delete' to delete."
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
                        "Email ${prevIndex + 1} of ${emails.size}. From ${email.from}: ${email.subject}. " +
                            "Say 'read' to hear the full message, 'reply' to reply, or 'delete' to delete."
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
            is VoiceCommand.MarkAsRead -> markCurrentEmailAsRead(emails)
            is VoiceCommand.Forward -> forwardCurrentEmail(emails)
            is VoiceCommand.Search -> startSearch(emails)
            is VoiceCommand.Refresh -> loadInbox()
            is VoiceCommand.Compose -> {
                viewModelScope.launch {
                    _navigationEvent.emit(InboxNavEvent.NavigateToCompose(replyTo = null))
                }
            }
            else -> {
                voiceCommandEngine.speakThenListen(
                    "Sorry, I didn't understand. Say 'read', 'reply', 'delete', 'search', 'next', 'previous', 'refresh', or 'compose'."
                ) { cmd -> handleCommand(cmd, emails) }
            }
        }
    }

    /**
     * Handles the yes/cancel response after the user said "delete".
     * On confirmation: calls the API, removes the email from the local list
     * immediately (no reload needed), then auto-reads the next email or
     * announces the inbox is empty.
     */
    private fun handleDeleteConfirmation(
        cmd: VoiceCommand,
        email: EmailItem,
        emails: List<EmailItem>
    ) {
        when (cmd) {
            is VoiceCommand.Confirm -> {
                viewModelScope.launch {
                    try {
                        gmailRepository.trashEmail(email.id)

                        // Remove from the local list immediately so the user
                        // doesn't need to wait for a full reload.
                        val updated = emails.toMutableList().also { it.remove(email) }

                        // Clamp index so it stays in bounds after removal.
                        val newIndex = _currentEmailIndex.value.coerceAtMost(
                            (updated.size - 1).coerceAtLeast(0)
                        )
                        _currentEmailIndex.value = newIndex
                        _uiState.value = InboxUiState.Success(updated)

                        if (updated.isEmpty()) {
                            voiceCommandEngine.speakThenListen(
                                "Email deleted. Your inbox is now empty. " +
                                    "Say 'refresh' to reload or 'compose' to write a new email."
                            ) { nextCmd -> handleCommand(nextCmd, updated) }
                        } else {
                            val next = updated[newIndex]
                            voiceCommandEngine.speakThenListen(
                                "Email deleted. ${updated.size} email${if (updated.size == 1) "" else "s"} remaining. " +
                                    "Now on email ${newIndex + 1}, from ${next.from}: ${next.subject}. " +
                                    "Say 'read' to hear it, 'reply' to reply, or 'delete' to delete it."
                            ) { nextCmd -> handleCommand(nextCmd, updated) }
                        }
                    } catch (e: Exception) {
                        val msg = e.message ?: "Failed to delete email"
                        voiceCommandEngine.speakThenListen(
                            "Could not delete the email. $msg. Say 'try again' or 'cancel'."
                        ) { nextCmd ->
                            when (nextCmd) {
                                is VoiceCommand.TryAgain -> handleDeleteConfirmation(
                                    VoiceCommand.Confirm, email, emails
                                )
                                else -> voiceCommandEngine.speakThenListen(
                                    "Deletion cancelled."
                                ) { retryCmd -> handleCommand(retryCmd, emails) }
                            }
                        }
                    }
                }
            }
            is VoiceCommand.Cancel -> {
                voiceCommandEngine.speakThenListen(
                    "Deletion cancelled. Email from ${email.from} kept."
                ) { nextCmd -> handleCommand(nextCmd, emails) }
            }
            else -> {
                // Any other word — ask once more, then cancel to be safe.
                voiceCommandEngine.speakThenListen(
                    "Say 'yes' to confirm deletion or 'cancel' to keep the email."
                ) { nextCmd -> handleDeleteConfirmation(nextCmd, email, emails) }
            }
        }
    }

    /**
     * Marks the current email as read by removing the UNREAD label.
     * Updates the local list immediately — no reload needed.
     */
    private fun markCurrentEmailAsRead(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen(
                "No email selected. Say 'read' to hear an email first."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        if (!email.isUnread) {
            voiceCommandEngine.speakThenListen(
                "This email is already marked as read."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        viewModelScope.launch {
            try {
                gmailRepository.markAsRead(email.id)
                // Update the local copy so the UI reflects the change without a reload.
                val updated = emails.toMutableList()
                updated[_currentEmailIndex.value] = email.copy(isUnread = false)
                _uiState.value = InboxUiState.Success(updated)
                voiceCommandEngine.speakThenListen(
                    "Marked as read. Email from ${email.from}, subject: ${email.subject}. " +
                        "Say 'reply' to reply, 'delete' to delete, 'next' for the next email, or 'repeat' to hear it again."
                ) { cmd -> handleCommand(cmd, updated) }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to mark as read"
                voiceCommandEngine.speakThenListen(
                    "Could not mark as read. $msg. Say 'try again' or 'cancel'."
                ) { cmd ->
                    when (cmd) {
                        is VoiceCommand.TryAgain -> markCurrentEmailAsRead(emails)
                        else -> voiceCommandEngine.speakThenListen(
                            "Okay."
                        ) { nextCmd -> handleCommand(nextCmd, emails) }
                    }
                }
            }
        }
    }

    /**
     * Asks the user for a recipient address then builds a forward draft and
     * navigates to the compose screen. The body is stored in [ForwardDraft]
     * to avoid URL-encoding / nav-arg length problems.
     */
    private fun forwardCurrentEmail(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen(
                "No email selected. Say 'read' to hear an email first."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        voiceCommandEngine.speakThenListen(
            "Forwarding email from ${email.from}, subject: ${email.subject}. " +
                "Who would you like to forward this to? Say the email address."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.Cancel -> voiceCommandEngine.speakThenListen(
                    "Forward cancelled."
                ) { next -> handleCommand(next, emails) }
                is VoiceCommand.FreeText -> launchForward(email, cmd.text, emails)
                else -> {
                    val raw = voiceManager.recognizedText.value ?: ""
                    if (raw.isNotBlank()) {
                        launchForward(email, raw, emails)
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "I didn't catch that. Please say the recipient's email address, or say cancel."
                        ) { retry ->
                            when (retry) {
                                is VoiceCommand.Cancel -> voiceCommandEngine.speakThenListen(
                                    "Forward cancelled."
                                ) { next -> handleCommand(next, emails) }
                                is VoiceCommand.FreeText -> launchForward(email, retry.text, emails)
                                else -> voiceCommandEngine.speakThenListen(
                                    "Forward cancelled."
                                ) { next -> handleCommand(next, emails) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun launchForward(email: EmailItem, to: String, emails: List<EmailItem>) {
        val forwardedBody = buildString {
            appendLine()
            appendLine("---------- Forwarded message ---------")
            appendLine("From: ${email.from}")
            appendLine("Subject: ${email.subject}")
            appendLine()
            append(email.body)
        }
        forwardDraft.set(
            to = to,
            subject = "Fwd: ${email.subject}",
            body = forwardedBody
        )
        viewModelScope.launch {
            _navigationEvent.emit(InboxNavEvent.NavigateToCompose(isForward = true))
        }
    }

    /**
     * Prompts the user to speak a search query, calls the Gmail API, then
     * narrates the results using the same read/next/previous/reply/delete flow.
     * "Search again" or "search" within results re-enters this function.
     * "Back to inbox" or "inbox" returns to the full inbox view.
     *
     * [originEmails] is the list to return to if the user says "inbox".
     */
    private fun startSearch(originEmails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen(
            "What would you like to search for? " +
                "You can say things like 'emails from David', 'subject meeting', or 'unread emails'."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.FreeText -> executeSearch(cmd.text, originEmails)
                is VoiceCommand.Cancel -> {
                    // Return to whichever list we came from.
                    voiceCommandEngine.speakThenListen(
                        "Search cancelled."
                    ) { nextCmd -> handleCommand(nextCmd, originEmails) }
                }
                else -> {
                    // The query might have been short (e.g. a name) and parsed as a
                    // command keyword — use the raw recognised text as the query.
                    val raw = voiceManager.recognizedText.value ?: ""
                    if (raw.isNotBlank()) {
                        executeSearch(raw, originEmails)
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "I didn't catch that. Please say what you'd like to search for, or say cancel."
                        ) { retry ->
                            val rawRetry = voiceManager.recognizedText.value ?: ""
                            if (rawRetry.isNotBlank()) executeSearch(rawRetry, originEmails)
                            else handleCommand(retry, originEmails)
                        }
                    }
                }
            }
        }
    }

    private fun executeSearch(rawQuery: String, originEmails: List<EmailItem>) {
        viewModelScope.launch {
            // Translate natural language → Gmail query syntax on the fly.
            val query = queryParser.parse(rawQuery)
            voiceManager.speak("Searching…")
            try {
                val results = gmailRepository.searchEmails(query)
                if (results.isEmpty()) {
                    voiceCommandEngine.speakThenListen(
                        "No emails found matching '$query'. " +
                            "Say 'search again' to try a different search, or 'inbox' to go back."
                    ) { cmd ->
                        when {
                            cmd is VoiceCommand.Search -> startSearch(originEmails)
                            cmd is VoiceCommand.Cancel ||
                                (cmd is VoiceCommand.FreeText &&
                                    cmd.text.trim().lowercase().contains("inbox")) ->
                                handleCommand(VoiceCommand.Refresh, originEmails)
                            else -> handleCommand(cmd, originEmails)
                        }
                    }
                } else {
                    val first = results[0]
                    val intro = "Found ${results.size} email${if (results.size == 1) "" else "s"} " +
                        "matching '$query'. " +
                        "Email 1 of ${results.size}, from ${first.from}: ${first.subject}. " +
                        "Say 'read' to hear it, 'next' for the next result, " +
                        "'reply' to reply, 'delete' to delete, " +
                        "or 'search again' to search for something else."
                    // Reset index to the top of results.
                    _currentEmailIndex.value = 0
                    // Surface results as the active list so all commands work naturally.
                    _uiState.value = InboxUiState.Success(results)
                    voiceCommandEngine.speakThenListen(intro) { cmd ->
                        // "search again" re-enters search; any other command uses results list.
                        if (cmd is VoiceCommand.Search) {
                            startSearch(originEmails)
                        } else {
                            handleCommand(cmd, results)
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Search failed"
                voiceCommandEngine.speakThenListen(
                    "Search failed. $msg. Say 'try again' to search again or 'cancel' to go back."
                ) { cmd ->
                    when (cmd) {
                        is VoiceCommand.TryAgain -> executeSearch(query, originEmails)
                        else -> handleCommand(VoiceCommand.Refresh, originEmails)
                    }
                }
            }
        }
    }

    /** Tap-to-read kept for sighted-fallback interaction. */
    fun readEmailAloud(email: EmailItem) {
        val emails = currentEmails()
        val idx = emails.indexOf(email).takeIf { it >= 0 } ?: _currentEmailIndex.value
        _currentEmailIndex.value = idx
        val text = "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}. " +
            "Say 'reply' to reply or 'delete' to delete."
        voiceCommandEngine.speakThenListen(text) { cmd -> handleCommand(cmd, emails) }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.clearTokens()
            _isSignedIn.value = false
            _uiState.value = InboxUiState.SignedOut
        }
    }

    fun stopVoice() = voiceCommandEngine.stopAll()

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
}

sealed class InboxUiState {
    object Loading : InboxUiState()
    object SignedOut : InboxUiState()
    data class Success(val emails: List<EmailItem>) : InboxUiState()
    data class Error(val message: String, val isAuthError: Boolean = false) : InboxUiState()
}

sealed class InboxNavEvent {
    /**
     * Navigate to the compose screen.
     * [replyTo] is the pre-filled recipient address when replying.
     * [isForward] is true when the full draft (to/subject/body) is waiting in [ForwardDraft].
     */
    data class NavigateToCompose(
        val replyTo: String? = null,
        val isForward: Boolean = false
    ) : InboxNavEvent()
}
