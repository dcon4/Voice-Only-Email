package com.example.voicegmail.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts raw speech-to-text strings into typed [VoiceCommand] objects.
 *
 * Matching is case-insensitive and keyword-based, not ML-based, so it works
 * completely offline and requires no extra permissions beyond RECORD_AUDIO.
 */
@Singleton
class VoiceCommandParser @Inject constructor() {

    fun parse(rawText: String): VoiceCommand {
        val text = rawText.trim().lowercase()

        return when {
            text.contains("read inbox") || text.contains("check inbox") ||
                    text.contains("open inbox") -> VoiceCommand.ReadInbox

            text.startsWith("send to ") -> {
                val recipient = rawText.substringAfter("send to ").trim()
                VoiceCommand.SetTo(recipient)
            }

            text.startsWith("send email to ") -> {
                val recipient = rawText.substringAfter("send email to ").trim()
                VoiceCommand.SetTo(recipient)
            }

            text.startsWith("subject ") -> {
                val subject = rawText.substringAfter("subject ").trim()
                VoiceCommand.SetSubject(subject)
            }

            text.startsWith("body ") || text.startsWith("message ") -> {
                val body = rawText.substringAfter(" ").trim()
                VoiceCommand.SetBody(body)
            }

            text.contains("compose") || text.contains("new email") ||
                    text.contains("write email") -> VoiceCommand.Compose

            text.contains("send") && !text.startsWith("send to") &&
                    !text.startsWith("send email") -> VoiceCommand.SendEmail

            text.contains("settings") || text.contains("preferences") -> VoiceCommand.GoToSettings

            text.contains("sign out") || text.contains("log out") ||
                    text.contains("logout") -> VoiceCommand.SignOut

            text.contains("go back") || text.contains("back") -> VoiceCommand.GoBack

            text.contains("next") -> VoiceCommand.NextEmail

            text.contains("previous") || text.contains("prev") -> VoiceCommand.PreviousEmail

            text.contains("read") -> VoiceCommand.ReadEmail

            else -> VoiceCommand.Unknown(rawText)
        }
    }
}
