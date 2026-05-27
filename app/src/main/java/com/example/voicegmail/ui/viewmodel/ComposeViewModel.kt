package com.example.voicegmail.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.gmail.GmailRepository
import com.example.voicegmail.gmail.OutgoingAttachment
import com.example.voicegmail.voice.ForwardDraft
import com.example.voicegmail.voice.VoiceCommand
import com.example.voicegmail.voice.VoiceCommandEngine
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot events emitted from the ViewModel to the UI layer. */
sealed class ComposeEvent {
    /** Request the UI to open the system file picker. */
    object OpenFilePicker : ComposeEvent()
}

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val gmailRepository: GmailRepository,
    private val voiceManager: VoiceManager,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val forwardDraft: ForwardDraft
) : ViewModel() {

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState

    val isListening get() = voiceManager.isListening

    /** Current field values driven by voice or manual text entry. */
    private val _to = MutableStateFlow("")
    val to: StateFlow<String> = _to

    private val _subject = MutableStateFlow("")
    val subject: StateFlow<String> = _subject

    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body

    /** Files staged for the outgoing message. Populated by [attachmentSelected]. */
    private val _attachments = MutableStateFlow<List<OutgoingAttachment>>(emptyList())
    val attachments: StateFlow<List<OutgoingAttachment>> = _attachments

    /** Signals the UI to navigate back (after successful send or voice cancel). */
    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack

    /** One-shot events for the UI layer (e.g. open file picker). */
    private val _composeEvent = MutableSharedFlow<ComposeEvent>(extraBufferCapacity = 1)
    val composeEvent: SharedFlow<ComposeEvent> = _composeEvent

    // ------------------------------------------------------------------
    // Guided voice flow
    // ------------------------------------------------------------------

    /** True once [initForwardFromDraft] has loaded the forward fields. */
    private var isForwardMode = false

    /**
     * Pre-fills the recipient for a reply and marks it so the guided flow
     * skips asking "Who would you like to send to?". Call this before
     * [startGuidedVoiceFlow].
     */
    fun initReplyTo(address: String) {
        _to.value = address
    }

    /**
     * Reads the pending [ForwardDraft] (if any) and pre-fills all three
     * fields (to, subject, body). Idempotent — safe to call even when no
     * draft is waiting. Call this before [startGuidedVoiceFlow].
     */
    fun initForwardFromDraft() {
        val draft = forwardDraft.consume() ?: return
        _to.value = draft.to
        _subject.value = draft.subject
        _body.value = draft.body
        isForwardMode = true
    }

    /**
     * Starts the hands-free guided compose flow.
     *
     * - Forward mode: all fields are pre-filled; asks for an optional extra
     *   message to prepend, then offers "send" or "cancel".
     * - Reply mode: [_to] is pre-filled; skips the "To" step.
     * - Fresh compose: collects To → Subject → Body → Confirm.
     */
    fun startGuidedVoiceFlow() {
        if (isForwardMode) {
            askForForwardExtra()
            return
        }
        val existingTo = _to.value
        if (existingTo.isNotBlank()) {
            voiceCommandEngine.speakThenListen(
                "Replying to $existingTo. What is the subject of your reply?"
            ) { cmd -> handleSubjectResult(cmd) }
        } else {
            askForTo()
        }
    }

    // ------------------------------------------------------------------
    // Forward-mode helpers
    // ------------------------------------------------------------------

    private fun askForForwardExtra() {
        voiceCommandEngine.speakThenListen(
            "Forwarding to ${_to.value}. Subject: ${_subject.value}. " +
                "Say your additional message to add before the forwarded email, " +
                "'attach file' to add a file, or 'send' to forward it as is."
        ) { cmd -> handleForwardExtraResult(cmd) }
    }

    private fun handleForwardExtraResult(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.Send -> confirmAndSend()
            is VoiceCommand.AttachFile -> requestFilePicker()
            is VoiceCommand.Cancel -> cancel()
            is VoiceCommand.FreeText -> {
                _body.value = "${cmd.text}\n${_body.value}"
                confirmAndSend()
            }
            else -> {
                val raw = voiceManager.recognizedText.value ?: ""
                if (raw.isNotBlank()) {
                    _body.value = "$raw\n${_body.value}"
                    confirmAndSend()
                } else {
                    voiceCommandEngine.speakThenListen(
                        "I didn't catch that. Say your message, 'attach file', 'send', or 'cancel'."
                    ) { retry -> handleForwardExtraResult(retry) }
                }
            }
        }
    }

    private fun askForTo() {
        voiceCommandEngine.speakThenListen(
            "Who would you like to send to? Please say the recipient's email address."
        ) { cmd -> handleToResult(cmd) }
    }

    private fun handleToResult(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.Cancel -> cancel()
            is VoiceCommand.FreeText -> {
                _to.value = cmd.text
                voiceManager.speak("Recipient set to ${cmd.text}.")
                askForSubject()
            }
            else -> {
                val raw = voiceManager.recognizedText.value ?: ""
                if (raw.isNotBlank()) {
                    _to.value = raw
                    voiceManager.speak("Recipient set to $raw.")
                    askForSubject()
                } else {
                    voiceCommandEngine.speakThenListen(
                        "I didn't catch that. Please say the recipient's email address, or say cancel to go back."
                    ) { retry -> handleToResult(retry) }
                }
            }
        }
    }

    private fun askForSubject() {
        voiceCommandEngine.speakThenListen("What is the subject of your email?") { cmd ->
            handleSubjectResult(cmd)
        }
    }

    private fun handleSubjectResult(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.Cancel -> cancel()
            is VoiceCommand.FreeText -> {
                _subject.value = cmd.text
                askForBody()
            }
            else -> {
                val raw = voiceManager.recognizedText.value ?: ""
                if (raw.isNotBlank()) {
                    _subject.value = raw
                    askForBody()
                } else {
                    voiceCommandEngine.speakThenListen(
                        "I didn't catch that. Please say the subject, or say cancel to go back."
                    ) { retry -> handleSubjectResult(retry) }
                }
            }
        }
    }

    private fun askForBody() {
        voiceCommandEngine.speakThenListen(
            "Say your message now, or say 'attach file' to add a file first."
        ) { cmd -> handleBodyResult(cmd) }
    }

    private fun handleBodyResult(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.Cancel -> cancel()
            is VoiceCommand.AttachFile -> requestFilePicker()
            is VoiceCommand.Send -> confirmAndSend()
            is VoiceCommand.FreeText -> {
                _body.value = cmd.text
                confirmAndSend()
            }
            else -> {
                val raw = voiceManager.recognizedText.value ?: ""
                if (raw.isNotBlank()) {
                    _body.value = raw
                    confirmAndSend()
                } else {
                    voiceCommandEngine.speakThenListen(
                        "I didn't catch that. Please say your message, 'attach file', or say cancel to go back."
                    ) { retry -> handleBodyResult(retry) }
                }
            }
        }
    }

    private fun confirmAndSend() {
        val attNote = when (_attachments.value.size) {
            0 -> ""
            1 -> "1 attachment: ${_attachments.value[0].filename}. "
            else -> "${_attachments.value.size} attachments. "
        }
        voiceCommandEngine.speakThenListen(
            "${attNote}Say 'send' to send, 'attach file' to add a file, or 'cancel' to go back."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.Send -> sendEmail(_to.value, _subject.value, _body.value)
                is VoiceCommand.AttachFile -> requestFilePicker()
                is VoiceCommand.Cancel -> cancel()
                else -> {
                    voiceCommandEngine.speakThenListen(
                        "Say 'send' to send, 'attach file' to add a file, or 'cancel'."
                    ) { retry ->
                        when (retry) {
                            is VoiceCommand.Send -> sendEmail(_to.value, _subject.value, _body.value)
                            is VoiceCommand.AttachFile -> requestFilePicker()
                            else -> cancel()
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Attachment helpers
    // ------------------------------------------------------------------

    /**
     * Emits [ComposeEvent.OpenFilePicker] so the UI layer can launch the
     * system file chooser. Call this whenever the user says "attach file".
     */
    fun requestFilePicker() {
        viewModelScope.launch { _composeEvent.emit(ComposeEvent.OpenFilePicker) }
    }

    /**
     * Called by the UI after the user selects a file. Adds the file to the
     * pending attachment list and announces the result via TTS.
     */
    fun attachmentSelected(filename: String, mimeType: String, bytes: ByteArray) {
        _attachments.value = _attachments.value + OutgoingAttachment(filename, mimeType, bytes)
        val count = _attachments.value.size
        val countNote = if (count > 1) " You now have $count attachments." else ""
        voiceCommandEngine.speakThenListen(
            "Attached: $filename.$countNote " +
                "Say 'send' to send, 'attach file' to add another, or 'cancel' to go back."
        ) { cmd -> confirmAndSend() }
    }

    /**
     * Announces that the chosen file exceeded the size limit (10 MB).
     */
    fun attachmentTooLarge() {
        voiceManager.speak(
            "That file is too large. Please choose a file under 10 megabytes."
        )
    }

    private fun cancel() {
        voiceManager.speak("Cancelled.")
        viewModelScope.launch { _navigateBack.emit(Unit) }
    }

    // ------------------------------------------------------------------
    // Traditional (sighted-caretaker) API — kept for manual text entry
    // ------------------------------------------------------------------

    fun updateTo(value: String) { _to.value = value }
    fun updateSubject(value: String) { _subject.value = value }
    fun updateBody(value: String) { _body.value = value }

    fun sendEmail(to: String, subject: String, body: String) {
        viewModelScope.launch {
            _sendState.value = SendState.Sending
            try {
                gmailRepository.sendEmail(to, subject, body, _attachments.value)
                _sendState.value = SendState.Success
                voiceManager.speak("Email sent successfully.")
                _navigateBack.emit(Unit)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to send email"
                _sendState.value = SendState.Error(msg)
                voiceCommandEngine.speakThenListen(
                    "Failed to send email. $msg. Say 'try again' or 'cancel'."
                ) { cmd ->
                    when (cmd) {
                        is VoiceCommand.TryAgain -> sendEmail(to, subject, body)
                        else -> cancel()
                    }
                }
            }
        }
    }

    fun startVoiceInput(onResult: (String) -> Unit) {
        voiceManager.startListening(onResult)
    }

    fun stopVoiceInput() {
        voiceManager.stopListening()
    }

    fun stopAll() = voiceCommandEngine.stopAll()
}

sealed class SendState {
    object Idle : SendState()
    object Sending : SendState()
    object Success : SendState()
    data class Error(val message: String) : SendState()
}
