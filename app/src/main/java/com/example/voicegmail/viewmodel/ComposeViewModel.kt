package com.example.voicegmail.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.data.GmailRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val gmailRepository: GmailRepository
) : ViewModel() {

    sealed class ComposeState {
        object Idle : ComposeState()
        object Sending : ComposeState()
        object Sent : ComposeState()
        data class Error(val message: String) : ComposeState()
    }

    private val _state = MutableStateFlow<ComposeState>(ComposeState.Idle)
    val state: StateFlow<ComposeState> = _state

    val to = MutableStateFlow("")
    val subject = MutableStateFlow("")
    val body = MutableStateFlow("")

    fun setField(field: String, value: String) {
        when (field) {
            "to" -> to.value = value
            "subject" -> subject.value = value
            "body" -> body.value = value
        }
    }

    fun sendEmail() {
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            runCatching {
                gmailRepository.sendEmail(
                    to = to.value.trim(),
                    subject = subject.value.trim(),
                    body = body.value.trim()
                )
            }.onSuccess {
                _state.value = ComposeState.Sent
            }.onFailure { e ->
                _state.value = ComposeState.Error(e.message ?: "Failed to send email")
            }
        }
    }

    fun resetState() {
        _state.value = ComposeState.Idle
    }
}
