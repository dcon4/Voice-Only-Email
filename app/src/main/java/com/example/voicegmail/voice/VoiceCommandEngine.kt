package com.example.voicegmail.voice

import android.speech.tts.Voice
import com.example.voicegmail.debug.DebugLogger
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

    /** User explicitly said "go to sleep" — stop all audio and listening until power button wake. */
    object GoToSleep       : VoiceCommand()

    /** User wants to listen to the Bible via Bible Brain audio. */
    object Bible           : VoiceCommand()

    /** User wants to browse the web by voice. */
    object Browser         : VoiceCommand()

    /** User wants to hear the battery percentage. */
    object Battery         : VoiceCommand()

    /** Read all results in a browser set sequentially. */
    object ReadAll         : VoiceCommand()
    /** Show the next page of browser results. */
    object MoreResults     : VoiceCommand()

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

    /**
     * Most recent recognition candidates from the *last* completed listen
     * session.  Exposed so contextual listeners (e.g. the Compose To-field
     * matcher) can sift through ALL hypotheses rather than just the top one
     * that [parseAll] picks for the dispatcher.  Cleared at the start of
     * each new listen so stale data isn't accidentally re-used.
     */
    @Volatile var lastCandidates: List<String> = emptyList()
        private set

    private fun parseAll(candidates: List<String>): VoiceCommand {
        lastCandidates = candidates
        if (candidates.isEmpty()) return VoiceCommand.FreeText("")
        if (candidates.size == 1 && candidates[0] == "SESSION_TIMEOUT")
            return VoiceCommand.SessionTimeout
        DebugLogger.log("VoiceCommandEngine", "parseAll candidates=$candidates")
        for (raw in candidates) {
            val corrected = applyPhoneticCorrections(raw)
            val cmd = parse(corrected)
            DebugLogger.log("VoiceCommandEngine", "  raw='$raw' corrected='$corrected' -> ${cmd::class.simpleName}")
            if (cmd !is VoiceCommand.FreeText) return cmd
        }
        val fallback = VoiceCommand.FreeText(applyPhoneticCorrections(candidates[0]))
        DebugLogger.log("VoiceCommandEngine", "  UNRECOGNIZED — falling through as FreeText: '${fallback.text}'")
        return fallback
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
            // "read all" classically misrecognised by Android STT (especially
            // on Bluetooth SCO narrowband audio) as "redial" / "re-dial" /
            // "read it all".  Map them all back to "read all unread" so the
            // parser routes to ReadAllUnread instead of accidentally treating
            // them as free-form text (which a search-prompt window would then
            // use as a query string).
            "redial", "re-dial", "re dial",
            "read all", "reed all", "red all", "read it all",
            "read 'em all", "read them all", "read everything",
            "hear all", "hear them all"      -> "read all unread"
            else                             -> s
        }
        s = s
            .replace(Regex("\\belite\\b"), "delete")
            .replace(Regex("\\bcan sell\\b"), "cancel")
            .replace(Regex("\\bmark red\\b"), "mark as read")
            .replace(Regex("\\bmark it red\\b"), "mark as read")
            .replace(Regex("\\bmark read\\b"), "mark as read")
            .replace(Regex("\\bmark it read\\b"), "mark as read")
            .replace(Regex("\\bmarked red\\b"), "marked as read")
            .replace(Regex("\\bmark on red\\b"), "mark unread")
            .replace(Regex("\\bmark it on red\\b"), "mark it unread")
            .replace(Regex("\\bread back\\b"), "read back")
            .replace(Regex("\\bgo next\\b"), "next")
            .replace(Regex("\\bgo back\\b"), "go back")
            .replace(Regex("\\bwon\\b"), "one")
            .replace(Regex("\\bate\\b"), "eight")
            .replace(Regex("\\band more\\b"), "add more")
            .replace(Regex("\\bgo to sleep\\b"), "go to sleep")
            .replace(Regex("\\bgoto sleep\\b"), "go to sleep")
            .replace(Regex("\\bgo too sleep\\b"), "go to sleep")
            // Also catch "redial" / "read all" appearing as part of a longer
            // misrecognised phrase, e.g. "redial please", "please redial".
            .replace(Regex("\\bredial\\b"), "read all unread")
            .replace(Regex("\\bre-dial\\b"), "read all unread")
            .replace(Regex("\\bread all\\b(?! unread)"), "read all unread")
            .replace(Regex("\\bdecon\\b"), "dcon")
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
                lower.contains("voice chooser") || lower.contains("tts setting") ->
                VoiceCommand.VoiceSettings

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

            lower == "drafts" || lower == "draft" ||
                lower.contains("list drafts") || lower.contains("my drafts") ||
                lower.contains("show drafts") || lower.contains("check drafts") ||
                lower.contains("open drafts") || lower.contains("view drafts") ||
                lower.contains("all drafts") || lower.contains("see my drafts") ->
                VoiceCommand.ListDrafts

            // ── Sleep — stop listening entirely until power button ─────────────

            lower == "go to sleep" || lower == "sleep" || lower == "go sleep" ||
                lower == "go to bed" || lower == "stop listening" ||
                lower == "goodnight" || lower == "good night" ||
                lower.contains("go to sleep") || lower.contains("go sleep") ||
                lower.contains("goodnight") || lower.contains("good night") ||
                lower.contains("stop listening") || lower.contains("go to bed") ||
                lower.startsWith("sleep") || lower.endsWith("sleep") ||
                lower == "i want to sleep" || lower.contains("time to sleep") ->
                VoiceCommand.GoToSleep

            // ── Bible audio ───────────────────────────────────────────────────

            lower == "bible" || lower == "read the bible" || lower == "play bible" ||
                lower.contains("bible") || lower.contains("scripture") ||
                lower.contains("read the bible") || lower.contains("listen to the bible") ||
                lower.contains("play bible") || lower.contains("bible audio") ->
                VoiceCommand.Bible

            // ── Battery ───────────────────────────────────────────────────────

            lower == "battery" || lower == "battery level" || lower == "battery percentage" ||
                lower == "what's my battery" || lower == "how's my battery" ||
                lower == "battery status" || lower == "check battery" ||
                lower.contains("battery level") || lower.contains("battery percentage") ||
                lower.contains("check my battery") || lower.contains("what is my battery") ->
                VoiceCommand.Battery

            // ── Browser — in-app voice web browsing ───────────────────────────

            lower == "browser" || lower == "browse" || lower == "internet" ||
                lower == "browse the web" || lower == "search the web" ||
                lower == "web search" || lower == "web browser" ||
                lower.contains("browse the web") || lower.contains("search the web") ||
                lower.contains("web search") || lower.contains("open browser") ||
                lower.contains("internet") || lower.contains("browse the internet") ||
                lower == "google" || lower == "search online" ||
                lower.contains("search online") || lower.contains("look up online") ->
                VoiceCommand.Browser

            // ── Pause / resume reading ────────────────────────────────────────

            lower == "pause" || lower == "pause reading" || lower == "stop reading" ->
                VoiceCommand.Pause

            lower in setOf("continue", "continue reading",
                    "continue continue", "continue continue reading") ||
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
                lower.contains("mark it unread") || lower.contains("mark this unread") ||
                lower.contains("marked unread") || lower.contains("marked as unread") ||
                lower.contains("mark it as unread") || lower.contains("mark this as unread") ->
                VoiceCommand.MarkAsUnread

            lower.contains("mark as read") || lower.contains("mark read") ||
                lower.contains("mark it read") || lower.contains("mark this read") ||
                lower.contains("marked read") || lower.contains("marked as read") ||
                lower.contains("mark it as read") || lower.contains("mark this as read") ->
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

            // "more results" / "next five" / "next results" — must be before plain "next"
            lower.contains("more result") || lower.contains("next five") ||
                lower.contains("next result") || lower.contains("more findings") ||
                lower.contains("next set") || lower.contains("next batch") ||
                lower == "more" -> VoiceCommand.MoreResults

            // "read all" / "all five" / "read them all" — must be before plain "read"
            lower.contains("read all") || lower.contains("all five") ||
                lower.contains("all of them") || lower.contains("read them all") ||
                lower == "all" -> VoiceCommand.ReadAll

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
