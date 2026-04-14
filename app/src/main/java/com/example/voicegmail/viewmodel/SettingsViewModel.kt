package com.example.voicegmail.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val voiceManager: VoiceManager
) : ViewModel() {

    private val _speechRate = MutableStateFlow(voiceManager.getSpeechRate())
    val speechRate: StateFlow<Float> = _speechRate

    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut

    fun setSpeechRate(rate: Float) {
        _speechRate.value = rate
        voiceManager.setSpeechRate(rate)
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _signedOut.value = true
        }
    }

    fun readAloud(text: String) {
        voiceManager.speakNow(text)
    }
}
