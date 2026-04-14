package com.example.voicegmail.voice

/** Represents a parsed voice command intent. */
sealed class VoiceCommand {
    object ReadInbox : VoiceCommand()
    object Compose : VoiceCommand()
    object GoToSettings : VoiceCommand()
    object SignOut : VoiceCommand()
    object GoBack : VoiceCommand()
    object NextEmail : VoiceCommand()
    object PreviousEmail : VoiceCommand()
    object ReadEmail : VoiceCommand()
    object SendEmail : VoiceCommand()
    data class SetTo(val recipient: String) : VoiceCommand()
    data class SetSubject(val subject: String) : VoiceCommand()
    data class SetBody(val body: String) : VoiceCommand()
    data class Unknown(val raw: String) : VoiceCommand()
}
