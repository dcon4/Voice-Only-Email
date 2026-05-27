package com.example.voicegmail.voice

import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** High-level commands that the user can speak. */
sealed class VoiceCommand {
    object None            : VoiceCommand()
    object Read            : VoiceCommand()
    object Next            : VoiceCommand()
    object Previous        : VoiceCommand()
    object Refresh         : VoiceCommand()
    object Compose         : VoiceCommand()
    object Reply           : VoiceCommand()
    object Delete          : VoiceCommand()
    object Confirm         : VoiceCommand()
    object Search          : VoiceCommand()
    object MarkAsRead      : VoiceCommand()
    object MarkAsUnread    : VoiceCommand()
    object Forward         : VoiceCommand()
    object ReadAllUnread   : VoiceCommand()
    object Help            : VoiceCommand()
    object ListAttachments : VoiceCommand()
    data class ReadAttachment(val index: Int = 1) : VoiceCommand()
    object AttachFile      : VoiceCommand()
    object ReadBack        : VoiceCommand()
    data class RemoveAttachment(val index: Int = 1) : VoiceCommand()
    object Repeat          : VoiceCommand()
    object GoBack          : VoiceCommand()
    object Send            : VoiceCommand()
    object TryAgain        : VoiceCommand()
    object Cancel          : VoiceCommand()
    object VoiceSettings   : VoiceCommand()
    object Instructions    : VoiceCommand()
    object ReadSlower      : VoiceCommand()
    object ReadFaster      : VoiceCommand()
    object SessionTimeout  : VoiceCommand()

    /** Pause mid-email reading. */
    object Pause           : VoiceCommand()
    /** Resume reading from the last paused position. */
    object ContinueReading : VoiceCommand()

    // ── Compose editing ───────────────────────────────────────────────────
    /** Append more dictated text to the current message body. */
    object AddMore     : VoiceCommand()
    /** Keep To and Subject; clear the body and re-dictate from scratch. */
    object StartOver   : VoiceCommand()
    /**
     * Delete the last [unit] of the message body.
     * [unit] is one of: "word", "sentence", "paragraph".
     */
    data class DeleteLast(val unit: String) : VoiceCommand()

    // ── Drafts ────────────────────────────────────────────────────────────
    /** Save the current compose state as a Gmail draft. */
    object SaveDraft : VoiceCommand()
    /** List all saved drafts. */
    object ListDrafts : VoiceCommand()
    /** Open/edit draft number [index] (1-based). */
    data class EditDraft(val index: Int) : VoiceCommand()
    /** Delete draft number [index] (1-based). */
    data class DeleteDraft(val index: Int) : VoiceCommand()

    data class FreeText(val text: String) : VoiceCommand()
}

