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
    /** Read aloud the attachment at 1-based [index] (parser fills this). */
    data class ReadAttachment(val index: Int = 1) : VoiceCommand()
    object AttachFile : VoiceCommand()
    object ReadBack : VoiceCommand()
    data class RemoveAttachment(val index: Int = 1) : VoiceCommand()
    object Repeat : VoiceCommand()
    object GoBack : VoiceCommand()
    object Send : VoiceCommand()
    object TryAgain : VoiceCommand()
    object Cancel : VoiceCommand()
    /** Open the voice/TTS settings dialog. */
    object VoiceSettings : VoiceCommand()
    /** Play the multi-section spoken user guide. */
    object Instructions : VoiceCommand()
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

    fun speakThenListen(prompt: String, onCommand: (VoiceCommand) -> Unit) {
        voiceManager.speakAndThenListen(prompt) { recognizedText ->
            val cmd = parse(recognizedText)
            _lastCommand.value = cmd
            onCommand(cmd)
        }
    }

    fun listen(onCommand: (VoiceCommand) -> Unit) {
        voiceManager.startListening { recognizedText ->
            val cmd = parse(recognizedText)
            _lastCommand.value = cmd
            onCommand(cmd)
        }
    }

    fun stopAll() = voiceManager.stopAll()

    // ------------------------------------------------------------------
    // Command parsing — longer/more-specific phrases are checked first
    // to avoid substring collisions with shorter patterns.
    // ------------------------------------------------------------------

    private fun parse(text: String): VoiceCommand {
        val lower = text.trim().lowercase()
        return when {
            // ---- Instructions / guide (before "help" to capture "full help") -----
            lower.contains("instructions") || lower.contains("user guide") ||
                lower.contains("help guide") || lower.contains("full guide") ||
                lower.contains("tutorial") || lower.contains("guide me") -> VoiceCommand.Instructions

            // ---- Voice / TTS settings -------------------------------------------
            lower.contains("voice setting") || lower.contains("change voice") ||
                lower.contains("change tts") || lower.contains("speech setting") ||
                lower.contains("choose voice") || lower.contains("select voice") ||
                lower.contains("pick voice") || lower.contains("different voice") ||
                lower.contains("tts setting") -> VoiceCommand.VoiceSettings

            // ---- Retry / try again ----------------------------------------------
            lower.contains("try again") || lower.contains("retry") -> VoiceCommand.TryAgain

            // ---- Compose new email ----------------------------------------------
            lower.contains("new email") -> VoiceCommand.Compose

            // ---- Previous / back ------------------------------------------------
            lower.matches(Regex("(go back( one)?|previous|prior|last)")) -> VoiceCommand.Previous

            // ---- Read -----------------------------------------------------------
            lower.matches(Regex("(read( next)?|hear( it)?)")) -> VoiceCommand.Read

            // ---- Reply (before repeat) ------------------------------------------
            lower.contains("reply") -> VoiceCommand.Reply

            // ---- Mark as read ---------------------------------------------------
            lower.contains("mark as read") || lower.contains("mark read") ||
                lower.contains("mark it read") || lower.contains("mark this read") -> VoiceCommand.MarkAsRead

            // ---- Forward (before refresh) ---------------------------------------
            lower.contains("forward") -> VoiceCommand.Forward

            // ---- Read unread (before generic read) ------------------------------
            lower.contains("read unread") || lower.contains("read all unread") ||
                lower.contains("unread emails") || lower.contains("check unread") ||
                lower.contains("hear unread") || lower.contains("show unread") -> VoiceCommand.ReadAllUnread

            // ---- Help -----------------------------------------------------------
            lower.contains("help") || lower.contains("what can i say") ||
                lower.contains("commands") || lower.contains("what are my options") -> VoiceCommand.Help

            // ---- Read back (before generic read) --------------------------------
            lower.contains("read back") || lower.contains("read my message") ||
                lower.contains("what did i say") || lower.contains("review message") ||
                lower.contains("review my message") -> VoiceCommand.ReadBack

            // ---- Attach file (before remove attachment) -------------------------
            lower.contains("attach file") || lower.contains("add file") ||
                lower.contains("add attachment") -> VoiceCommand.AttachFile

            // ---- Remove attachment (before generic delete) ----------------------
            lower.contains("remove attachment") || lower.contains("delete attachment") ||
                lower.contains("cancel attachment") ->
                VoiceCommand.RemoveAttachment(extractOrdinal(lower))

            // ---- List attachments -----------------------------------------------
            lower.contains("list attachment") || lower.contains("what attachment") ||
                lower.contains("which attachment") || lower.contains("show attachment") -> VoiceCommand.ListAttachments

            // ---- Read attachment [ordinal] --------------------------------------
            lower.contains("attachment") -> VoiceCommand.ReadAttachment(extractOrdinal(lower))

            // ---- Search ---------------------------------------------------------
            lower.contains("search") || lower.contains("find") ||
                lower.contains("look for") -> VoiceCommand.Search

            // ---- Delete ---------------------------------------------------------
            lower.contains("delete") || lower.contains("trash") ||
                lower.contains("remove") || lower.contains("erase") -> VoiceCommand.Delete

            // ---- Confirmation ---------------------------------------------------
            lower.matches(Regex("(yes|yeah|yep|confirm|sure|do it|ok|okay)")) -> VoiceCommand.Confirm

            // ---- Send -----------------------------------------------------------
            lower.contains("send") -> VoiceCommand.Send

            // ---- Navigation -----------------------------------------------------
            lower.contains("next") -> VoiceCommand.Next
            lower.contains("refresh") || lower.contains("reload") -> VoiceCommand.Refresh
            lower.contains("compose") || lower.contains("write") -> VoiceCommand.Compose
            lower.contains("repeat") || lower.contains("again") -> VoiceCommand.Repeat
            lower.matches(Regex("(back|cancel|exit|stop|never mind)")) -> VoiceCommand.Cancel

            else -> VoiceCommand.FreeText(text)
        }
    }

    /**
     * Extracts a 1-based attachment index from a spoken phrase.
     * "read attachment one" → 1, "read attachment two" → 2, etc.
     * Returns 1 (first attachment) when no ordinal is found.
     */
    private fun extractOrdinal(phrase: String): Int {
        val ordinals = mapOf(
            "first"  to 1, "one"   to 1, "1" to 1,
            "second" to 2, "two"   to 2, "2" to 2,
            "third"  to 3, "three" to 3, "3" to 3,
            "fourth" to 4, "four"  to 4, "4" to 4,
            "fifth"  to 5, "five"  to 5, "5" to 5
        )
        for ((word, idx) in ordinals) {
            if (Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(phrase)) return idx
        }
        return 1
    }
}
