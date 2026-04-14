package com.example.voicegmail.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** High-level commands that the user can speak. */
sealed class VoiceCommand {
    object None : VoiceCommand()
    object Read : VoiceCommand()
    object Next : VoiceCommand()
    object Previous : VoiceCommand()
    object Refresh : VoiceCommand()
    object Compose : VoiceCommand()
    object Repeat : VoiceCommand()
    object GoBack : VoiceCommand()
    object Send : VoiceCommand()
    object TryAgain : VoiceCommand()
    object Cancel : VoiceCommand()
    /** Raw text that was recognised but did not match a navigation command. */
    data class FreeText(val text: String) : VoiceCommand()
}

/**
 * Parses recognised speech into [VoiceCommand] values and provides a
 * [speakThenListen] helper that chains TTS → recognition so that speech and
 * the microphone are never active at the same time.
 */
@Singleton
class VoiceCommandEngine @Inject constructor(
    private val voiceManager: VoiceManager
) {
    private val _lastCommand = MutableStateFlow<VoiceCommand>(VoiceCommand.None)
    val lastCommand: StateFlow<VoiceCommand> = _lastCommand

    /**
     * Speak [prompt] and then open the microphone. The recognised text is
     * parsed into a [VoiceCommand]; [onCommand] is called with the result.
     */
    fun speakThenListen(prompt: String, onCommand: (VoiceCommand) -> Unit) {
        voiceManager.speakAndThenListen(prompt) { recognizedText ->
            val cmd = parse(recognizedText)
            _lastCommand.value = cmd
            onCommand(cmd)
        }
    }

    /**
     * Start listening immediately (no TTS preamble). Useful for re-arming
     * the microphone after handling a command.
     */
    fun listen(onCommand: (VoiceCommand) -> Unit) {
        voiceManager.startListening { recognizedText ->
            val cmd = parse(recognizedText)
            _lastCommand.value = cmd
            onCommand(cmd)
        }
    }

    fun stopAll() = voiceManager.stopAll()

    // ------------------------------------------------------------------
    // Command parsing
    // ------------------------------------------------------------------

    private fun parse(text: String): VoiceCommand {
        val lower = text.trim().lowercase()
        return when {
            lower.contains("try again") || lower.contains("retry") -> VoiceCommand.TryAgain
            lower.contains("send") -> VoiceCommand.Send
            lower.matches(Regex("(read( next)?|hear( it)?)")) -> VoiceCommand.Read
            lower.contains("next") -> VoiceCommand.Next
            lower.matches(Regex("(previous|go back( one)?|prior|last)")) -> VoiceCommand.Previous
            lower.contains("refresh") || lower.contains("reload") -> VoiceCommand.Refresh
            lower.contains("compose") || lower.contains("new email") || lower.contains("write") -> VoiceCommand.Compose
            lower.contains("repeat") || lower.contains("again") -> VoiceCommand.Repeat
            lower.matches(Regex("(go back|back|cancel|exit|stop|never mind)")) -> VoiceCommand.Cancel
            else -> VoiceCommand.FreeText(text)
        }
    }
}
