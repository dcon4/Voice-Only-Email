package com.example.voicegmail

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton bus that [VoiceWakeService] posts to when the screen turns on.
 * [InboxViewModel] collects from [wakeEvent] and re-arms the microphone so the
 * user can speak a command immediately after pressing the power button.
 */
@Singleton
class WakeEventBus @Inject constructor() {
    private val _wakeEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val wakeEvent: SharedFlow<Unit> = _wakeEvent

    /** Called from [VoiceWakeService] on the background thread. Thread-safe. */
    fun postWake() {
        _wakeEvent.tryEmit(Unit)
    }
}
