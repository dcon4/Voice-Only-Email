package com.example.voicegmail.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.gmail.GmailRepository
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

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val gmailRepository: GmailRepository,
    private val voiceManager: VoiceManager,
    private val voiceCommandEngine: VoiceCommandEngine
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

    /** Signals the UI to navigate back (after successful send or voice cancel). */
    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack

    // ------------------------------------------------------------------
    // Guided voice flow
    // ------------------------------------------------------------------

    /**
     * Starts the hands-free guided compose flow. Called once when the screen
     * is entered by a blind user. Steps:
     * 1. Capture recipient ("To")
     * 2. Capture subject
     * 3. Capture body
     * 4. Ask "send or cancel"
     */
    fun startGuidedVoiceFlow() {
        askForTo()
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
                // The raw recognized text may still be a valid email address even
                // if it matched a command keyword — use it directly.
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
        voiceCommandEngine.speakThenListen("Say your message now.") { cmd ->
            handleBodyResult(cmd)
        }
    }

    private fun handleBodyResult(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.Cancel -> cancel()
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
                        "I didn't catch that. Please say your message, or say cancel to go back."
                    ) { retry -> handleBodyResult(retry) }
                }
            }
        }
    }

    private fun confirmAndSend() {
        voiceCommandEngine.speakThenListen(
            "Your message is ready to send. Say 'send' to send it, or 'cancel' to go back."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.Send -> sendEmail(_to.value, _subject.value, _body.value)
                is VoiceCommand.Cancel -> cancel()
                else -> {
                    voiceCommandEngine.speakThenListen(
                        "Say 'send' to send, or 'cancel' to go back."
                    ) { retry ->
                        when (retry) {
                            is VoiceCommand.Send -> sendEmail(_to.value, _subject.value, _body.value)
                            else -> cancel()
                        }
                    }
                }
            }
        }
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
                gmailRepository.sendEmail(to, subject, body)
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
