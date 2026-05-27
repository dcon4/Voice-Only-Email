package com.example.voicegmail.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
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
import java.util.Locale
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

    // ------------------------------------------------------------------
    // Visual settings panel state
    // ------------------------------------------------------------------

    private val _settingsPanelVisible = MutableStateFlow(false)
    val settingsPanelVisible: StateFlow<Boolean> = _settingsPanelVisible

    private val _availableEngines = MutableStateFlow<List<TextToSpeech.EngineInfo>>(emptyList())
    val availableEngines: StateFlow<List<TextToSpeech.EngineInfo>> = _availableEngines

    private val _settingsVoices = MutableStateFlow<List<Voice>>(emptyList())
    val settingsVoices: StateFlow<List<Voice>> = _settingsVoices

    private val _selectedEngineName = MutableStateFlow<String?>(null)
    val selectedEngineName: StateFlow<String?> = _selectedEngineName

    private val _selectedVoiceName = MutableStateFlow<String?>(null)
    val selectedVoiceName: StateFlow<String?> = _selectedVoiceName

    private val _isSwitchingEngine = MutableStateFlow(false)
    val isSwitchingEngine: StateFlow<Boolean> = _isSwitchingEngine

    // ------------------------------------------------------------------
    // Init
    // ------------------------------------------------------------------

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
        viewModelScope.launch {
            wakeEventBus.wakeEvent.collect { handleWakeEvent() }
        }
    }

    // ------------------------------------------------------------------
    // Visual settings panel — called from UI
    // ------------------------------------------------------------------

    fun openSettingsPanel() {
        _availableEngines.value = voiceManager.getAvailableEngines()
        _settingsVoices.value   = voiceManager.getAvailableVoices()
        _selectedEngineName.value = voiceManager.getCurrentEngineName()
        _selectedVoiceName.value  = voiceManager.getCurrentVoiceName()
        _settingsPanelVisible.value = true
    }

    fun closeSettingsPanel() { _settingsPanelVisible.value = false }

    fun selectEngineFromPanel(engineName: String) {
        if (engineName == _selectedEngineName.value) {
            _settingsVoices.value = voiceManager.getAvailableVoices(); return
        }
        _isSwitchingEngine.value = true
        voiceManager.reinitWithEngine(engineName) {
            _settingsVoices.value     = voiceManager.getAvailableVoices()
            _selectedEngineName.value = engineName
            _selectedVoiceName.value  = voiceManager.getCurrentVoiceName()
            _isSwitchingEngine.value  = false
        }
    }

    fun selectVoiceFromPanel(voiceName: String) {
        voiceManager.setVoiceByName(voiceName)
        _selectedVoiceName.value = voiceName
    }

    fun clearVoicePreferenceFromPanel() {
        voiceManager.clearVoicePreference()
        _selectedVoiceName.value = null
    }

    fun testVoice() {
        val vName = _selectedVoiceName.value
        val label = if (vName != null)
            _settingsVoices.value.find { it.name == vName }
                ?.let { voiceManager.friendlyVoiceName(it) } ?: "the selected voice"
        else "the engine default voice"
        voiceManager.speak("Hello. This is $label. VoiceGmail is ready to help you.")
    }

    fun friendlyVoiceName(voice: Voice): String = voiceManager.friendlyVoiceName(voice)

    // ------------------------------------------------------------------
    // Wake from lock screen
    // ------------------------------------------------------------------

    private fun handleWakeEvent() {
        DebugLogger.log("InboxViewModel", "Wake event — state=${_uiState.value::class.simpleName}")
        when (val state = _uiState.value) {
            is InboxUiState.Success ->
                voiceCommandEngine.speakThenListen("VoiceGmail ready. Say a command.") { cmd ->
                    handleCommand(cmd, state.emails)
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
                        "${greeting}Your inbox is empty. Say 'compose' to write, or 'refresh' to reload."
                    unread > 0 -> {
                        val u = if (unread == 1) "1 unread email" else "$unread unread emails"
                        val t = if (emails.size == 1) "1 email total" else "${emails.size} emails total"
                        "${greeting}You have $u out of $t. Say 'read unread', 'read', 'search', or 'compose'."
                    }
                    else -> {
                        val t = if (emails.size == 1) "1 email" else "${emails.size} emails"
                        "${greeting}Your inbox is all caught up. You have $t. Say 'read', 'search', or 'compose'."
                    }
                }
                voiceCommandEngine.speakThenListen(prompt) { cmd -> handleCommand(cmd, emails) }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load inbox"
                val isAuth = msg.contains("401") || msg.contains("403") ||
                    msg.contains("Not authenticated") || msg.contains("Unauthorized")
                if (isAuth) {
                    authRepository.clearTokens(); _isSignedIn.value = false
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
            is VoiceCommand.Read          -> readCurrentEmail(emails)
            is VoiceCommand.Next          -> advanceEmail(+1, emails)
            is VoiceCommand.Previous      -> advanceEmail(-1, emails)
            is VoiceCommand.Repeat        -> repeatCurrentEmail(emails)
            is VoiceCommand.Refresh       -> loadInbox()
            is VoiceCommand.Compose       -> viewModelScope.launch {
                _navigationEvent.emit(InboxNavEvent.NavigateToCompose())
            }
            is VoiceCommand.Reply         -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null) viewModelScope.launch {
                    _navigationEvent.emit(InboxNavEvent.NavigateToCompose(replyTo = email.from))
                } else voiceCommandEngine.speakThenListen(
                    "No email selected. Say 'read' to hear an email first."
                ) { cmd -> handleCommand(cmd, emails) }
            }
            is VoiceCommand.Delete        -> confirmDelete(emails)
            is VoiceCommand.Forward       -> startForward(emails)
            is VoiceCommand.MarkAsRead    -> markCurrentAsRead(emails)
            is VoiceCommand.Search        -> startSearch(emails)
            is VoiceCommand.ListAttachments -> listAttachments(emails)
            is VoiceCommand.ReadAttachment  -> readAttachment(command.index, emails)
            is VoiceCommand.ReadAllUnread   -> readAllUnread(emails)
            is VoiceCommand.TryAgain      -> loadInbox()
            is VoiceCommand.Cancel, is VoiceCommand.GoBack ->
                voiceCommandEngine.speakThenListen(
                    "Cancelled. Say a command or 'help' for options."
                ) { cmd -> handleCommand(cmd, emails) }
            is VoiceCommand.Confirm ->
                voiceCommandEngine.speakThenListen(
                    "Nothing to confirm right now. Say a command."
                ) { cmd -> handleCommand(cmd, emails) }
            is VoiceCommand.Help ->
                voiceCommandEngine.speakThenListen(
                    "Commands: read, next, previous, repeat, reply, forward, delete, compose, " +
                        "search, refresh, read unread, mark as read, list attachments, " +
                        "voice settings, instructions. Say a command."
                ) { cmd -> handleCommand(cmd, emails) }
            is VoiceCommand.VoiceSettings -> handleVoiceSettings(emails)
            is VoiceCommand.Instructions  -> handleInstructions(emails)
            else ->
                voiceCommandEngine.speakThenListen(
                    "Sorry, I didn't understand that. Say 'help' to hear available commands."
                ) { cmd -> handleCommand(cmd, emails) }
        }
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private fun readCurrentEmail(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen("No more emails. Say 'refresh' or 'compose'.") { cmd ->
                handleCommand(cmd, emails)
            }; return
        }
        val unreadPrefix = if (email.isUnread) "Unread. " else ""
        val attachNote = when (email.attachments.size) {
            0    -> ""
            1    -> "This email has 1 attachment: ${email.attachments[0].filename}. "
            else -> "This email has ${email.attachments.size} attachments. Say 'list attachments' to hear them. "
        }
        val readAttachHint = if (email.attachments.isNotEmpty()) "'read attachment one' to read the first, " else ""
        voiceCommandEngine.speakThenListen(
            "${unreadPrefix}Email ${_currentEmailIndex.value + 1} of ${emails.size}. " +
                "From ${email.from}. Subject: ${email.subject}. ${email.body.take(500)}. " +
                attachNote + "Say 'reply', 'forward', 'delete', ${readAttachHint}'next', or 'repeat'."
        ) { cmd -> handleCommand(cmd, emails) }
    }

    private fun advanceEmail(delta: Int, emails: List<EmailItem>) {
        val newIdx = _currentEmailIndex.value + delta
        val email  = emails.getOrNull(newIdx)
        if (email != null) {
            _currentEmailIndex.value = newIdx
            voiceCommandEngine.speakThenListen(
                "Email ${newIdx + 1} of ${emails.size}. From ${email.from}: ${email.subject}. " +
                    "Say 'read' to hear the full message, 'reply', 'delete', or '${if (delta > 0) "next" else "previous"}'."
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
        if (email != null)
            voiceCommandEngine.speakThenListen(
                "Repeating email ${_currentEmailIndex.value + 1}. From ${email.from}. " +
                    "Subject: ${email.subject}. ${email.body.take(500)}"
            ) { cmd -> handleCommand(cmd, emails) }
        else
            voiceCommandEngine.speakThenListen("No email to repeat. Say 'read' first.") { cmd ->
                handleCommand(cmd, emails)
            }
    }

    private fun readAllUnread(emails: List<EmailItem>) {
        val unread = emails.filter { it.isUnread }
        if (unread.isEmpty()) {
            voiceCommandEngine.speakThenListen("No unread emails. All caught up!") { cmd ->
                handleCommand(cmd, emails)
            }; return
        }
        val idx = emails.indexOf(unread.first()).coerceAtLeast(0)
        _currentEmailIndex.value = idx
        val email = unread.first()
        voiceCommandEngine.speakThenListen(
            "You have ${unread.size} unread email${if (unread.size == 1) "" else "s"}. " +
                "First unread: From ${email.from}. Subject: ${email.subject}. " +
                "Say 'read' to hear it in full, or 'next' for the next email."
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
            ) { cmd -> handleCommand(cmd, emails) }; return
        }
        val names = email.attachments.mapIndexed { i, a -> "${i + 1}: ${a.filename}" }.joinToString(". ")
        voiceCommandEngine.speakThenListen("$names. Say 'read attachment one' to read the first.") { cmd ->
            handleCommand(cmd, emails)
        }
    }

    private fun readAttachment(oneBasedIndex: Int, emails: List<EmailItem>) {
        val email      = emails.getOrNull(_currentEmailIndex.value)
        val attachment = email?.attachments?.getOrNull(oneBasedIndex - 1)
        if (attachment == null) {
            val total = email?.attachments?.size ?: 0
            voiceCommandEngine.speakThenListen(
                if (total == 0) "This email has no attachments."
                else "No attachment number $oneBasedIndex. This email has $total attachment${if (total == 1) "" else "s"}."
            ) { cmd -> handleCommand(cmd, emails) }; return
        }
        viewModelScope.launch {
            voiceManager.speak("Reading ${attachment.filename}. Please wait.")
            try {
                val text = attachmentReader.readAttachment(email.id, attachment)
                voiceCommandEngine.speakThenListen(text) { cmd -> handleCommand(cmd, emails) }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen("Could not read attachment. ${e.message ?: "Unknown error."}") { cmd ->
                    handleCommand(cmd, emails)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    private fun confirmDelete(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen("No email selected to delete.") { cmd ->
                handleCommand(cmd, emails)
            }; return
        }
        voiceCommandEngine.speakThenListen(
            "Delete email from ${email.from}, subject: ${email.subject}? Say 'yes' to confirm or 'cancel' to go back."
        ) { cmd -> handleDeleteConfirmation(cmd, email, emails) }
    }

    private fun handleDeleteConfirmation(command: VoiceCommand, email: EmailItem, emails: List<EmailItem>) {
        when (command) {
            is VoiceCommand.Confirm -> viewModelScope.launch {
                try {
                    gmailRepository.trashEmail(email.id)
                    val updated = emails.filter { it.id != email.id }
                    _currentEmailIndex.value =
                        _currentEmailIndex.value.coerceAtMost((updated.size - 1).coerceAtLeast(0))
                    _uiState.value = InboxUiState.Success(updated)
                    val nextEmail = updated.getOrNull(_currentEmailIndex.value)
                    val msg = if (nextEmail != null)
                        "Deleted. Next: From ${nextEmail.from}. ${nextEmail.subject}. Say 'read'."
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
                if (email == null) "No email selected." else "This email is already read."
            ) { cmd -> handleCommand(cmd, emails) }; return
        }
        viewModelScope.launch {
            try {
                gmailRepository.markAsRead(email.id)
                val updated = emails.map { if (it.id == email.id) it.copy(isUnread = false) else it }
                _uiState.value = InboxUiState.Success(updated)
                voiceCommandEngine.speakThenListen("Marked as read.") { cmd -> handleCommand(cmd, updated) }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen("Could not mark as read. ${e.message ?: "Unknown error."}") { cmd ->
                    handleCommand(cmd, emails)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Forward
    // ------------------------------------------------------------------

    private fun startForward(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen("No email selected to forward.") { cmd ->
                handleCommand(cmd, emails)
            }; return
        }
        voiceCommandEngine.speakThenListen("Who would you like to forward this to? Please say the email address.") { cmd ->
            val raw = (cmd as? VoiceCommand.FreeText)?.text ?: voiceManager.recognizedText.value ?: ""
            when {
                cmd is VoiceCommand.Cancel ->
                    voiceCommandEngine.speakThenListen("Forward cancelled.") { c -> handleCommand(c, emails) }
                raw.isNotBlank() -> launchForward(email, raw, emails)
                else ->
                    voiceCommandEngine.speakThenListen("I didn't catch that. Say the email address or 'cancel'.") { c ->
                        handleCommand(c, emails)
                    }
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
            "What would you like to search for? For example: 'emails from David', 'subject meeting', or 'unread emails'."
        ) { cmd ->
            val raw = (cmd as? VoiceCommand.FreeText)?.text ?: voiceManager.recognizedText.value ?: ""
            when {
                cmd is VoiceCommand.Cancel ->
                    voiceCommandEngine.speakThenListen("Search cancelled.") { c -> handleCommand(c, originEmails) }
                raw.isNotBlank() -> executeSearch(raw, originEmails)
                else ->
                    voiceCommandEngine.speakThenListen(
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
                                (cmd is VoiceCommand.FreeText && cmd.text.lowercase().contains("inbox")) ->
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
                            "Email 1: From ${first.from}: ${first.subject}. Say 'read', 'next', or 'search again'."
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
    // Voice settings — voice-guided English US voice chooser
    // ------------------------------------------------------------------

    private fun handleVoiceSettings(emails: List<EmailItem>) {
        // Filter to English United States voices only.
        val voices = voiceManager.getAvailableVoices().filter { voice ->
            voice.locale.language == Locale.US.language &&
                voice.locale.country == Locale.US.country
        }
        if (voices.isEmpty()) {
            voiceCommandEngine.speakThenListen(
                "No English United States voices found. Install Google Text-to-Speech " +
                    "from the Play Store, or use the settings icon to switch engine."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        val originalVoiceName = voiceManager.getCurrentVoiceName()
        val count = voices.size
        voiceCommandEngine.speakThenListen(
            "Voice chooser. $count English U.S. voice${if (count == 1) "" else "s"} available. " +
                "Each will introduce itself. Say 'next' to move on, 'keep it' to save, or 'cancel' to go back."
        ) { _ -> browseVoice(voices, 0, 0, originalVoiceName, emails) }
    }

    /**
     * Plays each English US voice's sample in that voice itself so the user
     * can audition it. Accepts "next", "keep it", "yes", "previous", or "cancel".
     * Times out after [MAX_VOICE_BROWSE_FAILS] consecutive non-responses.
     */
    private fun browseVoice(
        voices: List<Voice>,
        index: Int,
        failCount: Int,
        originalVoiceName: String?,
        emails: List<EmailItem>
    ) {
        val voice  = voices[index]
        val total  = voices.size
        // The sample is spoken IN the candidate voice — the user hears what it sounds like.
        val sample = "Hello. This is English U.S.A. voice ${index + 1} of $total. What do you think?"

        voiceCommandEngine.speakWithVoiceAndListen(sample, voice) { cmd ->
            val raw = ((cmd as? VoiceCommand.FreeText)?.text
                ?: voiceManager.recognizedText.value ?: "").lowercase()

            when {
                // --- Keep this voice ---
                cmd is VoiceCommand.Confirm ||
                    raw.contains("keep") || raw.contains("yes") || raw.contains("good") ||
                    raw.contains("like") || raw.contains("love") || raw.contains("this one") ||
                    raw.contains("that one") || raw.contains("perfect") || raw.contains("great") -> {
                    voiceManager.setVoiceByName(voice.name)
                    voiceCommandEngine.speakThenListen(
                        "Voice ${index + 1} saved."
                    ) { c -> handleCommand(c, emails) }
                }

                // --- Next voice (wraps around) ---
                cmd is VoiceCommand.Next || raw.contains("next") || raw.contains("skip") -> {
                    browseVoice(voices, (index + 1) % total, 0, originalVoiceName, emails)
                }

                // --- Previous voice (wraps around) ---
                cmd is VoiceCommand.Previous ||
                    raw.contains("previous") || raw.contains("last") || raw.contains("back") -> {
                    browseVoice(voices, if (index == 0) total - 1 else index - 1, 0, originalVoiceName, emails)
                }

                // --- Cancel — restore original ---
                cmd is VoiceCommand.Cancel ||
                    raw.contains("cancel") || raw.contains("stop") || raw.contains("never mind") -> {
                    restoreOriginalVoice(originalVoiceName)
                    voiceCommandEngine.speakThenListen(
                        "Voice unchanged."
                    ) { c -> handleCommand(c, emails) }
                }

                // --- No response or unrecognised ---
                else -> {
                    val newFail = failCount + 1
                    if (newFail >= MAX_VOICE_BROWSE_FAILS) {
                        // Timed out after 3 attempts — restore and return.
                        restoreOriginalVoice(originalVoiceName)
                        voiceCommandEngine.speakThenListen(
                            "No selection made. Voice unchanged."
                        ) { c -> handleCommand(c, emails) }
                    } else {
                        // Brief hint, then replay the same voice's sample.
                        voiceCommandEngine.speakThenListen(
                            "Say 'next' to hear the next voice, 'keep it' to save this one, or 'cancel'."
                        ) { reminderCmd ->
                            val reminderRaw = ((reminderCmd as? VoiceCommand.FreeText)?.text
                                ?: voiceManager.recognizedText.value ?: "").lowercase()
                            when {
                                reminderCmd is VoiceCommand.Next || reminderRaw.contains("next") ->
                                    browseVoice(voices, (index + 1) % total, 0, originalVoiceName, emails)
                                reminderCmd is VoiceCommand.Confirm ||
                                    reminderRaw.contains("keep") || reminderRaw.contains("yes") -> {
                                    voiceManager.setVoiceByName(voice.name)
                                    voiceCommandEngine.speakThenListen("Voice ${index + 1} saved.") { c ->
                                        handleCommand(c, emails)
                                    }
                                }
                                reminderCmd is VoiceCommand.Cancel || reminderRaw.contains("cancel") -> {
                                    restoreOriginalVoice(originalVoiceName)
                                    voiceCommandEngine.speakThenListen("Voice unchanged.") { c ->
                                        handleCommand(c, emails)
                                    }
                                }
                                // Still no response — loop back with incremented fail count.
                                else -> browseVoice(voices, index, newFail, originalVoiceName, emails)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun restoreOriginalVoice(originalVoiceName: String?) {
        if (originalVoiceName != null) voiceManager.setVoiceByName(originalVoiceName)
        else voiceManager.clearVoicePreference()
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
                is VoiceCommand.Repeat -> handleInstructions(emails, section)
                is VoiceCommand.Cancel, is VoiceCommand.GoBack ->
                    voiceCommandEngine.speakThenListen("Returning to inbox. Say 'read' or 'help'.") { c ->
                        handleCommand(c, emails)
                    }
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

    private fun timeOfDayGreeting(): String =
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11  -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else      -> "Good night"
        }

    private fun currentEmails(): List<EmailItem> =
        (_uiState.value as? InboxUiState.Success)?.emails ?: emptyList()

    private companion object {
        /** Give up the voice chooser after this many consecutive non-responses. */
        const val MAX_VOICE_BROWSE_FAILS = 3

        val INSTRUCTION_SECTIONS = listOf(
            "Instructions. Section 1 of 8: Overview. " +
                "VoiceGmail is completely hands-free. After every spoken message, the microphone opens automatically. " +
                "Wake the app at any time by pressing the power button. " +
                "Navigate sections with: next, previous, repeat, cancel. Say 'next' to continue.",
            "Section 2 of 8: Reading your mail. " +
                "Say 'read' to hear the current email. " +
                "Say 'next' to move forward, 'previous' to go back, 'repeat' to hear again. " +
                "Say 'read unread' to jump to your first unread message. Say 'next' to continue.",
            "Section 3 of 8: Replying and composing. " +
                "Say 'reply' to reply to the current email. Say 'compose' to write a new email. " +
                "When composing, the app guides you step by step. " +
                "Say 'send' when done, 'read back' to review, or 'cancel' to discard. Say 'next' to continue.",
            "Section 4 of 8: Managing email. " +
                "Say 'delete' — then 'yes' to confirm or 'cancel' to abort. " +
                "Say 'mark as read' to clear the unread badge. " +
                "Say 'forward' to forward to someone else. Say 'next' to continue.",
            "Section 5 of 8: Searching. " +
                "Say 'search' and then describe what you want: " +
                "'emails from David', 'subject meeting', or 'unread emails'. " +
                "Use 'next', 'previous', and 'read' in search results. Say 'next' to continue.",
            "Section 6 of 8: Attachments. " +
                "Say 'list attachments' to hear what files are attached. " +
                "Say 'read attachment one' to have the first attachment read aloud. " +
                "P D F, Word, and plain text files are supported. Say 'next' to continue.",
            "Section 7 of 8: Voice settings. " +
                "Say 'voice settings' to browse and audition English U.S. voices by voice command. " +
                "Each voice will introduce itself. Say 'next' to try the next, 'keep it' to save. " +
                "Sighted helpers can also tap the Settings icon to open a visual panel. Say 'next' to continue.",
            "Section 8 of 8: Wake from lock screen. " +
                "Press the power button to wake your device. " +
                "VoiceGmail appears on screen and says: VoiceGmail ready, say a command. " +
                "You do not need to unlock your phone. " +
                "That is the end of the instructions. " +
                "Say 'previous', 'repeat', or any command to return to your inbox."
        )
    }
}

sealed class InboxUiState {
    object Loading   : InboxUiState()
    object SignedOut : InboxUiState()
    data class Success(val emails: List<EmailItem>) : InboxUiState()
    data class Error(val message: String, val isAuthError: Boolean = false) : InboxUiState()
}

sealed class InboxNavEvent {
    data class NavigateToCompose(
        val replyTo: String?   = null,
        val isForward: Boolean = false
    ) : InboxNavEvent()
}
