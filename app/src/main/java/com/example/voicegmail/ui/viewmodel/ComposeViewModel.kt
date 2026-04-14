package com.example.voicegmail.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.gmail.GmailRepository
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val gmailRepository: GmailRepository,
    private val voiceManager: VoiceManager
) : ViewModel() {

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState

    val isListening get() = voiceManager.isListening

    fun sendEmail(to: String, subject: String, body: String) {
        viewModelScope.launch {
            _sendState.value = SendState.Sending
            try {
                gmailRepository.sendEmail(to, subject, body)
                _sendState.value = SendState.Success
                voiceManager.speak("Email sent successfully.")
            } catch (e: Exception) {
                _sendState.value = SendState.Error(e.message ?: "Failed to send email")
                voiceManager.speak("Failed to send email. ${e.message}")
            }
        }
    }

    fun startVoiceInput(onResult: (String) -> Unit) {
        voiceManager.startListening(onResult)
    }

    fun stopVoiceInput() {
        voiceManager.stopListening()
    }
}

sealed class SendState {
    object Idle : SendState()
    object Sending : SendState()
    object Success : SendState()
    data class Error(val message: String) : SendState()
}
