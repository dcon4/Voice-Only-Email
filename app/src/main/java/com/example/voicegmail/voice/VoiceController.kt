package com.example.voicegmail.voice

import com.example.voicegmail.Screen
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes parsed [VoiceCommand] objects to navigation or ViewModel actions.
 *
 * The controller is intentionally stateless — callers pass in lambdas for
 * navigation and field-setting so it can be used from any screen.
 */
@Singleton
class VoiceController @Inject constructor(
    private val parser: VoiceCommandParser,
    private val voiceManager: VoiceManager
) {

    /**
     * Parse [rawText] and execute the appropriate action.
     *
     * @param rawText        Transcript from speech recognizer.
     * @param navigate       Lambda for navigation (route string).
     * @param onCompose      Called when user wants to compose; provides (to, subject, body) setters.
     * @param onSend         Called when user confirms send.
     * @param onSetField     Called with (field, value) for compose fields.
     * @param onSignOut      Called when user requests sign-out.
     * @param onBack         Called when user says "go back".
     */
    fun handle(
        rawText: String,
        navigate: (String) -> Unit,
        onCompose: (() -> Unit)? = null,
        onSend: (() -> Unit)? = null,
        onSetField: ((field: String, value: String) -> Unit)? = null,
        onSignOut: (() -> Unit)? = null,
        onBack: (() -> Unit)? = null
    ) {
        when (val cmd = parser.parse(rawText)) {
            is VoiceCommand.ReadInbox -> {
                voiceManager.speakNow("Opening inbox")
                navigate(Screen.INBOX)
            }
            is VoiceCommand.Compose -> {
                voiceManager.speakNow("Opening compose")
                navigate(Screen.COMPOSE)
                onCompose?.invoke()
            }
            is VoiceCommand.SendEmail -> {
                voiceManager.speakNow("Sending email")
                onSend?.invoke()
            }
            is VoiceCommand.SetTo -> {
                voiceManager.speakNow("Recipient set to ${cmd.recipient}")
                onSetField?.invoke("to", cmd.recipient)
            }
            is VoiceCommand.SetSubject -> {
                voiceManager.speakNow("Subject set to ${cmd.subject}")
                onSetField?.invoke("subject", cmd.subject)
            }
            is VoiceCommand.SetBody -> {
                voiceManager.speakNow("Message body set")
                onSetField?.invoke("body", cmd.body)
            }
            is VoiceCommand.GoToSettings -> {
                voiceManager.speakNow("Opening settings")
                navigate(Screen.SETTINGS)
            }
            is VoiceCommand.SignOut -> {
                voiceManager.speakNow("Signing out")
                onSignOut?.invoke()
            }
            is VoiceCommand.GoBack -> {
                onBack?.invoke()
            }
            is VoiceCommand.NextEmail -> {
                voiceManager.speakNow("Next email")
            }
            is VoiceCommand.PreviousEmail -> {
                voiceManager.speakNow("Previous email")
            }
            is VoiceCommand.ReadEmail -> {
                voiceManager.speakNow("Reading email")
            }
            is VoiceCommand.Unknown -> {
                voiceManager.speakNow("I did not understand: ${cmd.raw}")
            }
        }
    }
}
