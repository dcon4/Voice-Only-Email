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
import com.example.voicegmail.gmail.AttachmentReader
import com.example.voicegmail.gmail.DraftItem
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
    @Suppress("unused") private val forwardDraft: ForwardDraft,
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
    // Visual settings panel
    // ------------------------------------------------------------------

    fun openSettingsPanel() {
        _availableEngines.value   = voiceManager.getAvailableEngines()
        _settingsVoices.value     = voiceManager.getAvailableVoices()
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

    fun openVisualCompose() {
        viewModelScope.launch { _navigationEvent.emit(InboxNavEvent.NavigateToCompose()) }
    }

    // ------------------------------------------------------------------
    // Wake
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
                        "${greeting}You have $u out of $t. Say 'reed unread', 'reed', 'search', or 'compose'."
                    }
                    else -> {
                        val t = if (emails.size == 1) "1 email" else "${emails.size} emails"
                        "${greeting}Your inbox is all caught up. You have $t. Say 'reed', 'search', or 'compose'."
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
            is VoiceCommand.Read         -> readCurrentEmail(emails)
            is VoiceCommand.Next         -> advanceEmail(+1, emails)
            is VoiceCommand.Previous     -> advanceEmail(-1, emails)
            is VoiceCommand.Repeat       -> repeatCurrentEmail(emails)
            is VoiceCommand.Refresh      -> loadInbox()
            is VoiceCommand.Compose      -> startVoiceCompose(emails)
            is VoiceCommand.Reply -> {
                val email = emails.getOrNull(_currentEmailIndex.value)
                if (email != null)
                    startVoiceCompose(
                        emails,
                        replyTo      = email.from,
                        replySubject = "Re: ${email.subject}"
                    )
                else
                    voiceCommandEngine.speakThenListen(
                        "No email selected. Say 'reed' to hear an email first."
                    ) { cmd -> handleCommand(cmd, emails) }
            }
            is VoiceCommand.Forward      -> startVoiceForward(emails)
            is VoiceCommand.Delete       -> confirmDelete(emails)
            is VoiceCommand.MarkAsRead   -> markCurrentAsRead(emails)
            is VoiceCommand.Search       -> startSearch(emails)
            is VoiceCommand.ListDrafts   -> startDraftFlow(emails)
            is VoiceCommand.ListAttachments  -> listAttachments(emails)
            is VoiceCommand.ReadAttachment   -> readAttachment(command.index, emails)
            is VoiceCommand.ReadAllUnread    -> readAllUnread(emails)
            is VoiceCommand.TryAgain     -> loadInbox()

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
                    "Commands: reed, next, previous, repeat, reply, forward, delete, compose, " +
                        "search, refresh, reed unread, mark as read, list attachments, " +
                        "list drafts, read slower, read faster, voice settings, instructions. " +
                        "While composing: add more, delete last word or sentence, start over, " +
                        "save draft, read back, send, cancel."
                ) { cmd -> handleCommand(cmd, emails) }

            is VoiceCommand.ReadSlower -> {
                voiceManager.adjustEmailReadRate(-0.10f)
                val pct = voiceManager.getEmailReadRatePct()
                voiceCommandEngine.speakThenListen(
                    "Email reading speed reduced to $pct percent. Say 'repeat' to hear the current email at the new speed."
                ) { cmd -> handleCommand(cmd, emails) }
            }
            is VoiceCommand.ReadFaster -> {
                voiceManager.adjustEmailReadRate(+0.10f)
                val pct = voiceManager.getEmailReadRatePct()
                voiceCommandEngine.speakThenListen(
                    "Email reading speed increased to $pct percent. Say 'repeat' to hear the current email at the new speed."
                ) { cmd -> handleCommand(cmd, emails) }
            }

            is VoiceCommand.SessionTimeout ->
                voiceManager.speak("Going to sleep. Press the power button to wake me.")

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
        voiceCommandEngine.speakEmailThenListen(
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
                    "Say 'reed' to hear it, 'reply', 'delete', or '${if (delta > 0) "next" else "previous"}'."
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
            voiceCommandEngine.speakEmailThenListen(
                "Repeating email ${_currentEmailIndex.value + 1}. From ${email.from}. " +
                    "Subject: ${email.subject}. ${email.body.take(500)}"
            ) { cmd -> handleCommand(cmd, emails) }
        else
            voiceCommandEngine.speakThenListen("No email to repeat. Say 'reed' first.") { cmd ->
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
        voiceCommandEngine.speakEmailThenListen(
            "You have ${unread.size} unread email${if (unread.size == 1) "" else "s"}. " +
                "First unread: From ${email.from}. Subject: ${email.subject}. " +
                "Say 'reed' to hear it in full, or 'next' for the next email."
        ) { cmd -> handleCommand(cmd, emails) }
    }

    fun readEmailAloud(email: EmailItem) {
        val emails = currentEmails()
        val idx = emails.indexOf(email).takeIf { it >= 0 } ?: _currentEmailIndex.value
        _currentEmailIndex.value = idx
        voiceCommandEngine.speakEmailThenListen(
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
                voiceCommandEngine.speakEmailThenListen(text) { cmd -> handleCommand(cmd, emails) }
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
            voiceCommandEngine.speakThenListen("No email selected to delete.") { cmd ->
                handleCommand(cmd, emails)
            }; return
        }
        voiceCommandEngine.speakThenListen(
            "Delete email from ${email.from}, subject: ${email.subject}? Say 'yes' to confirm or 'cancel'."
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
                        "Deleted. Next: From ${nextEmail.from}. ${nextEmail.subject}. Say 'reed'."
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
                    "Say 'yes' to confirm delete or 'cancel'."
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
                voiceCommandEngine.speakThenListen(
                    "Could not mark as read. ${e.message ?: "Unknown error."}"
                ) { cmd -> handleCommand(cmd, emails) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Voice compose — fully inline, no screen navigation
    // ------------------------------------------------------------------

    private fun startVoiceCompose(
        emails: List<EmailItem>,
        replyTo: String? = null,
        replySubject: String? = null
    ) {
        if (replyTo != null) {
            voiceCommandEngine.speakThenListen(
                "Reply to $replyTo. Subject: $replySubject. Say your reply now."
            ) { cmd -> handleComposeBody(replyTo, replySubject ?: "", cmd, emails) }
        } else {
            voiceCommandEngine.speakThenListen(
                "Compose. Who would you like to send to? Say the email address."
            ) { cmd -> handleComposeTo(cmd, emails) }
        }
    }

    private fun handleComposeTo(cmd: VoiceCommand, emails: List<EmailItem>) {
        val raw = rawText(cmd)
        when {
            cmd is VoiceCommand.Cancel ->
                voiceCommandEngine.speakThenListen("Compose cancelled.") { c -> handleCommand(c, emails) }
            raw.isNotBlank() ->
                voiceCommandEngine.speakThenListen("Sending to $raw. What is the subject?") { c ->
                    handleComposeSubject(raw, c, emails)
                }
            else ->
                voiceCommandEngine.speakThenListen(
                    "I didn't catch the email address. Please try again, or say 'cancel'."
                ) { c -> handleComposeTo(c, emails) }
        }
    }

    private fun handleComposeSubject(to: String, cmd: VoiceCommand, emails: List<EmailItem>) {
        val raw = rawText(cmd)
        when {
            cmd is VoiceCommand.Cancel ->
                voiceCommandEngine.speakThenListen("Compose cancelled.") { c -> handleCommand(c, emails) }
            raw.isNotBlank() ->
                voiceCommandEngine.speakThenListen("Subject: $raw. Now say your message.") { c ->
                    handleComposeBody(to, raw, c, emails)
                }
            else ->
                voiceCommandEngine.speakThenListen(
                    "I didn't catch the subject. Please say it again, or say 'cancel'."
                ) { c -> handleComposeSubject(to, c, emails) }
        }
    }

    private fun handleComposeBody(
        to: String,
        subject: String,
        cmd: VoiceCommand,
        emails: List<EmailItem>
    ) {
        val raw = rawText(cmd)
        when {
            cmd is VoiceCommand.Cancel ->
                voiceCommandEngine.speakThenListen("Compose cancelled.") { c -> handleCommand(c, emails) }
            cmd is VoiceCommand.Send ->
                voiceComposeSend(to, subject, "", emails)
            raw.isNotBlank() ->
                voiceCommandEngine.speakThenListen(
                    "Message recorded. Say 'send', 'read back', 'add more', " +
                        "'delete last word or sentence', 'start over', 'save draft', or 'cancel'."
                ) { c -> handleComposeFinal(to, subject, raw, c, emails) }
            else ->
                voiceCommandEngine.speakThenListen(
                    "I didn't catch your message. Please try again, or say 'cancel'."
                ) { c -> handleComposeBody(to, subject, c, emails) }
        }
    }

    private fun handleComposeFinal(
        to: String,
        subject: String,
        body: String,
        cmd: VoiceCommand,
        emails: List<EmailItem>
    ) {
        when (cmd) {
            is VoiceCommand.Send     -> voiceComposeSend(to, subject, body, emails)
            is VoiceCommand.ReadBack ->
                voiceCommandEngine.speakThenListen(
                    "To: $to. Subject: $subject. Message: $body. " +
                        "Say 'send', 'add more', 'delete last word or sentence', " +
                        "'start over', 'save draft', or 'cancel'."
                ) { c -> handleComposeFinal(to, subject, body, c, emails) }

            is VoiceCommand.AddMore ->
                voiceCommandEngine.speakThenListen("Say what you'd like to add.") { c ->
                    val extra = rawText(c)
                    if (extra.isNotBlank())
                        handleComposeFinal(to, subject, "$body $extra", VoiceCommand.None, emails)
                    else
                        voiceCommandEngine.speakThenListen(
                            "Nothing added. Say 'send', 'add more', 'delete last word', 'start over', or 'cancel'."
                        ) { cx -> handleComposeFinal(to, subject, body, cx, emails) }
                }

            is VoiceCommand.StartOver ->
                voiceCommandEngine.speakThenListen("Body cleared. Say your new message.") { c ->
                    handleComposeBody(to, subject, c, emails)
                }

            is VoiceCommand.DeleteLast -> {
                val newBody = when (cmd.unit) {
                    "paragraph" -> deleteLastParagraph(body)
                    "sentence"  -> deleteLastSentence(body)
                    else        -> deleteLastWord(body)
                }
                if (newBody == body) {
                    voiceCommandEngine.speakThenListen(
                        "Nothing to remove. Say 'send', 'add more', or 'cancel'."
                    ) { c -> handleComposeFinal(to, subject, body, c, emails) }
                } else {
                    val preview = newBody.takeLast(80).ifBlank { "Message is now empty." }
                    voiceCommandEngine.speakThenListen(
                        "Deleted. Now ends with: $preview. " +
                            "Say 'send', 'read back', 'add more', 'delete last word', or 'cancel'."
                    ) { c -> handleComposeFinal(to, subject, newBody, c, emails) }
                }
            }

            is VoiceCommand.SaveDraft -> {
                viewModelScope.launch {
                    try {
                        gmailRepository.saveDraft(to, subject, body)
                        voiceCommandEngine.speakThenListen(
                            "Draft saved. Say a command, or 'list drafts' to see your drafts."
                        ) { c -> handleCommand(c, emails) }
                    } catch (e: Exception) {
                        voiceCommandEngine.speakThenListen(
                            "Could not save draft. ${e.message ?: "Unknown error."}. Say a command."
                        ) { c -> handleCommand(c, emails) }
                    }
                }
            }

            is VoiceCommand.Cancel, is VoiceCommand.GoBack ->
                voiceCommandEngine.speakThenListen("Compose cancelled.") { c -> handleCommand(c, emails) }

            is VoiceCommand.None ->
                // Called programmatically after AddMore appends text.
                voiceCommandEngine.speakThenListen(
                    "Added. Say 'send', 'read back', 'add more', 'delete last word or sentence', " +
                        "'start over', 'save draft', or 'cancel'."
                ) { c -> handleComposeFinal(to, subject, body, c, emails) }

            else ->
                voiceCommandEngine.speakThenListen(
                    "Say 'send', 'read back', 'add more', 'delete last word or sentence', " +
                        "'start over', 'save draft', or 'cancel'."
                ) { c -> handleComposeFinal(to, subject, body, c, emails) }
        }
    }

    private fun voiceComposeSend(to: String, subject: String, body: String, emails: List<EmailItem>) {
        viewModelScope.launch {
            voiceManager.speak("Sending.")
            try {
                gmailRepository.sendEmail(to, subject, body)
                voiceCommandEngine.speakThenListen("Sent successfully.") { c -> handleCommand(c, emails) }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen(
                    "Failed to send. ${e.message ?: "Unknown error."}. Say 'try again' or 'cancel'."
                ) { c ->
                    when (c) {
                        is VoiceCommand.TryAgain -> voiceComposeSend(to, subject, body, emails)
                        else -> handleCommand(c, emails)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Body editing helpers
    // ------------------------------------------------------------------

    /** Remove the last whitespace-delimited word from [text]. */
    private fun deleteLastWord(text: String): String {
        val t = text.trimEnd()
        val idx = t.lastIndexOf(' ')
        return if (idx < 0) "" else t.substring(0, idx)
    }

    /**
     * Remove everything after the second-to-last sentence boundary (.!?).
     * If there is only one sentence, clear the body entirely.
     */
    private fun deleteLastSentence(text: String): String {
        val t = text.trimEnd()
        // Find the last sentence-ending punctuation that is NOT the very last character.
        val match = Regex("""[.!?](?=[^.!?]*${'$'})""").find(t.dropLast(1))
        return if (match != null) t.substring(0, match.range.last + 1).trimEnd() else ""
    }

    /** Remove the last newline-delimited paragraph from [text]. */
    private fun deleteLastParagraph(text: String): String {
        val t = text.trimEnd()
        val idx = t.lastIndexOf('\n')
        return if (idx < 0) "" else t.substring(0, idx).trimEnd()
    }

    // ------------------------------------------------------------------
    // Voice forward
    // ------------------------------------------------------------------

    private fun startVoiceForward(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen("No email selected to forward.") { c -> handleCommand(c, emails) }
            return
        }
        voiceCommandEngine.speakThenListen(
            "Forward email from ${email.from}: ${email.subject}. " +
                "Who would you like to forward to? Say the email address."
        ) { cmd ->
            val raw = rawText(cmd)
            when {
                cmd is VoiceCommand.Cancel ->
                    voiceCommandEngine.speakThenListen("Forward cancelled.") { c -> handleCommand(c, emails) }
                raw.isNotBlank() -> {
                    val fwdBody =
                        "---------- Forwarded message ----------\n" +
                        "From: ${email.from}\nSubject: ${email.subject}\n\n${email.body}"
                    voiceCommandEngine.speakThenListen(
                        "Forwarding to $raw. Say 'send' to forward now, or say a note to add before the message."
                    ) { c ->
                        val note = rawText(c)
                        when {
                            c is VoiceCommand.Cancel ->
                                voiceCommandEngine.speakThenListen("Forward cancelled.") { x ->
                                    handleCommand(x, emails)
                                }
                            c is VoiceCommand.Send || note.isBlank() ->
                                voiceComposeSend(raw, "Fwd: ${email.subject}", fwdBody, emails)
                            else ->
                                voiceComposeSend(raw, "Fwd: ${email.subject}", "$note\n\n$fwdBody", emails)
                        }
                    }
                }
                else ->
                    voiceCommandEngine.speakThenListen(
                        "I didn't catch that. Say the email address or 'cancel'."
                    ) { _ -> startVoiceForward(emails) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    private fun startSearch(originEmails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen(
            "What would you like to search for? For example: 'emails from David', " +
                "'subject meeting', or 'unread emails'."
        ) { cmd ->
            val raw = (cmd as? VoiceCommand.FreeText)?.text ?: voiceManager.recognizedText.value ?: ""
            when {
                cmd is VoiceCommand.Cancel ->
                    voiceCommandEngine.speakThenListen("Search cancelled.") { c ->
                        handleCommand(c, originEmails)
                    }
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
            voiceManager.speak("Searching.")
            try {
                val results = gmailRepository.searchEmails(query)
                if (results.isEmpty()) {
                    voiceCommandEngine.speakThenListen(
                        "No emails found matching '$rawQuery'. Say 'search again' or 'refresh'."
                    ) { cmd ->
                        when {
                            cmd is VoiceCommand.Search -> startSearch(originEmails)
                            else -> handleCommand(VoiceCommand.Refresh, originEmails)
                        }
                    }
                } else {
                    val first = results[0]
                    _currentEmailIndex.value = 0
                    _uiState.value = InboxUiState.Success(results)
                    voiceCommandEngine.speakThenListen(
                        "Found ${results.size} email${if (results.size == 1) "" else "s"} matching '$rawQuery'. " +
                            "Email 1: From ${first.from}: ${first.subject}. " +
                            "Say 'reed', 'next', or 'search again'."
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
    // Drafts
    // ------------------------------------------------------------------

    private fun startDraftFlow(emails: List<EmailItem>) {
        viewModelScope.launch {
            voiceManager.speak("Loading drafts.")
            try {
                val drafts = gmailRepository.listDrafts()
                if (drafts.isEmpty()) {
                    voiceCommandEngine.speakThenListen(
                        "You have no saved drafts. Say 'compose' to start a new email."
                    ) { c -> handleCommand(c, emails) }
                    return@launch
                }
                val count   = drafts.size
                val preview = drafts.take(5).mapIndexed { i, d ->
                    "Draft ${i + 1}: to ${d.to.ifBlank { "unknown" }}, " +
                        "subject ${d.subject.ifBlank { "no subject" }}"
                }.joinToString(". ")
                voiceCommandEngine.speakThenListen(
                    "You have $count draft${if (count == 1) "" else "s"}. $preview. " +
                        "Say 'edit draft 1' to open one, 'delete draft 1' to discard one, or 'cancel'."
                ) { cmd ->
                    when (cmd) {
                        is VoiceCommand.EditDraft -> {
                            val draft = drafts.getOrNull(cmd.index - 1)
                            if (draft != null) resumeDraft(draft, drafts, emails)
                            else voiceCommandEngine.speakThenListen(
                                "No draft ${cmd.index}. You have $count draft${if (count == 1) "" else "s"}."
                            ) { _ -> startDraftFlow(emails) }
                        }
                        is VoiceCommand.DeleteDraft -> {
                            val draft = drafts.getOrNull(cmd.index - 1)
                            if (draft != null) voiceDraftDelete(draft, emails)
                            else voiceCommandEngine.speakThenListen(
                                "No draft ${cmd.index}."
                            ) { _ -> startDraftFlow(emails) }
                        }
                        is VoiceCommand.Cancel, is VoiceCommand.GoBack ->
                            voiceCommandEngine.speakThenListen("OK.") { c -> handleCommand(c, emails) }
                        else -> handleCommand(cmd, emails)
                    }
                }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen(
                    "Could not load drafts. ${e.message ?: "Unknown error."}"
                ) { c -> handleCommand(c, emails) }
            }
        }
    }

    /**
     * Read back the draft and enter an editing loop. The user can:
     *  - "send"          → send via Gmail drafts API
     *  - "add more"      → append more text to body
     *  - "delete last"   → trim last word/sentence/paragraph
     *  - "start over"    → keep To/Subject, re-dictate body
     *  - "save draft"    → update the existing draft and exit
     *  - "cancel"        → exit without changes
     */
    private fun resumeDraft(draft: DraftItem, allDrafts: List<DraftItem>, emails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen(
            "Draft ${allDrafts.indexOf(draft) + 1}. " +
                "To: ${draft.to.ifBlank { "unknown" }}. " +
                "Subject: ${draft.subject.ifBlank { "no subject" }}. " +
                "Body: ${draft.body.take(300).ifBlank { "no body yet" }}. " +
                "Say 'send', 'add more', 'delete last word or sentence', " +
                "'start over', 'save draft', or 'cancel'."
        ) { cmd -> handleDraftEdit(draft, draft.body, allDrafts, cmd, emails) }
    }

    private fun handleDraftEdit(
        draft: DraftItem,
        body: String,
        allDrafts: List<DraftItem>,
        cmd: VoiceCommand,
        emails: List<EmailItem>
    ) {
        when (cmd) {
            is VoiceCommand.Send -> {
                viewModelScope.launch {
                    voiceManager.speak("Sending.")
                    try {
                        // If the body was edited, update the draft first.
                        if (body != draft.body)
                            gmailRepository.updateDraft(draft.id, draft.to, draft.subject, body)
                        gmailRepository.sendDraft(draft.id)
                        voiceCommandEngine.speakThenListen("Sent.") { c -> handleCommand(c, emails) }
                    } catch (e: Exception) {
                        voiceCommandEngine.speakThenListen(
                            "Failed to send. ${e.message ?: "Unknown error."}. Say 'try again' or 'cancel'."
                        ) { c ->
                            when (c) {
                                is VoiceCommand.TryAgain ->
                                    handleDraftEdit(draft, body, allDrafts, VoiceCommand.Send, emails)
                                else -> handleCommand(c, emails)
                            }
                        }
                    }
                }
            }

            is VoiceCommand.AddMore ->
                voiceCommandEngine.speakThenListen("Say what you'd like to add.") { c ->
                    val extra = rawText(c)
                    if (extra.isNotBlank()) {
                        val newBody = "$body $extra".trim()
                        voiceCommandEngine.speakThenListen(
                            "Added. Say 'send', 'read back', 'add more', 'delete last word', 'save draft', or 'cancel'."
                        ) { cx -> handleDraftEdit(draft, newBody, allDrafts, cx, emails) }
                    } else {
                        resumeDraft(DraftItem(draft.id, draft.to, draft.subject, body), allDrafts, emails)
                    }
                }

            is VoiceCommand.StartOver ->
                voiceCommandEngine.speakThenListen("Body cleared. Say your new message.") { c ->
                    val newBody = rawText(c)
                    if (newBody.isNotBlank())
                        voiceCommandEngine.speakThenListen(
                            "Message recorded. Say 'send', 'read back', 'save draft', or 'cancel'."
                        ) { cx -> handleDraftEdit(draft, newBody, allDrafts, cx, emails) }
                    else
                        resumeDraft(draft, allDrafts, emails)
                }

            is VoiceCommand.DeleteLast -> {
                val newBody = when (cmd.unit) {
                    "paragraph" -> deleteLastParagraph(body)
                    "sentence"  -> deleteLastSentence(body)
                    else        -> deleteLastWord(body)
                }
                val preview = newBody.takeLast(80).ifBlank { "Body is now empty." }
                voiceCommandEngine.speakThenListen(
                    "Deleted. Now ends with: $preview. " +
                        "Say 'send', 'add more', 'delete last word', 'save draft', or 'cancel'."
                ) { c -> handleDraftEdit(draft, newBody, allDrafts, c, emails) }
            }

            is VoiceCommand.ReadBack ->
                voiceCommandEngine.speakThenListen(
                    "To: ${draft.to}. Subject: ${draft.subject}. Body: $body. " +
                        "Say 'send', 'add more', 'delete last word or sentence', 'save draft', or 'cancel'."
                ) { c -> handleDraftEdit(draft, body, allDrafts, c, emails) }

            is VoiceCommand.SaveDraft -> {
                viewModelScope.launch {
                    try {
                        gmailRepository.updateDraft(draft.id, draft.to, draft.subject, body)
                        voiceCommandEngine.speakThenListen(
                            "Draft updated. Say a command, or 'list drafts' to see your drafts."
                        ) { c -> handleCommand(c, emails) }
                    } catch (e: Exception) {
                        voiceCommandEngine.speakThenListen(
                            "Could not save draft. ${e.message ?: "Unknown error."}."
                        ) { c -> handleCommand(c, emails) }
                    }
                }
            }

            is VoiceCommand.Cancel, is VoiceCommand.GoBack ->
                voiceCommandEngine.speakThenListen("OK. Draft unchanged.") { c -> handleCommand(c, emails) }

            else ->
                voiceCommandEngine.speakThenListen(
                    "Say 'send', 'read back', 'add more', 'delete last word', 'save draft', or 'cancel'."
                ) { c -> handleDraftEdit(draft, body, allDrafts, c, emails) }
        }
    }

    private fun voiceDraftDelete(draft: DraftItem, emails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen(
            "Delete draft to ${draft.to.ifBlank { "unknown" }}, subject ${draft.subject.ifBlank { "no subject" }}? Say 'yes' or 'cancel'."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.Confirm -> viewModelScope.launch {
                    try {
                        gmailRepository.deleteDraft(draft.id)
                        voiceCommandEngine.speakThenListen(
                            "Draft deleted. Say 'list drafts' or a command."
                        ) { c -> handleCommand(c, emails) }
                    } catch (e: Exception) {
                        voiceCommandEngine.speakThenListen(
                            "Could not delete. ${e.message ?: "Unknown error."}."
                        ) { c -> handleCommand(c, emails) }
                    }
                }
                else ->
                    voiceCommandEngine.speakThenListen("Delete cancelled.") { c -> handleCommand(c, emails) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Voice settings — linear single-pass chooser
    // ------------------------------------------------------------------

    private fun handleVoiceSettings(emails: List<EmailItem>) {
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
                "Each will introduce itself. Say 'keep it' when you hear one you like, " +
                "or 'cancel' to exit. Otherwise I will move on automatically."
        ) { _ -> browseVoice(voices, 0, originalVoiceName, emails) }
    }

    private fun browseVoice(
        voices: List<Voice>,
        index: Int,
        originalVoiceName: String?,
        emails: List<EmailItem>
    ) {
        if (index >= voices.size) {
            restoreOriginalVoice(originalVoiceName)
            voiceCommandEngine.speakThenListen(
                "End of voice list. No selection made. Voice unchanged."
            ) { c -> handleCommand(c, emails) }
            return
        }
        val voice  = voices[index]
        val total  = voices.size
        val sample = "Hello. This is English U.S.A. voice ${index + 1} of $total. What do you think?"
        voiceCommandEngine.speakWithVoiceAndListen(sample, voice) { cmd ->
            val raw = ((cmd as? VoiceCommand.FreeText)?.text ?: "").lowercase()
            when {
                isVoiceKeepCommand(cmd, raw) -> {
                    voiceManager.setVoiceByName(voice.name)
                    voiceCommandEngine.speakThenListen(
                        "Voice ${index + 1} saved."
                    ) { c -> handleCommand(c, emails) }
                }
                isVoiceExitCommand(cmd, raw) -> {
                    restoreOriginalVoice(originalVoiceName)
                    voiceCommandEngine.speakThenListen("Voice unchanged.") { c -> handleCommand(c, emails) }
                }
                else -> browseVoice(voices, index + 1, originalVoiceName, emails)
            }
        }
    }

    private fun isVoiceKeepCommand(cmd: VoiceCommand, raw: String): Boolean =
        cmd is VoiceCommand.Confirm ||
            raw.contains("keep") || raw.contains("yes") || raw.contains("good") ||
            raw.contains("like") || raw.contains("love") || raw.contains("great") ||
            raw.contains("this one") || raw.contains("that one") || raw.contains("perfect") ||
            raw.contains("save") || raw.contains("use this") || raw.contains("use it") ||
            raw.contains("i want") || raw.contains("select")

    private fun isVoiceExitCommand(cmd: VoiceCommand, raw: String): Boolean =
        cmd is VoiceCommand.Cancel || cmd is VoiceCommand.GoBack ||
            raw.contains("cancel") || raw.contains("stop") || raw.contains("exit") ||
            raw.contains("never mind") || raw.contains("no thanks") || raw.contains("done") ||
            raw.contains("quit") || raw.contains("go back") || raw.contains("leave") ||
            raw.contains("back")

    private fun restoreOriginalVoice(originalVoiceName: String?) {
        if (originalVoiceName != null) voiceManager.setVoiceByName(originalVoiceName)
        else voiceManager.clearVoicePreference()
    }

    // ------------------------------------------------------------------
    // Instructions
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
                    voiceCommandEngine.speakThenListen(
                        "Returning to inbox. Say 'reed' or 'help'."
                    ) { c -> handleCommand(c, emails) }
                else -> handleCommand(cmd, emails)
            }
        }
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    private fun rawText(cmd: VoiceCommand): String =
        ((cmd as? VoiceCommand.FreeText)?.text?.trim()
            ?: voiceManager.recognizedText.value?.trim() ?: "")

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
        val INSTRUCTION_SECTIONS = listOf(
            "Instructions. Section 1 of 9: Overview. " +
                "VoiceGmail is completely hands-free. After every spoken message, the microphone opens automatically. " +
                "Wake the app at any time by pressing the power button. " +
                "Navigate sections with: next, previous, repeat, cancel. Say 'next' to continue.",
            "Section 2 of 9: Reading your mail. " +
                "Say 'reed' to hear the current email. " +
                "Say 'next' to move forward, 'previous' to go back, 'repeat' to hear again. " +
                "Say 'reed unread' to jump to your first unread message. Say 'next' to continue.",
            "Section 3 of 9: Composing and replying. " +
                "Say 'compose' — I will ask for the recipient, subject, and message, one at a time. " +
                "Say 'reply' to reply to the current email. Say 'forward' to forward it. " +
                "Say 'new email' as a shortcut for compose. Say 'next' to continue.",
            "Section 4 of 9: Editing your message before sending. " +
                "After dictating your message, you can say: " +
                "'read back' to hear the full message. " +
                "'add more' to append more text. " +
                "'delete last word', 'delete last sentence', or 'delete last paragraph' to trim the end. " +
                "'start over' to keep the recipient and subject but re-dictate the body from scratch. " +
                "Say 'next' to continue.",
            "Section 5 of 9: Sending and drafts. " +
                "Say 'send' to send the message. " +
                "Say 'save draft' to save without sending. " +
                "Say 'list drafts' to see your saved drafts. " +
                "Say 'edit draft 1' or 'edit draft 2' to reopen a draft. " +
                "Say 'delete draft 1' to discard a draft. " +
                "Say 'cancel' to discard the current compose session. Say 'next' to continue.",
            "Section 6 of 9: Managing received email. " +
                "Say 'delete' — then 'yes' to confirm or 'cancel' to abort. " +
                "Say 'mark as read' to clear the unread badge. " +
                "Say 'forward' to forward to someone else. Say 'next' to continue.",
            "Section 7 of 9: Searching. " +
                "Say 'search' and then describe what you want: " +
                "'emails from David', 'subject meeting', or 'unread emails'. " +
                "Use 'next', 'previous', and 'reed' in search results. Say 'next' to continue.",
            "Section 8 of 9: Attachments. " +
                "Say 'list attachments' to hear what files are attached. " +
                "Say 'reed attachment one' to have the first attachment read aloud. " +
                "P D F, Word, and plain text files are supported. Say 'next' to continue.",
            "Section 9 of 9: Voice and speed settings. " +
                "Say 'voice settings' to browse English U.S. voices. " +
                "Say 'read slower' or 'slow down' to decrease email reading speed by 10 percent. " +
                "Say 'read faster' or 'speed up' to increase it. " +
                "After adjusting, say 'repeat' to hear the current email at the new speed. " +
                "Press the power button to wake the app from sleep. " +
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