@Singleton
class VoiceCommandEngine @Inject constructor(
    private val voiceManager: VoiceManager
) {
    private val _lastCommand = MutableStateFlow<VoiceCommand>(VoiceCommand.None)
    val lastCommand: StateFlow<VoiceCommand> = _lastCommand

    fun speakThenListen(prompt: String, onCommand: (VoiceCommand) -> Unit) {
        voiceManager.speakAndThenListen(prompt) { candidates ->
            val cmd = parseAll(candidates)
            _lastCommand.value = cmd
            onCommand(cmd)
        }
    }

    fun speakEmailThenListen(prompt: String, onCommand: (VoiceCommand) -> Unit) {
        voiceManager.speakEmailAndThenListen(prompt) { candidates ->
            val cmd = parseAll(candidates)
            _lastCommand.value = cmd
            onCommand(cmd)
        }
    }

    /**
     * Speak one chunk of an email at email reading rate, then open a brief
     * listening window (no-speech max retries = 0).  On silence the callback
     * receives [VoiceCommand.FreeText] with an empty string so the caller can
     * continue to the next chunk automatically.
     */
    fun speakEmailSentenceAndListen(sentence: String, onCommand: (VoiceCommand) -> Unit) {
        voiceManager.speakEmailSentenceAndThenListen(sentence) { candidates ->
            val cmd = parseAll(candidates)
            _lastCommand.value = cmd
            onCommand(cmd)
        }
    }

    fun listen(onCommand: (VoiceCommand) -> Unit) {
        voiceManager.startListening { candidates ->
            val cmd = parseAll(candidates)
            _lastCommand.value = cmd
            onCommand(cmd)
        }
    }

    fun speakWithVoiceAndListen(text: String, voice: Voice, onCommand: (VoiceCommand) -> Unit) {
        voiceManager.speakWithVoiceAndThenListen(text, voice) { candidates ->
            val cmd = parseAll(candidates)
            _lastCommand.value = cmd
            onCommand(cmd)
        }
    }

    /**
     * Speak one chunk of an email at reading rate — no listening window.
     * [onDone] is invoked when TTS completes so the ViewModel can advance to
     * the next chunk immediately, keeping reading fluid and beep-free.
     */
    fun speakEmailChunk(text: String, onDone: () -> Unit) {
        voiceManager.speakEmailChunk(text, onDone)
    }

    fun stopAll() = voiceManager.stopAll()

    /**
     * Silently discard any in-flight recognition session without triggering
     * error callbacks.  Use instead of [stopAll] when you only need to cancel
     * the mic — the next [speakThenListen] call will restart TTS via
     * QUEUE_FLUSH on its own.
     */
    fun cancelListening() = voiceManager.cancelListening()

    // ------------------------------------------------------------------
    // Multi-hypothesis selection
    // ------------------------------------------------------------------

    private fun parseAll(candidates: List<String>): VoiceCommand {
        if (candidates.isEmpty()) return VoiceCommand.FreeText("")
        if (candidates.size == 1 && candidates[0] == "SESSION_TIMEOUT")
            return VoiceCommand.SessionTimeout
        for (raw in candidates) {
            val corrected = applyPhoneticCorrections(raw)
            val cmd = parse(corrected)
            if (cmd !is VoiceCommand.FreeText) return cmd
        }
        return VoiceCommand.FreeText(applyPhoneticCorrections(candidates[0]))
    }

    // ------------------------------------------------------------------
    // Phonetic corrections
    // ------------------------------------------------------------------

    private fun applyPhoneticCorrections(text: String): String {
        var s = text.trim().lowercase()
        s = when (s) {
            "reed", "reap", "re", "red"     -> "read"
            "necks", "tex", "text", "next please" -> "next"
            "a text"                         -> "next"
            "replay", "re-play"              -> "reply"
            "elite", "delete it"             -> "delete"
            "can sell", "cancel that", "ken sell" -> "cancel"
            "send it", "send that"           -> "send"
            "fore", "door"                   -> "forward"
            "refresh it", "re-fresh"         -> "refresh"
            "won"                            -> "one"
            "to", "too"                      -> "two"
            "for"                            -> "four"
            "ate"                            -> "eight"
            "add more", "and more"           -> "add more"
            else                             -> s
        }
        s = s
            .replace(Regex("\\belite\\b"), "delete")
            .replace(Regex("\\bcan sell\\b"), "cancel")
            .replace(Regex("\\bmark red\\b"), "mark as read")
            .replace(Regex("\\bmark it red\\b"), "mark as read")
            .replace(Regex("\\bmark read\\b"), "mark as read")
            .replace(Regex("\\bmark it read\\b"), "mark as read")
            .replace(Regex("\\bread back\\b"), "read back")
            .replace(Regex("\\bgo next\\b"), "next")
            .replace(Regex("\\bgo back\\b"), "go back")
            .replace(Regex("\\bwon\\b"), "one")
            .replace(Regex("\\bate\\b"), "eight")
            .replace(Regex("\\band more\\b"), "add more")
        return s
    }

    // ------------------------------------------------------------------
    // Single-hypothesis parser
    // ------------------------------------------------------------------

    private fun parse(text: String): VoiceCommand {
        val lower = text.trim().lowercase()
        return when {

            lower.contains("instructions") || lower.contains("user guide") ||
                lower.contains("help guide") || lower.contains("full guide") ||
                lower.contains("tutorial") || lower.contains("guide me") -> VoiceCommand.Instructions

            lower.contains("voice setting") || lower.contains("change voice") ||
                lower.contains("change tts") || lower.contains("speech setting") ||
                lower.contains("choose voice") || lower.contains("select voice") ||
                lower.contains("pick voice") || lower.contains("different voice") ||
                lower.contains("tts setting") -> VoiceCommand.VoiceSettings

            lower.contains("read slower") || lower.contains("slow down") ||
                lower.contains("read more slowly") || lower.contains("speak slower") ||
                lower.contains("speak more slowly") ||
                lower == "slower" -> VoiceCommand.ReadSlower

            lower.contains("read faster") || lower.contains("speed up") ||
                lower.contains("read more quickly") || lower.contains("read more fast") ||
                lower.contains("speak faster") || lower.contains("speak more quickly") ||
                lower == "faster" -> VoiceCommand.ReadFaster

            // ── Draft commands — checked before plain "delete" / "edit" ──────

            lower.contains("delete draft") || lower.contains("discard draft") ||
                lower.contains("remove draft") ->
                VoiceCommand.DeleteDraft(extractOrdinal(lower))

            lower.contains("edit draft") || lower.contains("open draft") ||
                lower.contains("resume draft") || lower.contains("continue draft") ||
                lower.contains("load draft") ->
                VoiceCommand.EditDraft(extractOrdinal(lower))

            lower.contains("save draft") || lower.contains("save as draft") ||
                lower.contains("draft it") || lower.contains("save it as draft") ->
                VoiceCommand.SaveDraft

            lower.contains("list drafts") || lower.contains("my drafts") ||
                lower.contains("show drafts") || lower.contains("check drafts") ||
                lower.contains("open drafts") || lower.contains("view drafts") ||
                lower.contains("all drafts") || lower.contains("see my drafts") ->
                VoiceCommand.ListDrafts

            // ── Pause / resume reading ────────────────────────────────────────

            lower == "pause" || lower == "pause reading" || lower == "stop reading" ->
                VoiceCommand.Pause

            lower == "continue" || lower == "continue reading" ||
                lower.contains("resume reading") || lower == "play" ||
                lower.contains("pick up where") || lower.contains("keep reading") ->
                VoiceCommand.ContinueReading

            // ── Compose body editing ─────────────────────────────────────────

            lower.contains("add more") || lower.contains("continue writing") ||
                lower.contains("add to that") || lower.contains("keep going") ||
                lower.contains("add more text") || lower == "more" ||
                lower.contains("and also") ||
                lower.contains("add another") -> VoiceCommand.AddMore

            // "start over" must come before "cancel"/"stop" checks
            lower.contains("start over") || lower.contains("start again") ||
                lower.contains("start from scratch") || lower.contains("new body") ||
                lower.contains("rewrite") || lower.contains("clear body") ||
                lower.contains("erase body") -> VoiceCommand.StartOver

            lower.contains("delete last") || lower.contains("undo last") ||
                lower.contains("remove last") || lower.contains("erase last") ||
                lower.contains("delete that last") || lower.contains("undo that") ->
                VoiceCommand.DeleteLast(
                    when {
                        lower.contains("paragraph") -> "paragraph"
                        lower.contains("sentence")  -> "sentence"
                        else                         -> "word"
                    }
                )

            // ── Standard commands ────────────────────────────────────────────

            lower.contains("try again") || lower.contains("retry") -> VoiceCommand.TryAgain

            // MarkAsUnread before MarkAsRead to avoid substring collision
            lower.contains("mark as unread") || lower.contains("mark unread") ||
                lower.contains("mark it unread") || lower.contains("mark this unread") ->
                VoiceCommand.MarkAsUnread

            lower.contains("mark as read") || lower.contains("mark read") ||
                lower.contains("mark it read") || lower.contains("mark this read") ->
                VoiceCommand.MarkAsRead

            lower.contains("read back") || lower.contains("read my message") ||
                lower.contains("what did i say") || lower.contains("review message") -> VoiceCommand.ReadBack

            lower.contains("read unread") || lower.contains("read all unread") ||
                lower.contains("unread emails") || lower.contains("check unread") ||
                lower.contains("hear unread") || lower.contains("show unread") -> VoiceCommand.ReadAllUnread

            lower.contains("list attachment") || lower.contains("what attachment") ||
                lower.contains("which attachment") || lower.contains("show attachment") -> VoiceCommand.ListAttachments

            lower.contains("remove attachment") || lower.contains("delete attachment") ||
                lower.contains("cancel attachment") ->
                VoiceCommand.RemoveAttachment(extractOrdinal(lower))

            lower.contains("attach file") || lower.contains("add file") ||
                lower.contains("add attachment") -> VoiceCommand.AttachFile

            lower.contains("read attachment") || lower.contains("attachment") ->
                VoiceCommand.ReadAttachment(extractOrdinal(lower))

            lower.contains("forward") -> VoiceCommand.Forward
            lower.contains("reply")   -> VoiceCommand.Reply

            lower.contains("help") || lower.contains("what can i say") ||
                lower.contains("commands") -> VoiceCommand.Help

            lower.contains("search") || lower.contains("find") ||
                lower.contains("look for") -> VoiceCommand.Search

            lower.contains("delete") || lower.contains("trash") ||
                lower.contains("remove") || lower.contains("erase") -> VoiceCommand.Delete

            lower.matches(Regex("(yes|yeah|yep|confirm|sure|do it|ok|okay|keep it?|that one|this one|perfect|great|good|like it|love it)")) ->
                VoiceCommand.Confirm

            lower.matches(Regex("(read( (it|next|this|email|message))?|hear( it)?)")) -> VoiceCommand.Read

            lower.contains("send") -> VoiceCommand.Send
            lower.contains("next") -> VoiceCommand.Next
            lower.matches(Regex("(go back( one)?|previous|prior|last|back)")) -> VoiceCommand.Previous
            lower.contains("refresh") || lower.contains("reload") -> VoiceCommand.Refresh
            lower.contains("compose") || lower.contains("new email") ||
                lower.contains("write an email") || lower.contains("write email") -> VoiceCommand.Compose
            lower.contains("repeat") || lower.contains("again") -> VoiceCommand.Repeat
            lower.matches(Regex("(cancel|exit|stop|never mind)")) -> VoiceCommand.Cancel
            lower.contains("go back") -> VoiceCommand.GoBack

            else -> VoiceCommand.FreeText(text)
        }
    }

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
