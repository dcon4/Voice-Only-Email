package com.example.voicegmail.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.BuildConfig
import com.example.voicegmail.WakeEventBus
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.auth.OAuthDiagnostics
import com.example.voicegmail.auth.OAuthRedirectBus
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
import java.util.Calendar
import javax.inject.Inject

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
    private val oAuthRedirectBus: OAuthRedirectBus,
    private val wakeEventBus: WakeEventBus
) : ViewModel() {

    private var isFirstLoad = true

    private val _uiState = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val uiState: StateFlow<InboxUiState> = _uiState

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn

    private val _currentEmailIndex = MutableStateFlow(0)
    val currentEmailIndex: StateFlow<Int> = _currentEmailIndex

    private val _navigationEvent = MutableSharedFlow<InboxNavEvent>(extraBufferCapacity = 1)
    val navigationEvent: SharedFlow<InboxNavEvent> = _navigationEvent

    init {
        viewModelScope.launch {
            val earlyRedirect = oAuthRedirectBus.redirectUri.value
            if (earlyRedirect != null) {
                oAuthRedirectBus.consume()
                handleOAuthRedirectUri(earlyRedirect)
            } else {
                val hasToken = authRepository.hasAccessToken().first()
                _isSignedIn.value = hasToken
                if (hasToken) loadInbox()
                else {
                    _uiState.value = InboxUiState.SignedOut
                    voiceManager.speak("Please sign in to access your Gmail inbox.")
                }
            }
            oAuthRedirectBus.redirectUri.filterNotNull().collect { uri ->
                oAuthRedirectBus.consume()
                handleOAuthRedirectUri(uri)
            }
        }

        // Re-arm mic whenever the screen wakes (fired by VoiceWakeService).
        viewModelScope.launch {
            wakeEventBus.wakeEvent.collect { handleWakeEvent() }
        }
    }

    // ------------------------------------------------------------------
    // Wake from lock screen
    // ------------------------------------------------------------------

    private fun handleWakeEvent() {
        DebugLogger.log("InboxViewModel", "Wake event — state=${_uiState.value::class.simpleName}")
        when (val state = _uiState.value) {
            is InboxUiState.Success -> {
                val emails = state.emails
                voiceCommandEngine.speakThenListen(
                    "VoiceGmail ready. Say a command."
                ) { cmd -> handleCommand(cmd, emails) }
            }
            is InboxUiState.SignedOut ->
                voiceManager.speak("VoiceGmail. Please sign in to access your inbox.")
            is InboxUiState.Loading ->
                voiceManager.speak("Loading your inbox. Please wait.")
            is InboxUiState.Error ->
                voiceCommandEngine.speakThenListen(
                    "VoiceGmail. ${state.message}. Say 'retry' to try again."
                ) { cmd -> handleCommand(cmd, emptyList()) }
        }
    }

    // ------------------------------------------------------------------
    // Auth
    // ------------------------------------------------------------------

    fun getSignInIntent(): Intent {
        DebugLogger.log("Auth", "Sign-in button pressed")
        return authRepository.buildSignInIntent()
    }

    private suspend fun handleOAuthRedirectUri(uri: Uri) {
        DebugLogger.log("Auth", "OAuth redirect — uri=${uri.toString().take(100)}")
        try {
            val (accessToken, refreshToken) = authRepository.exchangeCodeFromRedirect(uri)
            if (accessToken != null) {
                authRepository.saveTokens(accessToken, refreshToken)
                _isSignedIn.value = true
                loadInbox()
            } else {
                val msg = "Sign-in failed: Google returned no access token."
                _uiState.value = InboxUiState.Error(msg, isAuthError = true)
                voiceManager.speak(msg)
            }
        } catch (e: AuthorizationException) {
            val msg = OAuthDiagnostics.friendlyMessage(e)
            _uiState.value = InboxUiState.Error(msg, isAuthError = true)
            voiceManager.speak(msg)
        } catch (e: Exception) {
            val msg = "Sign-in failed: ${e.message ?: "unexpected error"}."
            _uiState.value = InboxUiState.Error(msg, isAuthError = true)
            voiceManager.speak(msg)
        }
    }

    // ------------------------------------------------------------------
    // Inbox loading
    // ------------------------------------------------------------------

    fun loadInbox() {
        viewModelScope.launch {
            _uiState.value = InboxUiState.Loading
            try {
                val emails = gmailRepository.listInbox()
                _currentEmailIndex.value = 0
                _uiState.value = InboxUiState.Success(emails)

                val greeting = if (isFirstLoad) timeOfDayGreeting() + ". " else ""
                isFirstLoad = false

                val unread = emails.count { it.isUnread }
                val prompt = when {
                    emails.isEmpty() ->
                        "${greeting}Your inbox is empty. Say 'compose' to write an email, or 'refresh' to reload."
                    unread > 0 -> {
                        val u = if (unread == 1) "1 unread email" else "$unread unread emails"
                        val t = if (emails.size == 1) "1 email total" else "${emails.size} emails total"
                        "${greeting}You have $u out of $t. Say 'read unread' to hear your unread messages, " +
                            "'read' to start from the top, 'search' to find something, or 'compose' to write."
                    }
                    else -> {
                        val t = if (emails.size == 1) "1 email" else "${emails.size} emails"
                        "${greeting}Your inbox is all caught up. You have $t. " +
                            "Say 'read' to hear the first one, 'search', or 'compose'."
                    }
                }
                voiceCommandEngine.speakThenListen(prompt) { cmd -> handleCommand(cmd, emails) }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load inbox"
                val isAuth = msg.contains("401") || msg.contains("403") ||
                    msg.contains("Not authenticated") || msg.contains("Unauthorized")
                if (isAuth) {
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

    // ------------------------------------------------------------------
    // Main command dispatcher
    // ------------------------------------------------------------------

    fun handleCommand(command: VoiceCommand, emails: List<EmailItem> = currentEmails()) {
        when (command) {
            is VoiceCommand.Read -> readCurrentEmail(emails)
            is VoiceCommand.Next -> advanceEmail(+1, emails)
            is VoiceCommand.Previous -> advanceEmail(-1, emails)
            is VoiceCommand.Repeat -> repeatCurrentEmail(emails)
            is VoiceCommand.Refresh -> loadInbox()
            is VoiceCommand.Compose -> viewModelScope.launch {
                _navigationEvent.emit(InboxNavEvent.NavigateToCompose())
            }
            is VoiceCommand.Reply -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null) viewModelScope.launch {
                    _navigationEvent.emit(InboxNavEvent.NavigateToCompose(replyTo = email.from))
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No email selected. Say 'read' to hear an email first."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }
            is VoiceCommand.Delete -> confirmDelete(emails)
            is VoiceCommand.Forward -> startForward(emails)
            is VoiceCommand.MarkAsRead -> markCurrentAsRead(emails)
            is VoiceCommand.Search -> startSearch(emails)
            is VoiceCommand.ListAttachments -> listAttachments(emails)
            is VoiceCommand.ReadAttachment -> readAttachment(command.index, emails)
            is VoiceCommand.ReadAllUnread -> readAllUnread(emails)
            is VoiceCommand.TryAgain -> loadInbox()
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                voiceCommandEngine.speakThenListen(
                    "Cancelled. Say a command, or 'help' for options."
                ) { cmd -> handleCommand(cmd, emails) }
            }
            is VoiceCommand.Confirm -> {
                voiceCommandEngine.speakThenListen(
                    "Nothing to confirm right now. Say a command."
                ) { cmd -> handleCommand(cmd, emails) }
            }
            is VoiceCommand.Help -> {
                voiceCommandEngine.speakThenListen(
                    "Commands: read, next, previous, repeat, reply, forward, delete, compose, " +
                        "search, refresh, read unread, mark as read, list attachments, " +
                        "voice settings, instructions. Say a command."
                ) { cmd -> handleCommand(cmd, emails) }
            }
            is VoiceCommand.VoiceSettings -> handleVoiceSettings(emails)
            is VoiceCommand.Instructions -> handleInstructions(emails)
            else -> {
                voiceCommandEngine.speakThenListen(
                    "Sorry, I didn't understand that. Say 'help' to hear available commands."
                ) { cmd -> handleCommand(cmd, emails) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private fun readCurrentEmail(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen(
                "No more emails. Say 'refresh' or 'compose'."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        val unreadPrefix = if (email.isUnread) "Unread. " else ""
        val attachNote = when (email.attachments.size) {
            0 -> ""
            1 -> "This email has 1 attachment: ${email.attachments[0].filename}. "
            else -> "This email has ${email.attachments.size} attachments. Say 'list attachments' to hear them. "
        }
        val readAttachHint = if (email.attachments.isNotEmpty()) "'read attachment one' to read the first attachment, " else ""
        val text = "${unreadPrefix}Email ${_currentEmailIndex.value + 1} of ${emails.size}. " +
            "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}. " +
            attachNote +
            "Say 'reply', 'forward', 'delete', ${readAttachHint}'next', or 'repeat'."
        voiceCommandEngine.speakThenListen(text) { cmd -> handleCommand(cmd, emails) }
    }

    private fun advanceEmail(delta: Int, emails: List<EmailItem>) {
        val newIdx = _currentEmailIndex.value + delta
        val email = emails.getOrNull(newIdx)
        if (email != null) {
            _currentEmailIndex.value = newIdx
            val direction = if (delta > 0) "next" else "previous"
            voiceCommandEngine.speakThenListen(
                "Email ${newIdx + 1} of ${emails.size}. From ${email.from}: ${email.subject}. " +
                    "Say 'read' to hear the full message, 'reply', 'delete', or '$direction'."
            ) { cmd -> handleCommand(cmd, emails) }
        } else {
            val limit = if (delta > 0) "last" else "first"
            voiceCommandEngine.speakThenListen(
                "You are at the $limit email. Say '${if (delta > 0) "previous" else "next"}', 'refresh', or 'compose'."
            ) { cmd -> handleCommand(cmd, emails) }
        }
    }

    private fun repeatCurrentEmail(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email != null) {
            voiceCommandEngine.speakThenListen(
                "Repeating email ${_currentEmailIndex.value + 1}. " +
                    "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}"
            ) { cmd -> handleCommand(cmd, emails) }
        } else {
            voiceCommandEngine.speakThenListen(
                "No email to repeat. Say 'read' first."
            ) { cmd -> handleCommand(cmd, emails) }
        }
    }

    private fun readAllUnread(emails: List<EmailItem>) {
        val unread = emails.filter { it.isUnread }
        if (unread.isEmpty()) {
            voiceCommandEngine.speakThenListen(
                "No unread emails. All caught up!"
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        val idx = emails.indexOf(unread.first()).coerceAtLeast(0)
        _currentEmailIndex.value = idx
        val email = unread.first()
        voiceCommandEngine.speakThenListen(
            "You have ${unread.size} unread email${if (unread.size == 1) "" else "s"}. " +
                "First unread: From ${email.from}. Subject: ${email.subject}. " +
                "Say 'read' to hear the full message, or 'next' for the next email."
        ) { cmd -> handleCommand(cmd, emails) }
    }

    fun readEmailAloud(email: EmailItem) {
        val emails = currentEmails()
        val idx = emails.indexOf(email).takeIf { it >= 0 } ?: _currentEmailIndex.value
        _currentEmailIndex.value = idx
        voiceCommandEngine.speakThenListen(
            "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}. " +
                "Say 'reply', 'delete', 'next', or 'repeat'."
        ) { cmd -> handleCommand(cmd, emails) }
    }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    private fun listAttachments(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null || email.attachments.isEmpty()) {
            voiceCommandEngine.speakThenListen(
                if (email == null) "No email selected." else "This email has no attachments."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        val names = email.attachments.mapIndexed { i, a -> "${i + 1}: ${a.filename}" }.joinToString(". ")
        voiceCommandEngine.speakThenListen(
            "$names. Say 'read attachment one' to read the first, 'read attachment two' for the second."
        ) { cmd -> handleCommand(cmd, emails) }
    }

    private fun readAttachment(oneBasedIndex: Int, emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        val attachment = email?.attachments?.getOrNull(oneBasedIndex - 1)
        if (attachment == null) {
            val total = email?.attachments?.size ?: 0
            voiceCommandEngine.speakThenListen(
                if (total == 0) "This email has no attachments."
                else "No attachment number $oneBasedIndex. This email has $total attachment${if (total == 1) "" else "s"}."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        viewModelScope.launch {
            voiceManager.speak("Reading attachment: ${attachment.filename}. Please wait.")
            try {
                val text = attachmentReader.readAttachment(email.id, attachment)
                voiceCommandEngine.speakThenListen(text) { cmd -> handleCommand(cmd, emails) }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen(
                    "Could not read attachment. ${e.message ?: "Unknown error."}"
                ) { cmd -> handleCommand(cmd, emails) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    private fun confirmDelete(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen(
                "No email selected to delete. Say 'read' first."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        voiceCommandEngine.speakThenListen(
            "Delete email from ${email.from}, subject: ${email.subject}? Say 'yes' to confirm or 'cancel' to go back."
        ) { cmd -> handleDeleteConfirmation(cmd, email, emails) }
    }

    private fun handleDeleteConfirmation(command: VoiceCommand, email: EmailItem, emails: List<EmailItem>) {
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
                            "Deleted. Next email: From ${nextEmail.from}. ${nextEmail.subject}. Say 'read' to hear it."
                        else "Deleted. No more emails."
                        voiceCommandEngine.speakThenListen(msg) { cmd -> handleCommand(cmd, updated) }
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
            is VoiceCommand.Cancel, is VoiceCommand.GoBack ->
                voiceCommandEngine.speakThenListen("Delete cancelled.") { cmd -> handleCommand(cmd, emails) }
            else ->
                voiceCommandEngine.speakThenListen(
                    "Say 'yes' to confirm delete or 'cancel' to go back."
                ) { cmd -> handleDeleteConfirmation(cmd, email, emails) }
        }
    }

    // ------------------------------------------------------------------
    // Mark as read
    // ------------------------------------------------------------------

    private fun markCurrentAsRead(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null || !email.isUnread) {
            voiceCommandEngine.speakThenListen(
                if (email == null) "No email selected." else "This email is already marked as read."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        viewModelScope.launch {
            try {
                gmailRepository.markAsRead(email.id)
                val updated = emails.map { if (it.id == email.id) it.copy(isUnread = false) else it }
                _uiState.value = InboxUiState.Success(updated)
                voiceCommandEngine.speakThenListen("Marked as read.") { cmd -> handleCommand(cmd, updated) }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen(
                    "Could not mark as read. ${e.message ?: "Unknown error."}"
                ) { cmd -> handleCommand(cmd, emails) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Forward
    // ------------------------------------------------------------------

    private fun startForward(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen(
                "No email selected to forward. Say 'read' first."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        voiceCommandEngine.speakThenListen(
            "Who would you like to forward this to? Please say the email address."
        ) { cmd ->
            val raw = (cmd as? VoiceCommand.FreeText)?.text
                ?: voiceManager.recognizedText.value ?: ""
            when {
                cmd is VoiceCommand.Cancel -> voiceCommandEngine.speakThenListen(
                    "Forward cancelled."
                ) { c -> handleCommand(c, emails) }
                raw.isNotBlank() -> launchForward(email, raw, emails)
                else -> voiceCommandEngine.speakThenListen(
                    "I didn't catch that. Please say the email address to forward to."
                ) { c -> handleCommand(c, emails) }
            }
        }
    }

    private fun launchForward(email: EmailItem, to: String, emails: List<EmailItem>) {
        val body = buildString {
            appendLine(); appendLine("---------- Forwarded message ---------")
            appendLine("From: ${email.from}"); appendLine("Subject: ${email.subject}")
            appendLine(); append(email.body)
        }
        forwardDraft.set(to = to, subject = "Fwd: ${email.subject}", body = body)
        viewModelScope.launch { _navigationEvent.emit(InboxNavEvent.NavigateToCompose(isForward = true)) }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    private fun startSearch(originEmails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen(
            "What would you like to search for? For example: 'emails from David', " +
                "'subject meeting', or 'unread emails'."
        ) { cmd ->
            val raw = (cmd as? VoiceCommand.FreeText)?.text
                ?: voiceManager.recognizedText.value ?: ""
            when {
                cmd is VoiceCommand.Cancel -> voiceCommandEngine.speakThenListen(
                    "Search cancelled."
                ) { c -> handleCommand(c, originEmails) }
                raw.isNotBlank() -> executeSearch(raw, originEmails)
                else -> voiceCommandEngine.speakThenListen(
                    "I didn't catch that. Say what you'd like to search for, or 'cancel'."
                ) { c ->
                    val retry = voiceManager.recognizedText.value ?: ""
                    if (retry.isNotBlank()) executeSearch(retry, originEmails)
                    else handleCommand(c, originEmails)
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
                        "No emails found matching '$query'. Say 'search again' or 'inbox' to go back."
                    ) { cmd ->
                        when {
                            cmd is VoiceCommand.Search -> startSearch(originEmails)
                            cmd is VoiceCommand.Cancel ||
                                (cmd is VoiceCommand.FreeText &&
                                    cmd.text.lowercase().contains("inbox")) ->
                                handleCommand(VoiceCommand.Refresh, originEmails)
                            else -> handleCommand(cmd, originEmails)
                        }
                    }
                } else {
                    val first = results[0]
                    _currentEmailIndex.value = 0
                    _uiState.value = InboxUiState.Success(results)
                    voiceCommandEngine.speakThenListen(
                        "Found ${results.size} email${if (results.size == 1) "" else "s"} matching '$query'. " +
                            "Email 1 of ${results.size}, from ${first.from}: ${first.subject}. " +
                            "Say 'read', 'next', 'reply', 'delete', or 'search again'."
                    ) { cmd ->
                        if (cmd is VoiceCommand.Search) startSearch(originEmails)
                        else handleCommand(cmd, results)
                    }
                }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen(
                    "Search failed. ${e.message ?: "Unknown error."}. Say 'try again' or 'cancel'."
                ) { cmd ->
                    when (cmd) {
                        is VoiceCommand.TryAgain -> executeSearch(rawQuery, originEmails)
                        else -> handleCommand(VoiceCommand.Refresh, originEmails)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Voice settings — voice-guided engine & voice selection
    // ------------------------------------------------------------------

    private fun handleVoiceSettings(emails: List<EmailItem>) {
        val engines = voiceManager.getAvailableEngines()
        if (engines.isEmpty()) {
            voiceCommandEngine.speakThenListen(
                "No text-to-speech engines found. Please install a TTS engine from the Play Store."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        if (engines.size == 1) {
            // Skip engine selection — jump straight to voice list.
            voiceCommandEngine.speakThenListen(
                "Voice settings. You have one TTS engine: ${engines[0].label}. " +
                    "Say 'voices' to browse available voices, or 'cancel' to go back."
            ) { cmd ->
                if (cmd is VoiceCommand.Cancel) {
                    voiceCommandEngine.speakThenListen("Cancelled.") { c -> handleCommand(c, emails) }
                } else {
                    listAndSelectVoice(engines[0].label, emails)
                }
            }
            return
        }
        val engineList = engines.mapIndexed { i, e -> "${i + 1}: ${e.label}" }.joinToString(". ")
        voiceCommandEngine.speakThenListen(
            "Voice settings. You have ${engines.size} TTS engines installed. $engineList. " +
                "Say a number to select an engine, 'voices' to change voice on the current engine, or 'cancel'."
        ) { cmd ->
            val raw = ((cmd as? VoiceCommand.FreeText)?.text
                ?: voiceManager.recognizedText.value ?: "").lowercase()
            when {
                cmd is VoiceCommand.Cancel ->
                    voiceCommandEngine.speakThenListen("Cancelled.") { c -> handleCommand(c, emails) }
                raw.contains("voice") ->
                    listAndSelectVoice("current engine", emails)
                else -> {
                    val idx = matchNumber(raw)
                    val engine = if (idx != null) engines.getOrNull(idx - 1)
                    else engines.find { raw.contains(it.label.lowercase()) || it.label.lowercase().contains(raw) }
                    if (engine != null) {
                        voiceManager.speak("Switching to ${engine.label}. Please wait.")
                        voiceManager.reinitWithEngine(engine.name) {
                            listAndSelectVoice(engine.label, emails)
                        }
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "I didn't catch that. Say a number from 1 to ${engines.size}, or 'cancel'."
                        ) { handleVoiceSettings(emails) }
                    }
                }
            }
        }
    }

    private fun listAndSelectVoice(engineLabel: String, emails: List<EmailItem>, offset: Int = 0) {
        val allVoices = voiceManager.getAvailableVoices()
        val pageSize  = 6
        val page      = allVoices.drop(offset).take(pageSize)
        val hasMore   = offset + pageSize < allVoices.size

        if (page.isEmpty()) {
            voiceCommandEngine.speakThenListen(
                "No voices found for $engineLabel. Using the engine default."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        val voiceList = page.mapIndexed { i, v ->
            "${i + 1}: ${voiceManager.friendlyVoiceName(v)}"
        }.joinToString(". ")
        val moreHint = if (hasMore) "Say 'more' for more voices. " else ""

        voiceCommandEngine.speakThenListen(
            "Available voices for $engineLabel. $voiceList. " +
                "${moreHint}Say a number to select, or 'default' to reset to the engine default."
        ) { cmd ->
            val raw = ((cmd as? VoiceCommand.FreeText)?.text
                ?: voiceManager.recognizedText.value ?: "").lowercase()
            when {
                cmd is VoiceCommand.Cancel ->
                    voiceCommandEngine.speakThenListen("Voice settings cancelled.") { c ->
                        handleCommand(c, emails)
                    }
                raw.contains("more") || raw.contains("next") -> {
                    if (hasMore) listAndSelectVoice(engineLabel, emails, offset + pageSize)
                    else voiceCommandEngine.speakThenListen("No more voices.") {
                        listAndSelectVoice(engineLabel, emails, offset)
                    }
                }
                raw.contains("default") -> {
                    voiceManager.clearVoicePreference()
                    voiceCommandEngine.speakThenListen(
                        "Reset to $engineLabel default. Testing: Hello, this is your new voice."
                    ) { c -> handleCommand(c, emails) }
                }
                else -> {
                    val num = matchNumber(raw)
                    val voice = if (num != null && num >= 1 && num <= page.size) page[num - 1] else null
                    if (voice != null) {
                        voiceManager.setVoiceByName(voice.name)
                        voiceCommandEngine.speakThenListen(
                            "Voice set to ${voiceManager.friendlyVoiceName(voice)}. " +
                                "Testing: Hello, this is your new voice."
                        ) { c -> handleCommand(c, emails) }
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "I didn't catch that. Please say a number between 1 and ${page.size}."
                        ) { listAndSelectVoice(engineLabel, emails, offset) }
                    }
                }
            }
        }
    }

    /** Parses spoken number words into 1-based integers. */
    private fun matchNumber(raw: String): Int? {
        val map = mapOf(
            "1" to 1, "one" to 1, "first" to 1,
            "2" to 2, "two" to 2, "second" to 2,
            "3" to 3, "three" to 3, "third" to 3,
            "4" to 4, "four" to 4, "fourth" to 4,
            "5" to 5, "five" to 5, "fifth" to 5,
            "6" to 6, "six" to 6, "sixth" to 6,
            "7" to 7, "seven" to 7, "seventh" to 7,
            "8" to 8, "eight" to 8, "eighth" to 8
        )
        return map.entries.firstOrNull { (word, _) ->
            Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(raw)
        }?.value
    }

    // ------------------------------------------------------------------
    // Multi-section spoken instructions
    // ------------------------------------------------------------------

    private fun handleInstructions(emails: List<EmailItem>, section: Int = 0) {
        val text = INSTRUCTION_SECTIONS.getOrElse(section) { INSTRUCTION_SECTIONS.last() }
        voiceCommandEngine.speakThenListen(text) { cmd ->
            when (cmd) {
                is VoiceCommand.Next ->
                    handleInstructions(emails, (section + 1).coerceAtMost(INSTRUCTION_SECTIONS.size - 1))
                is VoiceCommand.Previous ->
                    handleInstructions(emails, (section - 1).coerceAtLeast(0))
                is VoiceCommand.Repeat ->
                    handleInstructions(emails, section)
                is VoiceCommand.Cancel, is VoiceCommand.GoBack ->
                    voiceCommandEngine.speakThenListen(
                        "Returning to inbox. Say 'read' to hear an email or 'help' for commands."
                    ) { c -> handleCommand(c, emails) }
                else -> handleCommand(cmd, emails)
            }
        }
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

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
        val uri  = FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.debug.fileprovider", file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun timeOfDayGreeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11  -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else      -> "Good night"
    }

    private fun currentEmails(): List<EmailItem> =
        (_uiState.value as? InboxUiState.Success)?.emails ?: emptyList()

    // ------------------------------------------------------------------
    // Instruction content
    // ------------------------------------------------------------------

    private companion object {
        val INSTRUCTION_SECTIONS = listOf(
            "Instructions. Section 1 of 8: Overview. " +
                "VoiceGmail is completely hands-free. After every spoken message, " +
                "the microphone opens automatically — you never need to tap the screen. " +
                "Wake the app at any time by pressing the power button. " +
                "Navigate with: next, previous, repeat, cancel, or help. " +
                "Say 'next' to continue, or say any command to return to your inbox.",

            "Section 2 of 8: Reading your mail. " +
                "Say 'read' to hear the current email in full. " +
                "Say 'next' to move to the next email, or 'previous' to go back. " +
                "Say 'repeat' to hear the same email again. " +
                "Say 'read unread' to jump straight to your first unread message. " +
                "Say 'next' to continue.",

            "Section 3 of 8: Replying and composing. " +
                "Say 'reply' to reply to the current email. " +
                "Say 'compose' to write a brand new email. " +
                "When composing you will be guided step by step — recipient, subject, then body. " +
                "Say 'send' when done, 'read back' to review what you wrote, or 'cancel' to discard. " +
                "Say 'next' to continue.",

            "Section 4 of 8: Managing email. " +
                "Say 'delete' to delete the current email — you will be asked to confirm. " +
                "Say 'yes' to confirm or 'cancel' to abort. " +
                "Say 'mark as read' to clear the unread badge on the current email. " +
                "Say 'forward' to forward — you will be asked for the recipient's address. " +
                "Say 'next' to continue.",

            "Section 5 of 8: Searching. " +
                "Say 'search' to search your inbox. Then describe what you want: " +
                "for example, 'emails from David', 'subject meeting notes', or 'unread emails'. " +
                "In search results, use 'next' and 'previous' to browse, and 'read' to hear a message. " +
                "Say 'next' to continue.",

            "Section 6 of 8: Attachments. " +
                "Say 'list attachments' to hear what files are attached to the current email. " +
                "Say 'read attachment one' to have the first attachment read aloud. " +
                "Say 'read attachment two' for the second, and so on. " +
                "P D F files, Word documents, and plain text files are supported. " +
                "Say 'next' to continue.",

            "Section 7 of 8: Voice and speech settings. " +
                "Say 'voice settings' to change the text-to-speech engine or voice. " +
                "You can choose any T T S engine installed on your device, " +
                "such as Google Text-to-Speech, Samsung T T S, or eSpeak. " +
                "Within each engine you can pick a specific voice — different accent, language, or gender. " +
                "Your settings are saved and restored automatically when the app starts. " +
                "Say 'next' to continue.",

            "Section 8 of 8: Wake from lock screen. " +
                "Press the power button to wake your device. " +
                "VoiceGmail will appear on screen and say 'VoiceGmail ready — say a command'. " +
                "You do not need to unlock your phone first. " +
                "The app works fully over the lock screen. " +
                "That is the end of the instructions. " +
                "Say 'previous' to go back, 'repeat' to hear this section again, " +
                "or any command to return to your inbox."
        )
    }
}

sealed class InboxUiState {
    object Loading  : InboxUiState()
    object SignedOut : InboxUiState()
    data class Success(val emails: List<EmailItem>) : InboxUiState()
    data class Error(val message: String, val isAuthError: Boolean = false) : InboxUiState()
}

sealed class InboxNavEvent {
    data class NavigateToCompose(
        val replyTo: String?  = null,
        val isForward: Boolean = false
    ) : InboxNavEvent()
}
