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
    object Reply : VoiceCommand()
    object Delete : VoiceCommand()
    object Confirm : VoiceCommand()
    object Search : VoiceCommand()
    object MarkAsRead : VoiceCommand()
    object Forward : VoiceCommand()
    object ReadAllUnread : VoiceCommand()
    object Help : VoiceCommand()
    object ListAttachments : VoiceCommand()
    /** Read aloud the attachment at zero-based [index]. Defaults to the first. */
    data class ReadAttachment(val index: Int = 0) : VoiceCommand()
    /** User wants to attach a file to the outgoing email they are composing. */
    object AttachFile : VoiceCommand()
    /** Remove a staged attachment at zero-based [index] before sending. */
    data class RemoveAttachment(val index: Int = 0) : VoiceCommand()
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
            // Multi-word / longer phrases before single-word checks
            lower.contains("try again") || lower.contains("retry") -> VoiceCommand.TryAgain
            lower.contains("new email") -> VoiceCommand.Compose
            lower.matches(Regex("(go back( one)?|previous|prior|last)")) -> VoiceCommand.Previous
            lower.matches(Regex("(read( next)?|hear( it)?)")) -> VoiceCommand.Read
            // "reply" before "repeat"
            lower.contains("reply") -> VoiceCommand.Reply
            // "mark as read" before generic "read" to avoid substring collision
            lower.contains("mark as read") || lower.contains("mark read") ||
                lower.contains("mark it read") || lower.contains("mark this read") -> VoiceCommand.MarkAsRead
            // "forward" — checked before "refresh"
            lower.contains("forward") -> VoiceCommand.Forward
            // "read unread" / "check unread" — checked before generic "read"
            lower.contains("read unread") || lower.contains("read all unread") ||
                lower.contains("unread emails") || lower.contains("check unread") ||
                lower.contains("hear unread") || lower.contains("show unread") -> VoiceCommand.ReadAllUnread
            // "help" — checked before other single-word commands
            lower.contains("help") || lower.contains("what can i say") ||
                lower.contains("commands") || lower.contains("what are my options") -> VoiceCommand.Help
            // "attach file" / "add file" — checked BEFORE attachment-reading rules so that
            // "add attachment" is routed here rather than to ReadAttachment.
            lower.contains("attach file") || lower.contains("add file") ||
                lower.contains("add attachment") -> VoiceCommand.AttachFile
            // "remove/delete/cancel attachment" — checked BEFORE the generic delete rule so
            // that "delete attachment one" isn't swallowed by VoiceCommand.Delete.
            (lower.contains("remove attachment") || lower.contains("delete attachment") ||
                lower.contains("cancel attachment")) ->
                VoiceCommand.RemoveAttachment(extractOrdinalFromPhrase(lower))
            // Attachments — "list" before "read attachment" to avoid collision
            lower.contains("list attachment") || lower.contains("what attachment") ||
                lower.contains("which attachment") || lower.contains("show attachment") -> VoiceCommand.ListAttachments
            // "read attachment [ordinal]" or just "attachment"
            lower.contains("attachment") -> VoiceCommand.ReadAttachment(extractOrdinalFromPhrase(lower))
            // "search" before "send" to avoid substring collision
            lower.contains("search") || lower.contains("find") ||
                lower.contains("look for") -> VoiceCommand.Search
            // "delete" / "trash" / "remove" — checked before "repeat" and "refresh"
            lower.contains("delete") || lower.contains("trash") ||
                lower.contains("remove") || lower.contains("erase") -> VoiceCommand.Delete
            // Confirmation for destructive actions
            lower.matches(Regex("(yes|yeah|yep|confirm|sure|do it|ok|okay)")) -> VoiceCommand.Confirm
            lower.contains("send") -> VoiceCommand.Send
            lower.contains("next") -> VoiceCommand.Next
            lower.contains("refresh") || lower.contains("reload") -> VoiceCommand.Refresh
            lower.contains("compose") || lower.contains("write") -> VoiceCommand.Compose
            lower.contains("repeat") || lower.contains("again") -> VoiceCommand.Repeat
            lower.matches(Regex("(back|cancel|exit|stop|never mind)")) -> VoiceCommand.Cancel
            else -> VoiceCommand.FreeText(text)
        }
    }

    /**
     * Extracts a zero-based attachment index from a spoken phrase.
     * Recognises ordinals ("first", "second" …) and digits ("1", "2" …).
     * Returns 0 (first attachment) when no ordinal is found.
     */
    private fun extractOrdinalFromPhrase(phrase: String): Int {
        val ordinals = mapOf(
            "first"  to 0, "one"   to 0, "1" to 0,
            "second" to 1, "two"   to 1, "2" to 1,
            "third"  to 2, "three" to 2, "3" to 2,
            "fourth" to 3, "four"  to 3, "4" to 3,
            "fifth"  to 4, "five"  to 4, "5" to 4
        )
        for ((word, idx) in ordinals) {
            if (Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(phrase)) return idx
        }
        return 0
    }
}
