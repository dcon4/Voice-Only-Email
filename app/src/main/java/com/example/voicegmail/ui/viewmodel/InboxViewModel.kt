package com.example.voicegmail.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.BuildConfig
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.auth.OAuthDiagnostics
import com.example.voicegmail.auth.OAuthRedirectBus
import java.util.Calendar
import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.gmail.EmailItem
import com.example.voicegmail.gmail.GmailRepository
import com.example.voicegmail.gmail.AttachmentReader
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
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
    private val forwardDraft: ForwardDraft,
    private val attachmentReader: AttachmentReader,
    private val oAuthRedirectBus: OAuthRedirectBus
) : ViewModel() {

    /** True until the first successful inbox load — drives the opening greeting. */
    private var isFirstLoad = true

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
            // If MainActivity received an OAuth redirect before the ViewModel was created
            // (e.g. app started from Chrome redirect after process death), the URI is
            // already in the StateFlow. Handle it immediately — skip the "check tokens"
            // step so we never flash the "Please sign in" message.
            val earlyRedirect = oAuthRedirectBus.redirectUri.value
            if (earlyRedirect != null) {
                oAuthRedirectBus.consume()
                handleOAuthRedirectUri(earlyRedirect)
            } else {
                val hasToken = authRepository.hasAccessToken().first()
                _isSignedIn.value = hasToken
                if (hasToken) {
                    loadInbox()
                } else {
                    _uiState.value = InboxUiState.SignedOut
                    voiceManager.speak("Please sign in to access your Gmail inbox.")
                }
            }

            // Stay subscribed for redirects that arrive while the app is already running
            // (normal flow: user presses sign-in button, Chrome opens, onNewIntent fires).
            oAuthRedirectBus.redirectUri.filterNotNull().collect { uri ->
                oAuthRedirectBus.consume()
                handleOAuthRedirectUri(uri)
            }
        }
    }

    fun getSignInIntent(): Intent {
        DebugLogger.log("Auth", "Sign-in button pressed")
        return authRepository.buildSignInIntent()
    }

    /**
     * Handles an OAuth redirect URI delivered by MainActivity.onNewIntent (or onCreate on
     * a fresh process after process death). Exchanges the authorization code for tokens
     * directly, bypassing AppAuth's AuthorizationManagementActivity which cannot deliver
     * results after process death.
     */
    private suspend fun handleOAuthRedirectUri(uri: Uri) {
        DebugLogger.log("Auth", "OAuth redirect received — uri=${uri.toString().take(100)}")
        try {
            val (accessToken, refreshToken) = authRepository.exchangeCodeFromRedirect(uri)
            if (accessToken != null) {
                DebugLogger.log(
                    "Auth",
                    "Token exchange success via direct redirect — hasRefreshToken=${refreshToken != null}"
                )
                authRepository.saveTokens(accessToken, refreshToken)
                _isSignedIn.value = true
                loadInbox()
            } else {
                val msg = "Sign-in failed: Google returned no access token. " +
                    "Verify that the Gmail API is enabled and the OAuth scopes " +
                    "are approved in Google Cloud Console."
                DebugLogger.log("Auth", "Token exchange returned null access token")
                _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                voiceManager.speak(msg)
            }
        } catch (e: AuthorizationException) {
            DebugLogger.logException(
                "Auth",
                "Token exchange AuthorizationException — " +
                    "type=${e.type} code=${e.code} " +
                    "error='${e.error ?: "none"}' desc='${e.errorDescription ?: "none"}'",
                e
            )
            val msg = OAuthDiagnostics.friendlyMessage(e)
            _uiState.value = InboxUiState.Error(msg, isAuthError = true)
            voiceManager.speak(msg)
        } catch (e: Exception) {
            DebugLogger.logException(
                "Auth",
                "Token exchange exception — ${e::class.simpleName}: ${e.message}",
                e
            )
            val msg = "Sign-in failed: ${e.message ?: "unexpected error"}. Please try again."
            _uiState.value = InboxUiState.Error(msg, isAuthError = true)
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

                val greeting = if (isFirstLoad) timeOfDayGreeting() + ". " else ""
                isFirstLoad = false

                val unreadCount = emails.count { it.isUnread }
                val prompt = when {
                    emails.isEmpty() -> {
                        "${greeting}Your inbox is empty. " +
                            "Say 'compose' to write a new email or 'refresh' to reload."
                    }
                    unreadCount > 0 -> {
                        val unreadWord = if (unreadCount == 1) "1 unread email" else "$unreadCount unread emails"
                        val totalWord = if (emails.size == 1) "1 email total" else "${emails.size} emails total"
                        "${greeting}You have $unreadWord out of $totalWord. " +
                            "Say 'read unread' to hear your unread messages, " +
                            "'read' to start from the top, 'search' to find something, " +
                            "or 'compose' to write a new email."
                    }
                    else -> {
                        val totalWord = if (emails.size == 1) "1 email" else "${emails.size} emails"
                        "${greeting}Your inbox is all caught up. You have $totalWord. " +
                            "Say 'read' to hear the first one, 'search' to find something, " +
                            "or 'compose' to write a new email."
                    }
                }
                voiceCommandEngine.speakThenListen(prompt) { cmd -> handleCommand(cmd, emails) }
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
                    val attachmentNote = when (email.attachments.size) {
                        0 -> ""
                        1 -> "This email has 1 attachment: ${email.attachments[0].filename}. "
                        else -> "This email has ${email.attachments.size} attachments. " +
                            "Say 'list attachments' to hear their names. "
                    }
                    val attachmentCmd = if (email.attachments.isNotEmpty())
                        "'read attachment one' to read the first attachment, " else ""
                    val text = "${unreadPrefix}Email ${_currentEmailIndex.value + 1} of ${emails.size}. " +
                        "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}. " +
                        attachmentNote +
                        "Say 'reply' to reply, 'forward' to forward, 'delete' to delete, " +
                        attachmentCmd +
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
                        "No email to repeat. Say 'read' to hear an email first."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.Refresh -> loadInbox()
            is VoiceCommand.Compose -> {
                viewModelScope.launch {
                    _navigationEvent.emit(InboxNavEvent.NavigateToCompose())
                }
            }
            is VoiceCommand.Search -> startSearch(emails)
            is VoiceCommand.Forward -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null) {
                    voiceCommandEngine.speakThenListen(
                        "Who would you like to forward this email to? Please say the email address."
                    ) { cmd ->
                        when (cmd) {
                            is VoiceCommand.FreeText -> launchForward(email, cmd.text, emails)
                            is VoiceCommand.Cancel -> {
                                voiceCommandEngine.speakThenListen(
                                    "Forward cancelled."
                                ) { next -> handleCommand(next, emails) }
                            }
                            else -> {
                                val raw = voiceManager.recognizedText.value ?: ""
                                if (raw.isNotBlank()) launchForward(email, raw, emails)
                                else voiceCommandEngine.speakThenListen(
                                    "I didn't catch that. Please say the email address to forward to."
                                ) { next -> handleCommand(next, emails) }
                            }
                        }
                    }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No email selected to forward. Say 'read' to hear an email first."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.MarkAsRead -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null && email.isUnread) {
                    viewModelScope.launch {
                        try {
                            gmailRepository.markAsRead(email.id)
                            val updated = emails.map {
                                if (it.id == email.id) it.copy(isUnread = false) else it
                            }
                            _uiState.value = InboxUiState.Success(updated)
                            voiceCommandEngine.speakThenListen(
                                "Marked as read."
                            ) { cmd -> handleCommand(cmd, updated) }
                        } catch (e: Exception) {
                            voiceCommandEngine.speakThenListen(
                                "Could not mark as read. ${e.message ?: "Unknown error."}"
                            ) { cmd -> handleCommand(cmd, emails) }
                        }
                    }
                } else {
                    voiceCommandEngine.speakThenListen(
                        if (email == null) "No email selected."
                        else "This email is already marked as read."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.ListAttachments -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null && email.attachments.isNotEmpty()) {
                    val names = email.attachments.mapIndexed { i, a ->
                        "Attachment ${i + 1}: ${a.filename}"
                    }.joinToString(". ")
                    voiceCommandEngine.speakThenListen(
                        "$names. Say 'read attachment one' to read the first, " +
                            "or 'read attachment two' for the second, and so on."
                    ) { cmd -> handleCommand(cmd, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        if (email == null) "No email selected." else "This email has no attachments."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.ReadAttachment -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                val attachment = email?.attachments?.getOrNull(command.index - 1)
                if (attachment != null) {
                    viewModelScope.launch {
                        voiceManager.speak("Reading attachment: ${attachment.filename}. Please wait.")
                        try {
                            val text = attachmentReader.readAttachment(email.id, attachment)
                            voiceCommandEngine.speakThenListen(text) { cmd ->
                                handleCommand(cmd, emails)
                            }
                        } catch (e: Exception) {
                            voiceCommandEngine.speakThenListen(
                                "Could not read attachment. ${e.message ?: "Unknown error."}"
                            ) { cmd -> handleCommand(cmd, emails) }
                        }
                    }
                } else {
                    val total = emails.getOrNull(_currentEmailIndex.value)?.attachments?.size ?: 0
                    voiceCommandEngine.speakThenListen(
                        if (total == 0) "This email has no attachments."
                        else "There is no attachment number ${command.index}. " +
                            "This email has $total attachment${if (total == 1) "" else "s"}."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.ReadAllUnread -> {
                val unread = emails.filter { it.isUnread }
                if (unread.isEmpty()) {
                    voiceCommandEngine.speakThenListen(
                        "No unread emails. All caught up!"
                    ) { cmd -> handleCommand(cmd, emails) }
                } else {
                    val idx = emails.indexOf(unread.first()).coerceAtLeast(0)
                    _currentEmailIndex.value = idx
                    val email = unread.first()
                    voiceCommandEngine.speakThenListen(
                        "You have ${unread.size} unread email${if (unread.size == 1) "" else "s"}. " +
                            "First unread: From ${email.from}. Subject: ${email.subject}. " +
                            "Say 'read' to hear the full message, or 'next' to go to the next email."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.TryAgain -> loadInbox()
            is VoiceCommand.Cancel -> {
                voiceCommandEngine.speakThenListen(
                    "Cancelled. Say a command."
                ) { cmd -> handleCommand(cmd, emails) }
            }
            is VoiceCommand.Confirm -> {
                // Confirm with no pending action — prompt for a command.
                voiceCommandEngine.speakThenListen(
                    "Nothing to confirm. Say a command."
                ) { cmd -> handleCommand(cmd, emails) }
            }
            is VoiceCommand.Help -> {
                voiceCommandEngine.speakThenListen(
                    "Available commands: 'read', 'next', 'previous', 'reply', 'forward', " +
                        "'delete', 'compose', 'search', 'refresh', 'read unread', " +
                        "'mark as read', 'list attachments', or 'repeat'."
                ) { cmd -> handleCommand(cmd, emails) }
            }
            else -> {
                voiceCommandEngine.speakThenListen(
                    "Sorry, I didn't understand that. Say 'help' to hear available commands."
                ) { cmd -> handleCommand(cmd, emails) }
            }
        }
    }

    private fun handleDeleteConfirmation(
        command: VoiceCommand,
        email: EmailItem,
        emails: List<EmailItem>
    ) {
        when (command) {
            is VoiceCommand.Confirm -> {
                viewModelScope.launch {
                    try {
                        gmailRepository.trashEmail(email.id)
                        val updated = emails.filter { it.id != email.id }
                        _currentEmailIndex.value =
                            _currentEmailIndex.value.coerceAtMost((updated.size - 1).coerceAtLeast(0))
                        _uiState.value = InboxUiState.Success(updated)
                        val nextEmail = updated.getOrNull(_currentEmailIndex.value)
                        val msg = if (nextEmail != null)
                            "Deleted. Next email: From ${nextEmail.from}. ${nextEmail.subject}. " +
                                "Say 'read' to hear it."
                        else
                            "Deleted. No more emails in inbox."
                        voiceCommandEngine.speakThenListen(msg) { cmd ->
                            handleCommand(cmd, updated)
                        }
                    } catch (e: Exception) {
                        voiceCommandEngine.speakThenListen(
                            "Delete failed. ${e.message ?: "Unknown error."}. Say 'try again' or 'cancel'."
                        ) { cmd ->
                            when (cmd) {
                                is VoiceCommand.TryAgain ->
                                    handleDeleteConfirmation(VoiceCommand.Confirm, email, emails)
                                else -> handleCommand(cmd, emails)
                            }
                        }
                    }
                }
            }
            is VoiceCommand.Cancel -> {
                voiceCommandEngine.speakThenListen(
                    "Delete cancelled."
                ) { next -> handleCommand(next, emails) }
            }
            else -> {
                voiceCommandEngine.speakThenListen(
                    "Say 'yes' to confirm delete or 'cancel' to go back."
                ) { cmd -> handleDeleteConfirmation(cmd, email, emails) }
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

    private fun startSearch(originEmails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen(
            "What would you like to search for? " +
                "You can say things like 'emails from David', 'subject meeting', or 'unread emails'."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.FreeText -> executeSearch(cmd.text, originEmails)
                is VoiceCommand.Cancel -> {
                    voiceCommandEngine.speakThenListen(
                        "Search cancelled."
                    ) { nextCmd -> handleCommand(nextCmd, originEmails) }
                }
                else -> {
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
                    _currentEmailIndex.value = 0
                    _uiState.value = InboxUiState.Success(results)
                    voiceCommandEngine.speakThenListen(intro) { cmd ->
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

    private fun timeOfDayGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11  -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else      -> "Good night"
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
    data class NavigateToCompose(
        val replyTo: String? = null,
        val isForward: Boolean = false
    ) : InboxNavEvent()
}
