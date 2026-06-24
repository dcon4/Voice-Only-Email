package com.example.voicegmail.ui.viewmodel

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.BuildConfig
import com.example.voicegmail.VoiceWakeService
import com.example.voicegmail.WakeEventBus
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.auth.OAuthDiagnostics
import com.example.voicegmail.auth.OAuthRedirectBus
import com.example.voicegmail.bible.BibleVoiceFlow
import com.example.voicegmail.audio.AudioPlayerVoiceFlow
import com.example.voicegmail.browser.BrowserVoiceFlow
import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.gmail.AttachmentReader
import com.example.voicegmail.gmail.DraftItem
import com.example.voicegmail.gmail.EmailItem
import com.example.voicegmail.gmail.GmailRepository
import com.example.voicegmail.voice.ForwardDraft
import com.example.voicegmail.voice.NaturalLanguageQueryParser
import com.example.voicegmail.contacts.ContactMatcher
import com.example.voicegmail.voice.VoiceCommand
import com.example.voicegmail.voice.VoiceCommandEngine
import com.example.voicegmail.voice.VoiceManager
import com.example.voicegmail.voice.forSpeech
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val gmailRepository: GmailRepository,
    private val voiceManager: VoiceManager,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val contactsRepository: com.example.voicegmail.contacts.ContactsRepository,
    private val queryParser: NaturalLanguageQueryParser,
    @Suppress("unused") private val forwardDraft: ForwardDraft,
    private val attachmentReader: AttachmentReader,
    private val oAuthRedirectBus: OAuthRedirectBus,
    private val wakeEventBus: WakeEventBus,
    private val bibleVoiceFlow: BibleVoiceFlow,
    private val browserVoiceFlow: BrowserVoiceFlow,
    private val audioPlayerVoiceFlow: AudioPlayerVoiceFlow,
    private val wakePreferences: com.example.voicegmail.voice.WakePreferences,
    private val mediaSessionController: com.example.voicegmail.media.MediaSessionController,
    private val ttsSettings: com.example.voicegmail.voice.TtsSettingsRepository,
    private val appLauncherPrefs: com.example.voicegmail.voice.AppLauncherPreferences,
    private val audioRepository: com.example.voicegmail.audio.AudioRepository
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

    private val _selectedBibleVoiceName = MutableStateFlow<String?>(null)
    val selectedBibleVoiceName: StateFlow<String?> = _selectedBibleVoiceName

    private val _bibleSelectedEngineName = MutableStateFlow<String?>(null)
    val bibleSelectedEngineName: StateFlow<String?> = _bibleSelectedEngineName

    private val _bibleSettingsVoices = MutableStateFlow<List<Voice>>(emptyList())
    val bibleSettingsVoices: StateFlow<List<Voice>> = _bibleSettingsVoices

    // ── Bible options sub-page ──────────────────────────────────────────────

    private val _bibleSettingsVisible = MutableStateFlow(false)
    val bibleSettingsVisible: StateFlow<Boolean> = _bibleSettingsVisible

    private val _bibleTranslation = MutableStateFlow("web")
    val bibleTranslation: StateFlow<String> = _bibleTranslation

    private val _bibleVerseNumbers = MutableStateFlow(true)
    val bibleVerseNumbers: StateFlow<Boolean> = _bibleVerseNumbers

    private val _isSwitchingEngine = MutableStateFlow(false)
    val isSwitchingEngine: StateFlow<Boolean> = _isSwitchingEngine

    private val _isSwitchingBibleEngine = MutableStateFlow(false)
    val isSwitchingBibleEngine: StateFlow<Boolean> = _isSwitchingBibleEngine

    private val _runInBackground = MutableStateFlow(true)
    val runInBackground: StateFlow<Boolean> = _runInBackground

    private val _verboseLogging = MutableStateFlow(false)
    val verboseLogging: StateFlow<Boolean> = _verboseLogging

    private val _batteryThreshold = MutableStateFlow(30)
    val batteryThreshold: StateFlow<Int> = _batteryThreshold

    private var lastLaunchedPackage: String? = null
    private val _launcherPanelVisible = MutableStateFlow(false)
    val launcherPanelVisible: StateFlow<Boolean> = _launcherPanelVisible

    // ------------------------------------------------------------------
    // Pause/resume reading state
    // ------------------------------------------------------------------

    /**
     * Saved position when email reading is interrupted (power button, "pause"
     * command, or session timeout mid-read).  Updated before every chunk is
     * spoken so a power-button wake always finds the correct position even if
     * TTS is mid-sentence.  Cleared when reading reaches the end normally or
     * the user issues a non-resume command.
     */
    private data class ReadingPosition(
        val emailId: String,
        val emailIndex: Int,
        val chunks: List<String>,
        val chunkIndex: Int
    )

    private var pausedPosition: ReadingPosition? = null

    /**
     * Monotonically-incrementing counter for reading sessions.  Incremented at
     * the start of every new read and on every wake event.  Passed into
     * [readNextChunk] so that stale TTS onDone callbacks (from utterances
     * flushed by QUEUE_FLUSH) can be detected and discarded without racing
     * the new session.
     */
    private var readingGen = 0
    @Volatile private var flowGen = 0L

    // ------------------------------------------------------------------
    // Init
    // ------------------------------------------------------------------

    init {
        // Initialize media session for BT/headset/notification controls
        mediaSessionController.initialize()
        mediaSessionController.onPlay = { handleMediaPlay() }
        mediaSessionController.onPause = { handleMediaPause() }
        mediaSessionController.onNext = { handleMediaNext() }
        mediaSessionController.onPrevious = { handleMediaPrevious() }

        viewModelScope.launch {
            try {
                val earlyRedirect = oAuthRedirectBus.redirectUri.value
                if (earlyRedirect != null) {
                    oAuthRedirectBus.consume()
                    handleOAuthRedirectUri(earlyRedirect)
                } else {
                    // If a previous install granted an older set of scopes (e.g.
                    // pre-People-API), clear those tokens so the next sign-in
                    // collects the new scope set in one consent dialog.
                    val forcedReConsent = authRepository.clearTokensIfReConsentRequired()
                    if (forcedReConsent) {
                        _isSignedIn.value = false
                        _uiState.value = InboxUiState.SignedOut
                        voiceCommandEngine.speakThenListen(
                            "VoiceGmail has been updated and needs additional " +
                                "permission to access your contacts. Say 'sign in' to begin."
                        ) { cmd -> handleCommand(cmd, emptyList()) }
                    } else {
                        val hasToken = authRepository.hasAccessToken().first()
                        _isSignedIn.value = hasToken
                        if (hasToken) loadInbox()
                        else {
                            _uiState.value = InboxUiState.SignedOut
                            voiceCommandEngine.speakThenListen(
                                "Please sign in to access your Gmail inbox. Say 'sign in' to begin."
                            ) { cmd -> handleCommand(cmd, emptyList()) }
                        }
                    }
                }
                oAuthRedirectBus.redirectUri.filterNotNull().collect { uri ->
                    oAuthRedirectBus.consume()
                    handleOAuthRedirectUri(uri)
                }
            } catch (e: Exception) {
                DebugLogger.log("InboxViewModel", "Init failed: ${e.message}")
                _uiState.value = InboxUiState.Error(
                    "Failed to initialize: ${e.message ?: "unknown error"}",
                    isAuthError = false
                )
                voiceManager.speak("VoiceGmail failed to start. Please try restarting the app.")
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
        _availableEngines.value       = voiceManager.getAvailableEngines()
        _settingsVoices.value         = filterEnglishVoices(voiceManager.getAvailableVoices())
        _selectedEngineName.value     = voiceManager.getCurrentEngineName()
        _selectedVoiceName.value      = voiceManager.getCurrentVoiceName()
        _selectedBibleVoiceName.value = voiceManager.bibleVoiceName.ifBlank { null }
        val bibleEng = voiceManager.bibleEnginePackage
        _bibleSelectedEngineName.value = bibleEng
        if (bibleEng != null) {
            loadBibleVoicesForEngine(bibleEng)
        }
        _runInBackground.value    = wakePreferences.isRunInBackground()
        _verboseLogging.value     = wakePreferences.isVerboseLogging()
        _batteryThreshold.value   = ttsSettings.getBatteryThreshold()
        _settingsPanelVisible.value = true
    }

    fun closeSettingsPanel() { _settingsPanelVisible.value = false }

    // ── App launcher panel ───────────────────────────────────────────────────

    fun openLauncherPanel() {
        _launcherApps.value = appLauncherPrefs.getApps()
        _launcherPanelVisible.value = true
    }
    fun closeLauncherPanel() { _launcherPanelVisible.value = false }

    private val _launcherApps = MutableStateFlow<List<com.example.voicegmail.voice.LauncherApp>>(emptyList())
    val launcherApps: StateFlow<List<com.example.voicegmail.voice.LauncherApp>> = _launcherApps

    fun saveLauncherApp(app: com.example.voicegmail.voice.LauncherApp) {
        appLauncherPrefs.saveApp(app)
        _launcherApps.value = appLauncherPrefs.getApps()
    }

    fun removeLauncherApp(packageName: String) {
        appLauncherPrefs.removeApp(packageName)
        _launcherApps.value = appLauncherPrefs.getApps()
    }

    // ── Direct app picker (from VoiceSettingsPanel) ─────────────────────────

    private val _showAppPicker = MutableStateFlow(false)
    val showAppPicker: StateFlow<Boolean> = _showAppPicker

    /** Opens the app picker dialog directly (bypasses the AppLauncherPanel overlay). */
    fun openAppPicker() {
        closeSettingsPanel()
        _launcherApps.value = appLauncherPrefs.getApps()
        _showAppPicker.value = true
    }

    fun closeAppPicker() { _showAppPicker.value = false }

    fun saveAppFromPicker(app: com.example.voicegmail.voice.LauncherApp) {
        appLauncherPrefs.saveApp(app)
        _launcherApps.value = appLauncherPrefs.getApps()
        _showAppPicker.value = false
    }

    // ── Audio player settings (sighted-helper panel) ───────────────────────

    private val _audioSettingsVisible = MutableStateFlow(false)
    val audioSettingsVisible: StateFlow<Boolean> = _audioSettingsVisible

    private val _audioFolderUris = MutableStateFlow<List<String>>(emptyList())
    val audioFolderUris: StateFlow<List<String>> = _audioFolderUris

    private val _audioTrackCount = MutableStateFlow(0)
    val audioTrackCount: StateFlow<Int> = _audioTrackCount

    private val _isAudioScanning = MutableStateFlow(false)
    val isAudioScanning: StateFlow<Boolean> = _isAudioScanning

    private val _fileAnnouncements = MutableStateFlow(false)
    val audioFileAnnouncements: StateFlow<Boolean> = _fileAnnouncements

    fun openAudioSettings() {
        refreshAudioState()
        _fileAnnouncements.value = audioRepository.getFileAnnouncements()
        _audioSettingsVisible.value = true
    }

    fun setAudioFileAnnouncements(enabled: Boolean) {
        audioRepository.setFileAnnouncements(enabled)
        _fileAnnouncements.value = enabled
    }

    fun closeAudioSettings() { _audioSettingsVisible.value = false }

    fun addAudioFolder(uri: Uri) {
        audioRepository.addFolder(uri)
        scanAudioFolders()
    }

    fun removeAudioFolder(uri: Uri) {
        audioRepository.removeFolder(uri)
        scanAudioFolders()
    }

    private fun refreshAudioState() {
        _audioFolderUris.value = audioRepository.getIndexedFolderUris().map { it.toString() }
        _audioTrackCount.value = audioRepository.trackCount()
    }

    fun scanAudioFolders() {
        _isAudioScanning.value = true
        viewModelScope.launch {
            audioRepository.scanAll()
            refreshAudioState()
            _isAudioScanning.value = false
        }
    }

    // ── Bible options sub-page ──────────────────────────────────────────────

    fun openBibleSettings() {
        _bibleTranslation.value = ttsSettings.getBibleTranslation()
        _bibleVerseNumbers.value = ttsSettings.getBibleVerseNumbers()
        _bibleSettingsVisible.value = true
    }

    fun closeBibleSettings() { _bibleSettingsVisible.value = false }

    fun setBibleTranslation(translation: String) {
        ttsSettings.saveBibleTranslation(translation)
        _bibleTranslation.value = translation
    }

    fun setBibleVerseNumbers(enabled: Boolean) {
        ttsSettings.saveBibleVerseNumbers(enabled)
        _bibleVerseNumbers.value = enabled
    }

    /**
     * Toggle between background mode (wake on power button) and foreground-only mode.
     * Starts or stops VoiceWakeService accordingly. If the user enables background
     * mode without granting RECORD_AUDIO (and POST_NOTIFICATIONS on API 33+),
     * the service would crash on Android 14+ — refuse to start it and announce
     * the missing permission via TTS instead.
     */
    fun setRunInBackground(enabled: Boolean) {
        wakePreferences.setRunInBackground(enabled)
        _runInBackground.value = enabled
        if (enabled) {
            val missing = VoiceWakeService.missingPermissions(context)
            if (missing.isEmpty()) {
                VoiceWakeService.start(context)
            } else {
                DebugLogger.log(
                    "InboxViewModel",
                    "Refusing to start wake service: missing $missing"
                )
                voiceManager.speak(
                    "Background listening needs the microphone and notification permissions. " +
                        "Please grant them in Settings, then turn this back on."
                )
            }
        } else {
            VoiceWakeService.stop(context)
        }
        DebugLogger.log("InboxViewModel", "Run in background = $enabled")
    }

    fun setVerboseLogging(enabled: Boolean) {
        wakePreferences.setVerboseLogging(enabled)
        _verboseLogging.value = enabled
        DebugLogger.verboseEnabled = enabled
        DebugLogger.log("InboxViewModel", "Verbose logging = $enabled")
    }

    fun setBatteryThreshold(threshold: Int) {
        ttsSettings.saveBatteryThreshold(threshold)
        _batteryThreshold.value = threshold
        DebugLogger.log("InboxViewModel", "Battery threshold = $threshold")
    }

    fun selectEngineFromPanel(engineName: String) {
        if (engineName == _selectedEngineName.value) {
            _settingsVoices.value = filterEnglishVoices(voiceManager.getAvailableVoices()); return
        }
        _isSwitchingEngine.value = true
        voiceManager.reinitWithEngine(engineName) {
            _settingsVoices.value     = filterEnglishVoices(voiceManager.getAvailableVoices())
            _selectedEngineName.value = engineName
            _selectedVoiceName.value  = voiceManager.getCurrentVoiceName()
            _isSwitchingEngine.value  = false
        }
    }

    fun selectVoiceFromPanel(voiceName: String) {
        voiceManager.setVoiceByName(voiceName)
        _selectedVoiceName.value = voiceName
        voiceManager.speak("Hello, how can I help you?")
    }

    fun clearVoicePreferenceFromPanel() {
        voiceManager.clearVoicePreference()
        _selectedVoiceName.value = null
        voiceManager.speak("Hello, how can I help you?")
    }

    fun selectBibleVoiceFromPanel(voiceName: String) {
        voiceManager.setBibleVoiceName(voiceName)
        _selectedBibleVoiceName.value = voiceName
        voiceManager.speakWithVoice("Hello, how can I help you?", voiceName) { }
    }

    fun clearBibleVoiceFromPanel() {
        voiceManager.setBibleVoiceName("")
        _selectedBibleVoiceName.value = null
        voiceManager.speak("Hello, how can I help you?")
    }

    fun selectBibleEngineFromPanel(engineName: String) {
        if (engineName == _bibleSelectedEngineName.value) {
            loadBibleVoicesForEngine(engineName)
            return
        }
        _isSwitchingBibleEngine.value = true
        voiceManager.setBibleEngineName(engineName)
        _bibleSelectedEngineName.value = engineName
        loadBibleVoicesForEngine(engineName)
    }

    fun clearBibleEngineFromPanel() {
        voiceManager.setBibleEngineName(null)
        _bibleSelectedEngineName.value = null
        _bibleSettingsVoices.value = emptyList()
    }

    private fun loadBibleVoicesForEngine(engineName: String) {
        voiceManager.getVoicesForEngine(engineName) { voices ->
            _bibleSettingsVoices.value = filterEnglishVoices(voices).sortedWith(
                compareBy({ it.locale.country != "US" }, { it.locale.country != "GB" },
                    { it.isNetworkConnectionRequired }, { it.name })
            )
            _isSwitchingBibleEngine.value = false
        }
    }

    fun testBibleVoice() {
        val vName = _selectedBibleVoiceName.value
        val label = if (vName != null)
            _bibleSettingsVoices.value.find { it.name == vName }
                ?.let { voiceManager.friendlyVoiceName(it) } ?: "the selected Bible voice"
        else "the default Bible voice"
        voiceManager.switchToBibleEngine {
            voiceManager.speak("Hello. This is $label. Ready to read the Bible.") {
                voiceManager.restoreMainEngine()
            }
        }
    }

    fun testMainVoice() {
        val vName = _selectedVoiceName.value
        val label = if (vName != null)
            _settingsVoices.value.find { it.name == vName }
                ?.let { voiceManager.friendlyVoiceName(it) } ?: "the selected voice"
        else "the engine default voice"
        voiceManager.speak("Hello. This is $label. VoiceGmail is ready to help you.")
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
    // Media session button handlers (BT headset, notification, lock screen)
    // ------------------------------------------------------------------

    private fun handleMediaPlay() {
        DebugLogger.log("InboxViewModel", "Media: PLAY")
        // If audio player was paused, resume audio
        if (audioPlayerVoiceFlow.mediaPlay()) return
        val emails = currentEmails()
        // If browser was paused, resume browser
        if (browserVoiceFlow.handleWakeInterrupt()) {
            browserVoiceFlow.resumeReading(viewModelScope) { exitCmd ->
                if (exitCmd is VoiceCommand.None) {
                    mediaSessionController.setStopped()
                    voiceCommandEngine.speakThenListen("Back to inbox. Say a command.") { cmd ->
                        handleCommand(cmd, emails)
                    }
                } else handleCommand(exitCmd, emails)
            }
            return
        }
        // If email was paused, resume email
        if (pausedPosition != null) {
            resumeFromPause(emails)
        } else {
            // Nothing paused — read current email
            handleCommand(VoiceCommand.Read, emails)
        }
    }

    private fun handleMediaPause() {
        DebugLogger.log("InboxViewModel", "Media: PAUSE")
        if (audioPlayerVoiceFlow.mediaPause()) return
        voiceCommandEngine.cancelListening()
        voiceManager.stopAll()
        mediaSessionController.setPaused()
    }

    private fun handleMediaNext() {
        DebugLogger.log("InboxViewModel", "Media: NEXT")
        if (audioPlayerVoiceFlow.mediaNext()) return
        val emails = currentEmails()
        // If in browser, advance to next article
        if (browserVoiceFlow.handleWakeInterrupt()) {
            browserVoiceFlow.handleWakeCommand(VoiceCommand.Next, viewModelScope) { exitCmd ->
                if (exitCmd is VoiceCommand.None) {
                    mediaSessionController.setStopped()
                    voiceCommandEngine.speakThenListen("Back to inbox. Say a command.") { cmd ->
                        handleCommand(cmd, emails)
                    }
                } else handleCommand(exitCmd, emails)
            }
            return
        }
        // Otherwise advance to next email
        handleCommand(VoiceCommand.Next, emails)
    }

    private fun handleMediaPrevious() {
        DebugLogger.log("InboxViewModel", "Media: PREVIOUS")
        if (audioPlayerVoiceFlow.mediaPrevious()) return
        val emails = currentEmails()
        handleCommand(VoiceCommand.Previous, emails)
    }

    // ------------------------------------------------------------------
    // Wake — power button interrupt
    // ------------------------------------------------------------------

    private fun handleWakeEvent() {
        DebugLogger.log("InboxViewModel", "Wake event — state=${_uiState.value::class.simpleName} pausedPosition=${pausedPosition != null}")
        flowGen++ // invalidate stale compose/browser/other callbacks
        // Pause any Bible audio that may be playing (preserves position)
        val bibleWasReading = bibleVoiceFlow.handleWakeInterrupt()
        val browserWasReading = browserVoiceFlow.handleWakeInterrupt()
        val audioWasPlaying = audioPlayerVoiceFlow.handleWakeInterrupt()
        DebugLogger.log("InboxViewModel", "handleWakeEvent: bible=$bibleWasReading browser=$browserWasReading audio=$audioWasPlaying")
        // Destroy the in-flight recognizer silently so its error/result callback
        // cannot race with this new session. TTS is handled by QUEUE_FLUSH inside
        // the following speakThenListen call.
        voiceCommandEngine.cancelListening()
        readingGen++ // invalidate any in-flight speakEmailChunk onDone callbacks
        // Restore the main TTS engine+voice in case Bible mode was active.
        // This runs even if Bible was not active (it is a no-op when no engine
        // was saved) so the rest of the wake handler always speaks in the
        // correct voice.
        voiceManager.restoreMainEngine {
            handleWakeEventAfterEngineRestore(bibleWasReading, browserWasReading, audioWasPlaying)
        }
    }

    private fun handleWakeEventAfterEngineRestore(
        bibleWasReading: Boolean,
        browserWasReading: Boolean,
        audioWasPlaying: Boolean
    ) {
        when (val state = _uiState.value) {
            is InboxUiState.Success -> {
                if (audioWasPlaying) {
                    mediaSessionController.setPaused()
                    promptAudioResumeAndListen(state.emails)
                    return
                }
                if (bibleWasReading) {
                    promptBibleResumeAndListen(state.emails)
                    return
                }
                if (browserWasReading) {
                    // Browser article reading was interrupted — open a pause
                    // listener that PRESERVES the browser's chunk bookmark on
                    // unrecognized speech, sleep, and timeout.
                    mediaSessionController.setPaused()
                    promptBrowserResumeAndListen(state.emails)
                    return
                }
                val pos = pausedPosition
                if (pos != null) {
                    // Reading was in progress when the user pressed the power button
                    mediaSessionController.setPaused()
                    val chunkNum = pos.chunkIndex + 1
                    val total    = pos.chunks.size
                    promptResumeAndListen(
                        "Reading paused at part $chunkNum of $total. " +
                            "Say 'continue' to resume, or give another command.",
                        state.emails
                    )
                } else {
                    lowBatteryAnnouncement {
                        voiceCommandEngine.speakThenListen("VoiceGmail ready. Say a command.") { cmd ->
                            handleCommand(cmd, state.emails)
                        }
                    }
                }
            }
            is InboxUiState.SignedOut ->
                voiceCommandEngine.speakThenListen(
                    "VoiceGmail. Please sign in to access your inbox. Say 'sign in' to start."
                ) { cmd -> handleCommand(cmd, emptyList()) }
            is InboxUiState.Loading ->
                voiceManager.speak("Loading your inbox. Please wait.")
            is InboxUiState.Error ->
                voiceCommandEngine.speakThenListen(
                    "VoiceGmail. ${state.message}. Say 'retry' to try again."
                ) { cmd -> handleCommand(cmd, emptyList()) }
        }
    }

    /**
     * Browser-flow equivalent of [promptResumeAndListen].  Opens a listening
     * window after the user pressed the power button while reading a search
     * result article.  Preserves [BrowserVoiceFlow]'s internal chunk bookmark
     * for SessionTimeout, GoToSleep, Cancel-during-listening, and unrecognized
     * speech — so the user can later wake the app and say "continue" to
     * resume the article from the chunk that was interrupted.
     *
     * This fixes the bug where, after browser-reading was paused by the power
     * button, saying "go to sleep" or letting the prompt time out (or being
     * misheard a single time) caused the next "continue" to fall through to
     * the INBOX dispatcher — which has no browser bookmark of its own and
     * therefore answered "No reading in progress."
     */

    private fun promptBibleResumeAndListen(emails: List<EmailItem>) {
        val bibleOnExit: (VoiceCommand) -> Unit = { exitCmd ->
            if (exitCmd is VoiceCommand.None) {
                voiceCommandEngine.speakThenListen("Back to inbox. Say a command.") { c ->
                    handleCommand(c, emails)
                }
            } else {
                handleCommand(exitCmd, emails)
            }
        }
        voiceCommandEngine.speakThenListen(
            "Bible reading was paused. Say 'continue' to resume, or another command."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.ContinueReading -> {
                    voiceManager.switchToBibleEngine {
                        bibleVoiceFlow.resumeReading(viewModelScope, bibleOnExit)
                    }
                }
                is VoiceCommand.Repeat -> {
                    voiceManager.switchToBibleEngine {
                        bibleVoiceFlow.repeatChapter(viewModelScope, bibleOnExit)
                    }
                }
                is VoiceCommand.SessionTimeout, is VoiceCommand.GoToSleep -> {
                    voiceManager.stopAll()
                    voiceManager.speak(
                        "Going to sleep. Press the power button and say 'continue' to resume."
                    )
                }
                else -> {
                    bibleVoiceFlow.stop()
                    handleCommand(cmd, emails)
                }
            }
        }
    }

    private fun promptBrowserResumeAndListen(emails: List<EmailItem>) {
        val browserOnExit: (VoiceCommand) -> Unit = { exitCmd ->
            if (exitCmd is VoiceCommand.None) {
                voiceCommandEngine.speakThenListen("Back to inbox. Say a command.") { c ->
                    handleCommand(c, emails)
                }
            } else handleCommand(exitCmd, emails)
        }
        voiceCommandEngine.speakThenListen(
            "Browser reading paused. Say 'continue' to resume, 'next' for the next article, or 'cancel'."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.SessionTimeout, is VoiceCommand.GoToSleep -> {
                    // Preserve browser bookmark — user can resume after the next wake.
                    voiceManager.stopAll()
                    voiceManager.speak(
                        "Going to sleep. Press the power button and say 'continue' " +
                            "to resume the article."
                    )
                }
                is VoiceCommand.FreeText -> {
                    // Unrecognized speech or empty recognizer result.  Do NOT
                    // fall through to the inbox dispatcher — that would lose
                    // the browser bookmark.  Re-prompt the same browser-pause
                    // listener so the user can try again.
                    DebugLogger.log(
                        "InboxViewModel",
                        "promptBrowserResumeAndListen: unrecognized FreeText='${cmd.text}' — preserving browser bookmark"
                    )
                    promptBrowserResumeAndListen(emails)
                }
                else -> {
                    // Route to browser flow first; if it doesn't handle the
                    // command, fall through to inbox (browser bookmark is
                    // intentionally abandoned for explicit-intent commands
                    // because BrowserVoiceFlow's handleWakeCommand only
                    // returns false for commands that genuinely belong to
                    // the inbox — e.g. inbox-only voice commands).
                    val handled = browserVoiceFlow.handleWakeCommand(cmd, viewModelScope, browserOnExit)
                    if (!handled) {
                        browserVoiceFlow.clearState()
                        handleCommand(cmd, emails)
                    }
                }
            }
        }
    }

    private fun promptAudioResumeAndListen(emails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen(
            "Audio paused. Say 'resume', 'continue', or 'play' to resume, or give another command."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.ContinueReading -> {
                    audioPlayerVoiceFlow.resumeAfterWake(viewModelScope) { exitCmd ->
                        handleCommand(exitCmd, emails)
                    }
                }
                is VoiceCommand.SessionTimeout, is VoiceCommand.GoToSleep -> {
                    voiceManager.stopAll()
                    voiceManager.speak(
                        "Going to sleep. Press the power button and say 'continue' to resume."
                    )
                }
                is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                    audioPlayerVoiceFlow.stop()
                    mediaSessionController.setStopped()
                    voiceCommandEngine.speakThenListen("Audio stopped. Say a command.") { c ->
                        handleCommand(c, emails)
                    }
                }
                else -> {
                    audioPlayerVoiceFlow.stop()
                    mediaSessionController.setStopped()
                    handleCommand(cmd, emails)
                }
            }
        }
    }

    /**
     * Listening window opened after a power-button wake while a reading
     * bookmark is set.  Unlike the normal command dispatcher, this loop
     * **preserves [pausedPosition]** unless the user issues an intentional
     * navigation/action command.  Unrecognized speech (empty [FreeText]),
     * recognizer timeouts, and explicit "go to sleep" / "pause" / "cancel"
     * commands all keep the bookmark alive so the user can come back later
     * (after another wake) and say "continue".
     *
     * This fixes the reliability bug where the bookmark was nuked by a single
     * misheard noise or by saying "go to sleep" after the initial pause,
     * causing the subsequent "continue" to fail with "No reading in progress".
     */
    private fun promptResumeAndListen(prompt: String, emails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen(prompt) { cmd ->
            when (cmd) {
                is VoiceCommand.ContinueReading ->
                    resumeFromPause(emails)

                is VoiceCommand.SessionTimeout, is VoiceCommand.GoToSleep -> {
                    // Preserve bookmark — user can resume after the next wake.
                    voiceManager.stopAll()
                    voiceManager.speak(
                        "Going to sleep. Press the power button and say 'continue' to resume reading."
                    )
                }

                is VoiceCommand.Cancel, is VoiceCommand.Pause ->
                    // Preserve bookmark and re-prompt without timing out into a "lost".
                    promptResumeAndListen(
                        "Paused. Say 'continue' to resume, 'go to sleep' to keep the bookmark, " +
                            "or another command.",
                        emails
                    )

                is VoiceCommand.FreeText -> {
                    // Unrecognized speech or empty result from recognizer.  Do NOT
                    // clear the bookmark — re-prompt so the user can try again.
                    DebugLogger.log(
                        "InboxViewModel",
                        "promptResumeAndListen: unrecognized FreeText='${cmd.text}' — preserving bookmark"
                    )
                    promptResumeAndListen(
                        "Sorry, I didn't catch that. Say 'continue' to resume reading, " +
                            "'go to sleep' to keep the bookmark, or another command.",
                        emails
                    )
                }

                else -> {
                    // Intentional navigation/action command (next, previous, search,
                    // refresh, delete, compose, etc.).  These handlers already clear
                    // pausedPosition themselves when appropriate, so we only clear
                    // it here as a defensive default.
                    pausedPosition = null
                    handleCommand(cmd, emails)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Auth
    // ------------------------------------------------------------------

    /** One-shot event for the UI to start the sign-in browser. */
    private val _signInRequested = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val signInRequested: SharedFlow<Intent> = _signInRequested

    fun requestSignIn() {
        val intent = getSignInIntent()
        _signInRequested.tryEmit(intent)
    }

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
        DebugLogger.log("InboxViewModel", "handleCommand: ${command::class.simpleName}" +
            if (command is VoiceCommand.FreeText) " text='${command.text}'" else "")
        when (command) {
            is VoiceCommand.Read         -> readCurrentEmail(emails)
            is VoiceCommand.Next         -> advanceEmail(+1, emails)
            is VoiceCommand.Previous     -> advanceEmail(-1, emails)
            is VoiceCommand.Repeat       -> repeatCurrentEmail(emails)
            is VoiceCommand.Refresh      -> loadInbox()
            is VoiceCommand.Compose -> startVoiceCompose(emails)
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
            is VoiceCommand.MarkAsUnread -> markCurrentAsUnread(emails)
            is VoiceCommand.Search       -> startSearch(emails)
            is VoiceCommand.ListDrafts   -> startDraftFlow(emails)
            is VoiceCommand.ListAttachments  -> listAttachments(emails)
            is VoiceCommand.ReadAttachment   -> readAttachment(command.index, emails)
            is VoiceCommand.ReadAllUnread    -> readAllUnread(emails)

            is VoiceCommand.Pause ->
                if (pausedPosition != null) {
                    voiceCommandEngine.speakThenListen(
                        "Reading paused. Say 'continue' to resume or give another command."
                    ) { c ->
                        when (c) {
                            is VoiceCommand.ContinueReading -> resumeFromPause(emails)
                            else -> handleCommand(c, emails)
                        }
                    }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Nothing is playing right now. Say a command."
                    ) { c -> handleCommand(c, emails) }
                }

            is VoiceCommand.ContinueReading ->
                if (bibleVoiceFlow.isPaused) {
                    voiceManager.switchToBibleEngine {
                        bibleVoiceFlow.resumeReading(viewModelScope) { exitCmd ->
                            voiceManager.restoreMainEngine {
                                if (exitCmd is VoiceCommand.None) {
                                    voiceCommandEngine.speakThenListen("Back to inbox. Say a command.") { c ->
                                        handleCommand(c, emails)
                                    }
                                } else {
                                    handleCommand(exitCmd, emails)
                                }
                            }
                        }
                    }
                } else if (pausedPosition != null) resumeFromPause(emails)
                else voiceCommandEngine.speakThenListen(
                    "No reading in progress. Say 'reed' to read the current email."
                ) { c -> handleCommand(c, emails) }

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
                        "search, refresh, reed unread, mark as read, mark as unread, " +
                        "pause, continue, go to sleep, bible, browser, list attachments, " +
                        "list drafts, read slower, read faster, voice settings, instructions, " +
                        "play. While composing: add more, delete last word or sentence, start over, " +
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

            is VoiceCommand.SessionTimeout -> {
                val msg = if (pausedPosition != null)
                    "Going to sleep. Press the power button and say 'continue' to resume reading."
                else
                    "Going to sleep. Press the power button to wake me."
                voiceManager.speak(msg)
            }

            is VoiceCommand.GoToSleep -> {
                // Stop all TTS and mic before speaking the sleep message,
                // so any email or article being read aloud is interrupted immediately.
                // But keep the audio player running if it's active.
                voiceManager.stopAll()
                if (!audioPlayerVoiceFlow.isActive) {
                    mediaSessionController.setStopped()
                }
                // NOTE: Do NOT clear pausedPosition here.  If a reading bookmark
                // exists, the user must be able to wake the app later and say
                // 'continue' to resume from exactly where they left off.
                val msg = if (pausedPosition != null)
                    "Going to sleep. Press the power button and say 'continue' to resume reading."
                else if (audioPlayerVoiceFlow.isActive)
                    ""
                else
                    "Going to sleep. Press the power button to wake me."
                if (msg.isNotBlank()) {
                    voiceManager.speak(msg)
                }
            }

            is VoiceCommand.SignIn -> {
                voiceManager.speak("Opening sign in page.")
                requestSignIn()
            }

            is VoiceCommand.Bible -> {
                voiceManager.switchToBibleEngine {
                    bibleVoiceFlow.start(viewModelScope) { exitCmd ->
                        voiceManager.restoreMainEngine {
                            if (exitCmd is VoiceCommand.None) {
                                voiceCommandEngine.speakThenListen("Back to inbox. Say a command.") { cmd ->
                                    handleCommand(cmd, emails)
                                }
                            } else {
                                handleCommand(exitCmd, emails)
                            }
                        }
                    }
                }
            }

            is VoiceCommand.Battery -> {
                announceBatteryLevel {
                    voiceCommandEngine.speakThenListen("Say a command.") { cmd ->
                        handleCommand(cmd, emails)
                    }
                }
            }

            is VoiceCommand.Browser -> {
                mediaSessionController.setPlaying("Browser", "Searching...")
                browserVoiceFlow.start(viewModelScope) { exitCmd ->
                    mediaSessionController.setStopped()
                    if (exitCmd is VoiceCommand.None) {
                        voiceCommandEngine.speakThenListen("Back to inbox. Say a command.") { cmd ->
                            handleCommand(cmd, emails)
                        }
                    } else {
                        handleCommand(exitCmd, emails)
                    }
                }
            }

            is VoiceCommand.PlayAudio -> {
                audioPlayerVoiceFlow.start(command.query, viewModelScope) { exitCmd ->
                    when {
                        exitCmd is VoiceCommand.GoToSleep || exitCmd is VoiceCommand.SessionTimeout -> {
                            // Keep audio playing silently. No "going to sleep" announcement,
                            // no media session teardown — the user hears their music/audio
                            // continue while the voice loop exits.
                        }
                        exitCmd is VoiceCommand.None -> {
                            mediaSessionController.setStopped()
                            voiceCommandEngine.speakThenListen("Audio player finished. Back to inbox. Say a command.") { cmd ->
                                handleCommand(cmd, emails)
                            }
                        }
                        else -> {
                            mediaSessionController.setStopped()
                            handleCommand(exitCmd, emails)
                        }
                    }
                }
            }

            is VoiceCommand.VoiceSettings -> handleVoiceSettings(emails)
            is VoiceCommand.Instructions  -> handleInstructions(emails)

            is VoiceCommand.AppLauncherSetup -> {
                _launcherApps.value = appLauncherPrefs.getApps()
                _launcherPanelVisible.value = true
                voiceManager.speak("App launcher panel opened.")
            }

            is VoiceCommand.LaunchApp -> {
                val app = appLauncherPrefs.findByVoiceCommand(command.query)
                if (app != null) {
                    try {
                        val pm = context.packageManager
                        var intent = pm.getLaunchIntentForPackage(app.packageName)
                        // Some apps (e.g. accessibility services) don't expose a
                        // standard CATEGORY_LAUNCHER activity.  Fall back to
                        // querying for ANY exported activity in the package.
                        if (intent == null) {
                            val ri = pm.queryIntentActivities(
                                Intent(Intent.ACTION_MAIN), 0
                            ).firstOrNull { it.activityInfo.packageName == app.packageName }
                            if (ri != null) {
                                intent = Intent().apply {
                                    setClassName(app.packageName, ri.activityInfo.name)
                                }
                            }
                        }
                        if (intent != null) {
                            lastLaunchedPackage = app.packageName
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            voiceCommandEngine.speakThenListen(
                                "Could not launch ${app.displayName}. The app may not be installed."
                            ) { cmd -> handleCommand(cmd, emails) }
                        }
                    } catch (e: Exception) {
                        DebugLogger.log("InboxViewModel", "Launch failed: ${e.message}")
                        voiceCommandEngine.speakThenListen(
                            "Could not launch ${app.displayName}."
                        ) { cmd -> handleCommand(cmd, emails) }
                    }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No app is configured with the voice command '${command.query}'. " +
                            "Say 'app launcher set up' to add apps."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }

            is VoiceCommand.KillApp -> {
                val pkg = lastLaunchedPackage
                if (pkg != null) {
                    lastLaunchedPackage = null
                    // Send the app to background by going home, then try to
                    // kill its background processes.
                    try {
                        val home = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(home)
                    } catch (_: Exception) {}
                    try {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        am.killBackgroundProcesses(pkg)
                    } catch (_: Exception) { /* best effort */ }
                    // Broadcast for MacroDroid to intercept (for apps that
                    // killBackgroundProcesses cannot stop, like foreground apps)
                    try {
                        val killIntent = Intent("com.example.voicegmail.KILL_APP").apply {
                            putExtra("packageName", pkg)
                            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        }
                        context.sendBroadcast(killIntent)
                    } catch (_: Exception) {}
                    // Bring self to foreground
                    val bringBack = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (bringBack != null) {
                        bringBack.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(bringBack)
                    }
                    voiceManager.speak(
                        "Returning to VoiceGmail. Say a command."
                    )
                } else {
                    voiceCommandEngine.speakThenListen(
                        "No app has been launched from VoiceGmail yet."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }

            is VoiceCommand.FreeText -> {
                val text = command.text.trim()
                if (text.isBlank()) {
                    voiceManager.speak("Going to sleep. Press the power button to wake me.")
                } else if (text.lowercase().contains("audio setting") ||
                    text.lowercase().contains("audio folder") ||
                    text.lowercase().contains("music setting")) {
                    openAudioSettings()
                    voiceManager.speak("Audio player settings opened.")
                } else if (bibleVoiceFlow.isBibleReference(text)) {
                    // Direct Bible reference from inbox (e.g., "John 3:16")
                    voiceManager.switchToBibleEngine {
                        bibleVoiceFlow.startWithReference(text, viewModelScope) { exitCmd ->
                            voiceManager.restoreMainEngine {
                                handleCommand(exitCmd, emails)
                            }
                        }
                    }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Sorry, I didn't understand: FreeText='$text'. Say 'help' to hear available commands."
                    ) { cmd -> handleCommand(cmd, emails) }
                }
            }

            else -> {
                val detail = command::class.simpleName ?: "unknown"
                DebugLogger.log("InboxViewModel", "UNHANDLED command: $detail")
                voiceCommandEngine.speakThenListen(
                    "Sorry, I didn't understand: $detail. Say 'help' to hear available commands."
                ) { cmd -> handleCommand(cmd, emails) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Reading — sentence-by-sentence with pause/resume support
    // ------------------------------------------------------------------

    private fun readCurrentEmail(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen("No more emails. Say 'refresh' or 'compose'.") { cmd ->
                handleCommand(cmd, emails)
            }; return
        }
        pausedPosition = null // clear any stale bookmark before starting fresh
        mediaSessionController.setPlaying("Email: ${email.subject}", "From ${email.from}")
        val unreadPrefix = if (email.isUnread) "Unread. " else ""
        val bodyForSpeech = email.body.forSpeech()
        val fullText = "${unreadPrefix}Email ${_currentEmailIndex.value + 1} of ${emails.size}. " +
            "From ${email.from}. Subject: ${email.subject}. $bodyForSpeech"
        readEmailBySentences(email, emails, fullText)
    }

    private fun repeatCurrentEmail(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email != null) {
            pausedPosition = null
            val bodyForSpeech = email.body.forSpeech()
            val fullText = "Repeating email ${_currentEmailIndex.value + 1}. " +
                "From ${email.from}. Subject: ${email.subject}. $bodyForSpeech"
            readEmailBySentences(email, emails, fullText)
        } else
            voiceCommandEngine.speakThenListen("No email to repeat. Say 'reed' first.") { cmd ->
                handleCommand(cmd, emails)
            }
    }

    fun readEmailAloud(email: EmailItem) {
        val emails = currentEmails()
        val idx = emails.indexOf(email).takeIf { it >= 0 } ?: _currentEmailIndex.value
        _currentEmailIndex.value = idx
        pausedPosition = null
        val bodyForSpeech = email.body.forSpeech()
        val fullText = "From ${email.from}. Subject: ${email.subject}. $bodyForSpeech"
        readEmailBySentences(email, emails, fullText)
    }

    /**
     * Split [fullText] into chunks and begin chunk-by-chunk reading from
     * [startChunkIndex] (0 = beginning).
     */
    private fun readEmailBySentences(
        email: EmailItem,
        emails: List<EmailItem>,
        fullText: String,
        startChunkIndex: Int = 0
    ) {
        val chunks = makeChunks(splitIntoSentences(fullText))
        if (chunks.isEmpty()) {
            voiceCommandEngine.speakThenListen(
                "This email appears to be empty. Say a command."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        readingGen++ // begin a new reading session
        readNextChunk(email, emails, chunks, startChunkIndex, readingGen)
    }

    /**
     * Speak chunk [index] at email reading rate then immediately advance to the
     * next chunk — no mic session is opened between chunks, so there is no
     * recognition beep or initialisation pause.
     *
     * Interrupt path: the power button fires [handleWakeEvent], which increments
     * [readingGen] and calls [cancelListening].  The [gen] guard below discards
     * any stale TTS onDone callback that fires after the utterance was flushed.
     *
     * [pausedPosition] is updated *before* each chunk so a power-button wake
     * always finds the correct position.
     */
    private fun readNextChunk(
        email: EmailItem,
        emails: List<EmailItem>,
        chunks: List<String>,
        index: Int,
        gen: Int = readingGen
    ) {
        if (gen != readingGen) return // stale callback — a newer session is now active

        if (index >= chunks.size) {
            // Reached the end — clear the bookmark
            pausedPosition = null
            mediaSessionController.setStopped()
            val attachHint = when (email.attachments.size) {
                0    -> ""
                1    -> " This email has 1 attachment: ${email.attachments[0].filename}."
                else -> " This email has ${email.attachments.size} attachments. Say 'list attachments' to hear them."
            }
            val readAttachHint = if (email.attachments.isNotEmpty()) "'read attachment one', " else ""
            voiceCommandEngine.speakThenListen(
                "End of email.$attachHint Say 'reply', 'forward', 'delete', " +
                    "'mark as unread', ${readAttachHint}'next', or 'repeat'."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }

        // Update bookmark BEFORE speaking — power-button wake reads this position
        pausedPosition = ReadingPosition(
            emailId    = email.id,
            emailIndex = _currentEmailIndex.value,
            chunks     = chunks,
            chunkIndex = index
        )

        voiceCommandEngine.speakEmailChunk(chunks[index]) {
            readNextChunk(email, emails, chunks, index + 1, gen)
        }
    }

    /**
     * Resume reading from [pausedPosition].  If the email is no longer in the
     * list, falls back to the saved index position.
     *
     * "Stop & Restart from current sentence" fallback: we don't rely on the
     * underlying TTS engine's resume() API (which is unreliable across Android
     * engines and across web browsers).  Instead, the resume model itself is
     * restart-from-chunk — [readNextChunk] calls [VoiceManager.speakEmailChunk]
     * which uses [TextToSpeech.QUEUE_FLUSH] to evict any leftover utterance
     * from the pre-pause session.  Combined with the [readingGen] bump below
     * (which invalidates stale onDone callbacks), this guarantees a clean
     * resume even if the engine was stuck.
     */
    private fun resumeFromPause(emails: List<EmailItem>) {
        val pos = pausedPosition
        if (pos == null) {
            voiceCommandEngine.speakThenListen(
                "No reading in progress. Say 'reed' to start."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        val email = emails.find { it.id == pos.emailId }
            ?: emails.getOrNull(pos.emailIndex)
        if (email == null) {
            pausedPosition = null
            voiceCommandEngine.speakThenListen(
                "The paused email is no longer available. Say 'reed' to start from the current email."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        _currentEmailIndex.value = pos.emailIndex
        readingGen++ // new session for resume — clears any stale flushed-chunk callbacks
        DebugLogger.log(
            "InboxViewModel",
            "resumeFromPause: emailIndex=${pos.emailIndex} chunkIndex=${pos.chunkIndex}/${pos.chunks.size} gen=$readingGen"
        )
        // "Stop & restart from current sentence" fallback: the resume model itself
        // is restart-from-chunk (we don't rely on a TTS engine's resume() — which
        // is unreliable across Android TTS engines / browsers).  speakEmailChunk
        // uses TextToSpeech.QUEUE_FLUSH to evict any leftover utterance from the
        // pre-pause session before speaking the resume chunk.  Combined with the
        // readingGen guard above, this guarantees a clean resume even if the
        // underlying engine got stuck.
        readNextChunk(email, emails, pos.chunks, pos.chunkIndex, readingGen)
    }

    // ------------------------------------------------------------------
    // Text chunking utilities
    // ------------------------------------------------------------------

    /**
     * Split [text] on sentence-ending punctuation.
     * Falls back to the whole text as a single entry if no boundary is found.
     */
    private fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return Regex("(?<=[.!?])\\s+")
            .split(text.trim())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(text.trim()) }
    }

    /**
     * Group [sentences] into chunks of at most [maxChars] characters so TTS
     * speaks for ~15–20 s per chunk before the brief listening window opens.
     */
    private fun makeChunks(sentences: List<String>, maxChars: Int = 240): List<String> {
        if (sentences.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        for (s in sentences) {
            if (buf.isNotEmpty() && buf.length + 1 + s.length > maxChars) {
                chunks.add(buf.toString())
                buf.clear()
            }
            if (buf.isNotEmpty()) buf.append(" ")
            buf.append(s)
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString())
        return chunks
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private fun advanceEmail(delta: Int, emails: List<EmailItem>) {
        val newIdx = _currentEmailIndex.value + delta
        val email  = emails.getOrNull(newIdx)
        pausedPosition = null // clear any reading bookmark when navigating away
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
                val raw  = attachmentReader.readAttachment(email.id, attachment)
                val text = raw.forSpeech()
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
    // Mark as read / unread
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

    private fun markCurrentAsUnread(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null || email.isUnread) {
            voiceCommandEngine.speakThenListen(
                if (email == null) "No email selected." else "This email is already marked as unread."
            ) { cmd -> handleCommand(cmd, emails) }; return
        }
        viewModelScope.launch {
            try {
                gmailRepository.markAsUnread(email.id)
                val updated = emails.map { if (it.id == email.id) it.copy(isUnread = true) else it }
                _uiState.value = InboxUiState.Success(updated)
                voiceCommandEngine.speakThenListen("Marked as unread.") { cmd -> handleCommand(cmd, updated) }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen(
                    "Could not mark as unread. ${e.message ?: "Unknown error."}"
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
                "Compose. Who would you like to send to? Say the name or email address."
            ) { cmd -> handleComposeTo(cmd, emails) }
        }
    }

    private fun handleComposeTo(cmd: VoiceCommand, emails: List<EmailItem>) {
        val rawSpoken = rawText(cmd)
        DebugLogger.log("InboxViewModel", "handleComposeTo: cmd=${cmd::class.simpleName} rawSpoken='$rawSpoken'")
        when {
            cmd is VoiceCommand.Cancel ->
                voiceCommandEngine.speakThenListen("Compose cancelled.") { c -> handleCommand(c, emails) }
            rawSpoken.isNotBlank() -> composeResolveRecipient(rawSpoken, cmd, emails)
            else ->
                voiceCommandEngine.speakThenListen(
                    "I didn't catch the email address. Please try again, or say 'cancel'."
                ) { c -> handleComposeTo(c, emails) }
        }
    }

    private fun composeResolveRecipient(rawSpoken: String, cmd: VoiceCommand, emails: List<EmailItem>) {
        viewModelScope.launch {
            val contacts = contactsRepository.combinedContacts(emails)
            val candidates = voiceCommandEngine.lastCandidates.ifEmpty { listOf(rawSpoken) }
            DebugLogger.log("InboxViewModel", "composeResolveRecipient: rawSpoken='$rawSpoken' candidates=$candidates contactsCount=${contacts.size}")
            contacts.take(10).forEach { c ->
                DebugLogger.verbose("InboxViewModel", "  contact: name='${c.displayName}' email=${c.email}")
            }
            val matches = ContactMatcher.rankAcrossCandidates(candidates, contacts)
            DebugLogger.log("InboxViewModel", "rankAcrossCandidates: matches=${matches.size}")
            matches.forEach { m ->
                DebugLogger.log("InboxViewModel", "  match: name='${m.contact.displayName}' email=${m.contact.email} score=${m.score}")
            }
            when {
                matches.size == 1 && matches[0].score >= 70 -> {
                    val pick = matches[0]
                    composeConfirmRecipient(pick.contact.email, pick.contact.displayName, cmd, emails)
                }
                matches.size > 1 -> composeEnumerateMatches(matches, rawSpoken, cmd, emails)
                else -> {
                    val parsed = ContactMatcher.parseEmailCandidates(candidates)
                    DebugLogger.log("InboxViewModel", "parseEmailCandidates: parsed=${parsed.size} -> $parsed")
                    when {
                        parsed.size == 1 -> composeGoToSubject(parsed[0], cmd, emails)
                        parsed.size > 1 -> composeEnumerateParsed(parsed, rawSpoken, cmd, emails)
                        else -> composerSpellEmail(rawSpoken, cmd, emails, notFound = true)
                    }
                }
            }
        }
    }

    private fun composeConfirmRecipient(email: String, displayName: String?, cmd: VoiceCommand, emails: List<EmailItem>) {
        val name = displayName ?: email.substringBefore('@')
        voiceCommandEngine.speakThenListen(
            "Send to $name? Say 'yes' to confirm, or say 'spell' to type an email address."
        ) { c ->
            val text = rawText(c)
            when {
                c is VoiceCommand.Confirm || text.contains("yes") -> composeGoToSubject(email, cmd, emails)
                text.contains("spell") -> composerSpellEmail("", cmd, emails)
                else -> handleComposeTo(c, emails)
            }
        }
    }

    private fun composeEnumerateMatches(matches: List<ContactMatcher.ScoredContact>, raw: String, cmd: VoiceCommand, emails: List<EmailItem>) {
        val choices = matches.take(5)
        val listing = choices.mapIndexed { i, sc -> "${i + 1}: ${sc.contact.displayName}" }.joinToString(". ")
        voiceCommandEngine.speakThenListen(
            "Multiple contacts found. $listing. Say the number, or say 'spell' to type an email address."
        ) { c ->
            val text = rawText(c).lowercase()
            val pickIdx = parseOrdinal(text, choices.size)
            when {
                text.contains("spell") -> composerSpellEmail(raw, cmd, emails)
                pickIdx != null -> {
                    val pick = choices[pickIdx]
                    composeConfirmRecipient(pick.contact.email, pick.contact.displayName, cmd, emails)
                }
                else -> composeEnumerateMatches(matches, raw, cmd, emails)
            }
        }
    }

    private fun composeEnumerateParsed(parsed: List<String>, raw: String, cmd: VoiceCommand, emails: List<EmailItem>) {
        val choices = parsed.take(5)
        val listing = choices.mapIndexed { i, e -> "${i + 1}: ${e.substringBefore('@')}" }.joinToString(". ")
        voiceCommandEngine.speakThenListen(
            "I found $listing. Say the number, or say 'spell' to type it letter by letter."
        ) { c ->
            val text = rawText(c).lowercase()
            val pickIdx = parseOrdinal(text, choices.size)
            when {
                text.contains("spell") -> composerSpellEmail(raw, cmd, emails)
                pickIdx != null -> composeGoToSubject(choices[pickIdx], cmd, emails)
                else -> composeEnumerateParsed(parsed, raw, cmd, emails)
            }
        }
    }

    /**
     * Accepts a raw spoken response and returns a 0-based index if it matches
     * a digit ("1", "2"), number word ("one", "two"), or ordinal ("first", "second").
     */
    private fun parseOrdinal(text: String, max: Int): Int? {
        // Number words and ordinals
        for ((idx, words) in ORDINAL_WORDS.withIndex()) {
            if (idx >= max) break
            for (w in words) if (text.contains(w)) return idx
        }
        // Bare digits
        text.toIntOrNull()?.let {
            val n = it - 1
            if (n in 0 until max) return n
        }
        return null
    }

    private fun composeGoToSubject(email: String, cmd: VoiceCommand, emails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen("Sending to $email. What is the subject?") { c ->
            handleComposeSubject(email, c, emails)
        }
    }

    private fun composerSpellEmail(raw: String, cmd: VoiceCommand, emails: List<EmailItem>, notFound: Boolean = false) {
        val prompt = if (notFound)
            "I couldn't find a contact named ${raw.forSpeech()}. Please spell the email address letter by letter, for example 'D C O N N 4 at Gmail dot com'."
        else
            "Please spell the email address letter by letter, for example 'D C O N N 4 at Gmail dot com'."
        voiceCommandEngine.speakThenListen(prompt) { c ->
            val text = rawText(c)
            if (text.isBlank()) {
                composerSpellEmail(raw, cmd, emails)
                return@speakThenListen
            }
            val parsed = ContactMatcher.parseDictatedEmail(text)
                ?: ContactMatcher.tryParseSpelledOut(text)
                ?: text
            composeGoToSubject(parsed, cmd, emails)
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
                    "To: $to. Subject: $subject. Message: ${body.forSpeech()}. " +
                        "Say 'send', 'add more', 'delete last word or sentence', " +
                        "'start over', 'save draft', or 'cancel'."
                ) { c -> handleComposeFinal(to, subject, body, c, emails) }

            // ContinueReading treated as AddMore in compose context
            is VoiceCommand.AddMore, is VoiceCommand.ContinueReading ->
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
        val gen = flowGen
        viewModelScope.launch {
            voiceManager.speak("Sending.")
            try {
                DebugLogger.log("InboxViewModel", "Sending email — to=$to subject=$subject body=${body.take(80)}")
                gmailRepository.sendEmail(to, subject, body)
                if (gen != flowGen) return@launch
                voiceManager.speak("Sent successfully.")
                val prompt = composePrompt(emails)
                voiceCommandEngine.speakThenListen(prompt) { c -> handleCommand(c, emails) }
            } catch (e: Exception) {
                if (gen != flowGen) return@launch
                voiceManager.speak("Send failed. ${e.message ?: "Unknown error."}")
                voiceCommandEngine.speakThenListen("Say 'try again' or 'cancel'.") { c ->
                    when (c) {
                        is VoiceCommand.TryAgain -> voiceComposeSend(to, subject, body, emails)
                        else -> handleCommand(c, emails)
                    }
                }
            }
        }
    }

    private fun composePrompt(emails: List<EmailItem>): String {
        val unread = emails.count { it.isUnread }
        return when {
            emails.isEmpty() -> "Your inbox is empty. Say 'compose' or 'refresh'."
            unread > 0 -> {
                val u = if (unread == 1) "1 unread email" else "$unread unread emails"
                val t = if (emails.size == 1) "1 email" else "${emails.size} emails"
                "Back to inbox. You have $u out of $t. Say a command."
            }
            else -> {
                val t = if (emails.size == 1) "1 email" else "${emails.size} emails"
                "Back to inbox. You have $t. Say a command."
            }
        }
    }

    // ------------------------------------------------------------------
    // Compose body editing helpers
    // ------------------------------------------------------------------

    private fun deleteLastWord(body: String): String {
        val trimmed = body.trimEnd()
        val lastSpace = trimmed.lastIndexOf(' ')
        return if (lastSpace < 0) "" else trimmed.substring(0, lastSpace)
    }

    private fun deleteLastSentence(body: String): String {
        val trimmed = body.trimEnd()
        val lastEnd = maxOf(
            trimmed.lastIndexOf('.'),
            trimmed.lastIndexOf('!'),
            trimmed.lastIndexOf('?')
        )
        if (lastEnd < 0) return ""
        val prevEnd = maxOf(
            trimmed.lastIndexOf('.', lastEnd - 1),
            trimmed.lastIndexOf('!', lastEnd - 1),
            trimmed.lastIndexOf('?', lastEnd - 1)
        )
        return if (prevEnd < 0) "" else trimmed.substring(0, prevEnd + 1).trimEnd()
    }

    private fun deleteLastParagraph(body: String): String {
        val trimmed = body.trimEnd()
        val lastNl = trimmed.lastIndexOf('\n')
        return if (lastNl < 0) "" else trimmed.substring(0, lastNl).trimEnd()
    }

    // ------------------------------------------------------------------
    // Drafts
    // ------------------------------------------------------------------

    private fun startDraftFlow(emails: List<EmailItem>) {
        viewModelScope.launch {
            voiceManager.speak("Loading drafts. Please wait.")
            try {
                val drafts = gmailRepository.listDrafts()
                if (drafts.isEmpty()) {
                    voiceCommandEngine.speakThenListen(
                        "You have no saved drafts. Say a command."
                    ) { cmd -> handleCommand(cmd, emails) }
                    return@launch
                }
                val items = drafts.mapIndexed { i, d ->
                    "${i + 1}: To ${d.to}, subject ${d.subject}"
                }.joinToString(". ")
                voiceCommandEngine.speakThenListen(
                    "You have ${drafts.size} draft${if (drafts.size == 1) "" else "s"}. $items. " +
                        "Say 'edit draft 1' to reopen, or 'delete draft 1' to discard."
                ) { cmd -> handleDraftCommand(cmd, drafts, emails) }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen(
                    "Could not load drafts. ${e.message ?: "Unknown error."}. Say a command."
                ) { cmd -> handleCommand(cmd, emails) }
            }
        }
    }

    private fun handleDraftCommand(
        cmd: VoiceCommand,
        drafts: List<DraftItem>,
        emails: List<EmailItem>
    ) {
        when (cmd) {
            is VoiceCommand.EditDraft -> {
                val draft = drafts.getOrNull(cmd.index - 1)
                if (draft == null) {
                    voiceCommandEngine.speakThenListen(
                        "No draft number ${cmd.index}. Say 'edit draft 1' or a command."
                    ) { c -> handleCommand(c, emails) }
                } else {
                    voiceCommandEngine.speakThenListen(
                        "Editing draft to ${draft.to}, subject ${draft.subject}. " +
                            "Current body: ${draft.body.forSpeech().take(200)}. " +
                            "Say 'send', 'add more', 'delete last word or sentence', " +
                            "'start over', 'save draft', or 'cancel'."
                    ) { c -> handleDraftEditing(draft, c, emails) }
                }
            }
            is VoiceCommand.DeleteDraft -> {
                val draft = drafts.getOrNull(cmd.index - 1)
                if (draft == null) {
                    voiceCommandEngine.speakThenListen(
                        "No draft number ${cmd.index}."
                    ) { c -> handleCommand(c, emails) }
                } else {
                    viewModelScope.launch {
                        try {
                            gmailRepository.deleteDraft(draft.id)
                            voiceCommandEngine.speakThenListen(
                                "Draft deleted. Say a command."
                            ) { c -> handleCommand(c, emails) }
                        } catch (e: Exception) {
                            voiceCommandEngine.speakThenListen(
                                "Could not delete draft. ${e.message ?: "Unknown error."}."
                            ) { c -> handleCommand(c, emails) }
                        }
                    }
                }
            }
            else -> handleCommand(cmd, emails)
        }
    }

    private fun handleDraftEditing(
        draft: DraftItem,
        cmd: VoiceCommand,
        emails: List<EmailItem>
    ) {
        when (cmd) {
            is VoiceCommand.Send -> viewModelScope.launch {
                try {
                    gmailRepository.sendDraft(draft.id)
                    voiceCommandEngine.speakThenListen("Draft sent.") { c -> handleCommand(c, emails) }
                } catch (e: Exception) {
                    voiceCommandEngine.speakThenListen(
                        "Could not send draft. ${e.message ?: "Unknown error."}."
                    ) { c -> handleCommand(c, emails) }
                }
            }
            is VoiceCommand.AddMore, is VoiceCommand.ContinueReading ->
                voiceCommandEngine.speakThenListen("Say what you'd like to add.") { c ->
                    val extra = rawText(c)
                    if (extra.isNotBlank()) {
                        val newBody = "${draft.body} $extra"
                        saveDraftEdit(draft, newBody, emails)
                    } else {
                        voiceCommandEngine.speakThenListen(
                            "Nothing added. Say 'send', 'add more', or 'cancel'."
                        ) { cx -> handleDraftEditing(draft, cx, emails) }
                    }
                }
            is VoiceCommand.StartOver ->
                voiceCommandEngine.speakThenListen("Body cleared. Say your new message.") { c ->
                    val newBody = rawText(c)
                    if (newBody.isNotBlank()) saveDraftEdit(draft, newBody, emails)
                    else handleDraftEditing(draft, c, emails)
                }
            is VoiceCommand.DeleteLast -> {
                val newBody = when (cmd.unit) {
                    "paragraph" -> deleteLastParagraph(draft.body)
                    "sentence"  -> deleteLastSentence(draft.body)
                    else        -> deleteLastWord(draft.body)
                }
                if (newBody == draft.body) {
                    voiceCommandEngine.speakThenListen(
                        "Nothing to remove."
                    ) { c -> handleDraftEditing(draft, c, emails) }
                } else {
                    saveDraftEdit(draft, newBody, emails)
                }
            }
            is VoiceCommand.ReadBack ->
                voiceCommandEngine.speakThenListen(
                    "To: ${draft.to}. Subject: ${draft.subject}. Message: ${draft.body.forSpeech()}."
                ) { c -> handleDraftEditing(draft, c, emails) }
            is VoiceCommand.Cancel, is VoiceCommand.GoBack ->
                voiceCommandEngine.speakThenListen("Draft editing cancelled.") { c -> handleCommand(c, emails) }
            else ->
                voiceCommandEngine.speakThenListen(
                    "Say 'send', 'add more', 'delete last word', 'start over', 'read back', or 'cancel'."
                ) { c -> handleDraftEditing(draft, c, emails) }
        }
    }

    private fun saveDraftEdit(draft: DraftItem, newBody: String, emails: List<EmailItem>) {
        viewModelScope.launch {
            try {
                val updated = gmailRepository.updateDraft(draft.id, draft.to, draft.subject, newBody)
                voiceCommandEngine.speakThenListen(
                    "Draft updated. Say 'send', 'add more', 'delete last word', " +
                        "'start over', 'read back', or 'cancel'."
                ) { c -> handleDraftEditing(updated, c, emails) }
            } catch (e: Exception) {
                voiceCommandEngine.speakThenListen(
                    "Could not save changes. ${e.message ?: "Unknown error."}."
                ) { c -> handleDraftEditing(draft, c, emails) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Forward
    // ------------------------------------------------------------------

    private fun startVoiceForward(emails: List<EmailItem>) {
        val email = emails.getOrNull(_currentEmailIndex.value)
        if (email == null) {
            voiceCommandEngine.speakThenListen(
                "No email selected to forward. Say 'reed' first."
            ) { cmd -> handleCommand(cmd, emails) }; return
        }
        voiceCommandEngine.speakThenListen(
            "Forward email from ${email.from}, subject ${email.subject}. " +
                "Who would you like to forward it to? Say the email address."
        ) { cmd ->
            val to = rawText(cmd)
            if (to.isBlank()) {
                voiceCommandEngine.speakThenListen(
                    "I didn't catch the address. Say it again or 'cancel'."
                ) { c -> handleCommand(c, emails) }
            } else {
                voiceCommandEngine.speakThenListen("Forwarding to $to. Say your note, or 'send' to forward as-is.") { c ->
                    val note = rawText(c)
                    val body = if (note.isBlank()) "---------- Forwarded message ----------\n${email.body}"
                               else "$note\n\n---------- Forwarded message ----------\n${email.body}"
                    voiceComposeSend(to, "Fwd: ${email.subject}", body, emails)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    private fun startSearch(emails: List<EmailItem>) {
        voiceCommandEngine.speakThenListen(
            "Search. What would you like to find? Say something like 'emails from David' or 'subject meeting'."
        ) { cmd ->
            val raw = rawText(cmd)
            // Hard cancel
            if (cmd is VoiceCommand.Cancel || cmd is VoiceCommand.GoBack) {
                voiceCommandEngine.speakThenListen("Search cancelled.") { c -> handleCommand(c, emails) }
                return@speakThenListen
            }
            // Empty / silence → cancel
            if (raw.isBlank()) {
                voiceCommandEngine.speakThenListen("Search cancelled.") { c -> handleCommand(c, emails) }
                return@speakThenListen
            }
            // The recogniser sometimes turns short app commands (e.g. "read all"
            // → "redial", "next", "cancel", "pause") into single-word phrases
            // that are nonsensical as Gmail queries.  If the user clearly meant
            // a control command, route to that instead of running a junk search.
            if (cmd !is VoiceCommand.FreeText) {
                DebugLogger.log(
                    "InboxViewModel",
                    "startSearch: heard control command ${cmd::class.simpleName} instead of a query — dispatching it"
                )
                handleCommand(cmd, emails)
                return@speakThenListen
            }
            val query = queryParser.parse(raw)
            viewModelScope.launch {
                voiceManager.speak("Searching for $raw. Please wait.")
                try {
                    val results = gmailRepository.searchEmails(query)
                    _uiState.value = InboxUiState.Success(results)
                    _currentEmailIndex.value = 0
                    if (results.isEmpty()) {
                        voiceCommandEngine.speakThenListen(
                            "No results for $raw. Say 'search' to try again or 'refresh' to reload your inbox."
                        ) { c -> handleCommand(c, results) }
                    } else {
                        val first = results[0]
                        voiceCommandEngine.speakThenListen(
                            "${results.size} result${if (results.size == 1) "" else "s"} for $raw. " +
                                "First: From ${first.from}. ${first.subject}. " +
                                "Say 'reed' to hear it, 'next' for more, " +
                                "'read all' to hear every result, " +
                                "or 'refresh' to go back to your inbox."
                        ) { c -> handleCommand(c, results) }
                    }
                } catch (e: Exception) {
                    voiceCommandEngine.speakThenListen(
                        "Search failed. ${e.message ?: "Unknown error."}. Say 'search' to try again."
                    ) { c -> handleCommand(c, emails) }
                }
            }
        }
    }

    private fun filterEnglishVoices(voices: List<Voice>): List<Voice> {
        val english = voices.filter { voice ->
            voice.locale.language == "en" && (voice.locale.country == "US" ||
                voice.locale.country == "GB" || voice.locale.country.isEmpty())
        }
        DebugLogger.log("InboxViewModel", "filterEnglishVoices: " +
            "${english.size} of ${voices.size} (countries: ${
                voices.filter { it.locale.language == "en" }.map { "'${it.locale.country}'" }.distinct()
            })")
        return english
    }

    // ------------------------------------------------------------------
    // Voice settings (browse TTS voices)
    // ------------------------------------------------------------------

    private fun handleVoiceSettings(emails: List<EmailItem>) {
        val voices = filterEnglishVoices(voiceManager.getAvailableVoices())
        if (voices.isEmpty()) {
            voiceCommandEngine.speakThenListen(
                "No English (US or UK) voices found. Install Google Text-to-Speech " +
                    "from the Play Store, or use the settings icon to switch engine."
            ) { cmd -> handleCommand(cmd, emails) }
            return
        }
        val originalVoiceName = voiceManager.getCurrentVoiceName()
        val count = voices.size
        voiceCommandEngine.speakThenListen(
            "Voice chooser. $count English voice${if (count == 1) "" else "s"} available. " +
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
        val sample = "Hello. I am voice ${index + 1} of $total. What do you think?"
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

    fun getEmailLogIntent(): Intent? {
        val file = DebugLogger.getLogFile()?.takeIf { it.exists() } ?: return null
        val uri  = FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.debug.fileprovider", file
        )
        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_SUBJECT, "VoiceGmail debug log - $dateTime")
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
        val ORDINAL_WORDS = listOf(
            listOf("first",  "one",   "number one",   "the first"),
            listOf("second", "two",   "number two",   "the second"),
            listOf("third",  "three", "number three", "the third"),
            listOf("fourth", "four",  "number four",  "the fourth"),
            listOf("fifth",  "five",  "number five",  "the fifth")
        )

        val INSTRUCTION_SECTIONS = listOf(
            "Instructions. Section 1 of 9: Overview. " +
                "VoiceGmail is completely hands-free. After every spoken message, the microphone opens automatically. " +
                "Wake the app at any time by pressing the power button. " +
                "Navigate sections with: next, previous, repeat, cancel. Say 'next' to continue.",
            "Section 2 of 9: Reading your mail. " +
                "Say 'reed' to hear the current email. It is read in short sections. " +
                "Say 'pause' at any time to pause, or press the power button mid-read. " +
                "Say 'continue' to resume where you left off. " +
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
                "Say 'mark as unread' to restore the unread badge. " +
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
                "Say 'voice settings' to browse available English voices. " +
                "Say 'read slower' or 'slow down' to decrease email reading speed by 10 percent. " +
                "Say 'read faster' or 'speed up' to increase it. " +
                "After adjusting, say 'repeat' to hear the current email at the new speed. " +
                "Long web links in email bodies are automatically shortened to 'weblink at domain dot com'. " +
                "Press the power button to wake the app from sleep. " +
                "That is the end of the instructions. " +
                "Say 'previous', 'repeat', or any command to return to your inbox."
        )
    }

    // ── Battery ────────────────────────────────────────────────────────────

    private fun getBatteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (scale > 0) (level * 100 / scale) else -1
    }

    private fun announceBatteryLevel(onDone: () -> Unit) {
        val pct = getBatteryLevel()
        if (pct >= 0) {
            voiceManager.speak("Battery at $pct percent.") {
                onDone()
            }
        } else {
            voiceManager.speak("Unable to read battery level.") {
                onDone()
            }
        }
    }

    private fun lowBatteryAnnouncement(onDone: () -> Unit) {
        val pct = getBatteryLevel()
        val threshold = ttsSettings.getBatteryThreshold()
        if (pct in 0..threshold) {
            voiceManager.speak("Battery $pct percent.") {
                onDone()
            }
        } else {
            onDone()
        }
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
