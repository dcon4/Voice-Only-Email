package com.example.voicegmail.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.data.EmailMessage
import com.example.voicegmail.data.GmailRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val gmailRepository: GmailRepository
) : ViewModel() {

    sealed class InboxState {
        object Loading : InboxState()
        data class Success(val messages: List<EmailMessage>) : InboxState()
        data class Error(val message: String) : InboxState()
    }

    private val _state = MutableStateFlow<InboxState>(InboxState.Loading)
    val state: StateFlow<InboxState> = _state

    private val _selectedMessage = MutableStateFlow<EmailMessage?>(null)
    val selectedMessage: StateFlow<EmailMessage?> = _selectedMessage

    init {
        loadInbox()
    }

    fun loadInbox() {
        _state.value = InboxState.Loading
        viewModelScope.launch {
            runCatching { gmailRepository.getInboxMessages() }
                .onSuccess { _state.value = InboxState.Success(it) }
                .onFailure { _state.value = InboxState.Error(it.message ?: "Failed to load inbox") }
        }
    }

    fun loadMessage(messageId: String) {
        viewModelScope.launch {
            runCatching { gmailRepository.getMessage(messageId) }
                .onSuccess { _selectedMessage.value = it }
                .onFailure { /* keep previous value */ }
        }
    }
}
