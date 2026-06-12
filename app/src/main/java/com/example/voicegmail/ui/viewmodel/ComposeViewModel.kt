package com.example.voicegmail.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicegmail.contacts.Contact
import com.example.voicegmail.contacts.ContactMatcher
import com.example.voicegmail.contacts.ContactsRepository
import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.gmail.GmailRepository
import com.example.voicegmail.gmail.OutgoingAttachment
import com.example.voicegmail.voice.ForwardDraft
import com.example.voicegmail.voice.VoiceCommand
import com.example.voicegmail.voice.VoiceCommandEngine
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot events emitted from the ViewModel to the UI layer. */
sealed class ComposeEvent {
    /** Request the UI to open the system file picker. */
    object OpenFilePicker : ComposeEvent()
}

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val gmailRepository: GmailRepository,
    private val contactsRepository: ContactsRepository,
    private val voiceManager: VoiceManager,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val forwardDraft: ForwardDraft
) : ViewModel() {

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState

    val isListening get() = voiceManager.isListening

    /** Current field values driven by voice or manual text entry. */
    private val _to = MutableStateFlow("")
    val to: StateFlow<String> = _to

    private val _subject = MutableStateFlow("")
    val subject: StateFlow<String> = _subject

    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body

    /** Files staged for the outgoing message. Populated by [attachmentSelected]. */
    private val _attachments = MutableStateFlow<List<OutgoingAttachment>>(emptyList())
    val attachments: StateFlow<List<OutgoingAttachment>> = _attachments

    /** Signals the UI to navigate back (after successful send or voice cancel). */
    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack

    /** One-shot events for the UI layer (e.g. open file picker). */
    private val _composeEvent = MutableSharedFlow<ComposeEvent>(extraBufferCapacity = 1)
    val composeEvent: SharedFlow<ComposeEvent> = _composeEvent

    // ------------------------------------------------------------------
    // Guided voice flow
    // ------------------------------------------------------------------

    /** True once [initForwardFromDraft] has loaded the forward fields. */
    private var isForwardMode = false

    fun initReplyTo(address: String) {
        _to.value = address
    }

    fun initForwardFromDraft() {
        val draft = forwardDraft.consume() ?: return
        _to.value = draft.to
        _subject.value = draft.subject
        _body.value = draft.body
        isForwardMode = true
    }

    fun startGuidedVoiceFlow() {
        if (isForwardMode) {
            askForForwardExtra()
            return
        }
        val existingTo = _to.value
        if (existingTo.isNotBlank()) {
            voiceCommandEngine.speakThenListen(
                "Replying to $existingTo. What is the subject of your reply?"
            ) { cmd -> handleSubjectResult(cmd) }
        } else {
            askForTo()
        }
    }

    // ------------------------------------------------------------------
    // Forward-mode helpers
    // ------------------------------------------------------------------

    private fun askForForwardExtra() {
        voiceCommandEngine.speakThenListen(
            "Forwarding to ${_to.value}. Subject: ${_subject.value}. " +
                "Say your additional message to add before the forwarded email, " +
                "'attach file' to add a file, or 'send' to forward it as is."
        ) { cmd -> handleForwardExtraResult(cmd) }
    }

    private fun handleForwardExtraResult(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.Send -> confirmAndSend()
            is VoiceCommand.ReadBack -> readBackMessage()
            is VoiceCommand.AttachFile -> requestFilePicker()
            is VoiceCommand.RemoveAttachment -> removeAttachment(cmd.index) { confirmAndSend() }
            is VoiceCommand.Cancel -> cancel()
            is VoiceCommand.FreeText -> {
                _body.value = "${cmd.text}\n${_body.value}"
                confirmAndSend()
            }
            else -> {
                val raw = voiceManager.recognizedText.value ?: ""
                if (raw.isNotBlank()) {
                    _body.value = "$raw\n${_body.value}"
                    confirmAndSend()
                } else {
                    voiceCommandEngine.speakThenListen(
                        "I didn't catch that. Say your message, 'attach file', 'send', or 'cancel'."
                    ) { retry -> handleForwardExtraResult(retry) }
                }
            }
        }
    }

    private fun askForTo() {
        voiceCommandEngine.speakThenListen(
            "Who would you like to send to? Say a name, like 'David Smith', " +
                "or spell out an email address."
        ) { cmd -> handleToResult(cmd) }
    }

    private fun handleToResult(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.Cancel -> cancel()
            is VoiceCommand.FreeText -> resolveRecipient(cmd.text)
            else -> {
                val raw = voiceManager.recognizedText.value ?: ""
                if (raw.isNotBlank()) resolveRecipient(raw)
                else voiceCommandEngine.speakThenListen(
                    "I didn't catch that. Please say the recipient's name or spell their email address, " +
                        "or say cancel to go back."
                ) { retry -> handleToResult(retry) }
            }
        }
    }

    /**
     * Voice-driven recipient resolution.  Pulls the user's contacts from
     * [ContactsRepository] (Google People API ∪ inbox senders), ranks every
     * recogniser candidate (not just the top one — Android STT often gets
     * the right answer as candidate #2 or #3) via
     * [ContactMatcher.rankAcrossCandidates], and:
     *  - if exactly one strong match → speaks it back, asks yes/no, then
     *    accepts the email and continues.
     *  - if multiple plausible matches → enumerates them and waits for
     *    "first", "second", "third", … "cancel".
     *  - if nothing matches but at least one candidate parses as an email-
     *    shaped string → lets the user pick from the parsed addresses.
     *  - otherwise → enters NATO-style letter-by-letter spelling mode.
     */
    private fun resolveRecipient(rawSpoken: String) {
        viewModelScope.launch {
            // Take every recogniser hypothesis if we have them; otherwise
            // fall back to whatever the caller passed in.
            val candidates = voiceCommandEngine.lastCandidates.ifEmpty { listOf(rawSpoken) }
            val contacts   = contactsRepository.combinedContacts(gmailRepository.lastLoadedInbox)
            val matches    = ContactMatcher.rankAcrossCandidates(candidates, contacts)

            DebugLogger.log(
                "ComposeVM",
                "resolveRecipient: candidates=$candidates contacts=${contacts.size} " +
                    "matches=${matches.size} topScore=${matches.firstOrNull()?.score ?: -1}"
            )

            when {
                // 1. Strong contact match — confirm and commit.
                matches.isNotEmpty() && (matches.size == 1
                        || matches[0].score >= STRONG_MATCH_SCORE
                        && matches[0].score - matches[1].score >= STRONG_LEAD) -> {
                    val pick = matches[0].contact
                    confirmRecipient(pick.email, displayName = pick.displayName)
                }
                // 2. Several plausible contact matches — enumerate.
                matches.isNotEmpty() -> enumerateMatches(matches.map { it.contact }, rawSpoken)
                // 3. No contact match — but at least one candidate parses
                //    as an email-shaped string.  Offer those for confirmation.
                else -> {
                    val parsedAddresses = ContactMatcher.parseEmailCandidates(candidates)
                    when {
                        parsedAddresses.size == 1 ->
                            confirmRecipient(parsedAddresses[0], displayName = null)
                        parsedAddresses.size > 1 ->
                            enumerateParsedAddresses(parsedAddresses, rawSpoken)
                        else ->
                            askToSpellEmail(unresolvedQuery = rawSpoken)
                    }
                }
            }
        }
    }

    /**
     * Like [enumerateMatches] but for raw parsed email-address strings (no
     * matching contact found).  Used when several recogniser hypotheses
     * each parsed to a different valid-looking address.
     */
    private fun enumerateParsedAddresses(addresses: List<String>, originalQuery: String) {
        val shown = addresses.take(MAX_ENUMERATED)
        val readable = shown.mapIndexed { i, a ->
            val ord = ORDINALS.getOrElse(i) { "option ${i + 1}" }
            "$ord, ${emailForSpeech(a)}"
        }.joinToString(". ")
        voiceCommandEngine.speakThenListen(
            "I didn't match a contact, but I heard ${shown.size} possible addresses for $originalQuery. $readable. " +
                "Say first, second, and so on. Say 'spell' to spell it letter by letter, or 'cancel' to start over."
        ) { reply ->
            val text = recognizedTextOrFreeText(reply).lowercase().trim()
            val pickIdx = parseOrdinal(reply, text, shown.size)
            when {
                reply is VoiceCommand.Cancel -> askForTo()
                text.contains("spell") -> spellEmailLetterByLetter()
                pickIdx != null -> confirmRecipient(shown[pickIdx], displayName = null)
                else -> {
                    // Re-prompt once before bailing to letter-by-letter spelling.
                    voiceCommandEngine.speakThenListen(
                        "Sorry, please say first, second, and so on; say 'spell' to spell it out; or 'cancel'."
                    ) { retry ->
                        val rt = recognizedTextOrFreeText(retry).lowercase().trim()
                        val idx2 = parseOrdinal(retry, rt, shown.size)
                        when {
                            retry is VoiceCommand.Cancel -> askForTo()
                            rt.contains("spell")         -> spellEmailLetterByLetter()
                            idx2 != null                 -> confirmRecipient(shown[idx2], displayName = null)
                            else                         -> spellEmailLetterByLetter()
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads back the proposed recipient and asks for yes/no confirmation.
     * On "no" / cancel / silence, re-prompts for the recipient.  On "yes"
     * commits the email and proceeds to the subject step.
     */
    private fun confirmRecipient(email: String, displayName: String?) {
        val readable = displayName?.takeIf { it.isNotBlank() }
            ?.let { "$it at ${emailForSpeech(email)}" }
            ?: emailForSpeech(email)
        voiceCommandEngine.speakThenListen(
            "Did you mean $readable? Say yes to confirm, or no to try again."
        ) { reply ->
            val text = recognizedTextOrFreeText(reply).lowercase().trim()
            val saidYes = AFFIRMATIVE_WORDS.any { it == text || text.startsWith("$it ") || text.endsWith(" $it") }
            val saidNo  = reply is VoiceCommand.Cancel ||
                NEGATIVE_WORDS.any { it == text || text.startsWith("$it ") || text.endsWith(" $it") }
            when {
                saidYes -> {
                    _to.value = email
                    voiceManager.speak("Recipient set to $readable.")
                    askForSubject()
                }
                saidNo -> askForTo()
                else -> {
                    // Unrecognized — re-confirm once, then bail to re-ask.
                    voiceCommandEngine.speakThenListen(
                        "Sorry, please say yes to confirm $readable, or no to try a different recipient."
                    ) { retry ->
                        val rt = recognizedTextOrFreeText(retry).lowercase().trim()
                        val yes2 = AFFIRMATIVE_WORDS.any { it == rt || rt.startsWith("$it ") }
                        if (yes2) {
                            _to.value = email
                            voiceManager.speak("Recipient set to $readable.")
                            askForSubject()
                        } else askForTo()
                    }
                }
            }
        }
    }

    /** Pulls the recognized text out of either the recognised-text flow or a FreeText command. */
    private fun recognizedTextOrFreeText(cmd: VoiceCommand): String {
        val raw = voiceManager.recognizedText.value
        if (!raw.isNullOrBlank()) return raw
        return if (cmd is VoiceCommand.FreeText) cmd.text else ""
    }

    /**
     * Reads up to [MAX_ENUMERATED] candidates aloud and waits for an ordinal
     * pick ("first", "second", …).  Falls back to [askForTo] on cancel or
     * unrecognized speech.
     */
    private fun enumerateMatches(candidates: List<Contact>, originalQuery: String) {
        val shown = candidates.take(MAX_ENUMERATED)
        val readable = shown.mapIndexed { i, c ->
            val ord = ORDINALS.getOrElse(i) { "option ${i + 1}" }
            "$ord, ${c.displayName} at ${emailForSpeech(c.email)}"
        }.joinToString(". ")
        voiceCommandEngine.speakThenListen(
            "I found ${shown.size} matches for $originalQuery. $readable. " +
                "Say first, second, and so on, or say cancel."
        ) { reply ->
            val text = (voiceManager.recognizedText.value ?: "")
                .lowercase().trim()
            val pickIdx = parseOrdinal(reply, text, shown.size)
            when {
                reply is VoiceCommand.Cancel -> askForTo()
                pickIdx != null -> {
                    val pick = shown[pickIdx]
                    confirmRecipient(pick.email, displayName = pick.displayName)
                }
                else -> {
                    voiceCommandEngine.speakThenListen(
                        "Sorry, please say first, second, or another ordinal — or say cancel."
                    ) { retry -> handleEnumerateRetry(retry, shown) }
                }
            }
        }
    }

    private fun handleEnumerateRetry(reply: VoiceCommand, shown: List<Contact>) {
        val text = (voiceManager.recognizedText.value ?: "").lowercase().trim()
        val pickIdx = parseOrdinal(reply, text, shown.size)
        when {
            reply is VoiceCommand.Cancel -> askForTo()
            pickIdx != null -> {
                val pick = shown[pickIdx]
                confirmRecipient(pick.email, displayName = pick.displayName)
            }
            else -> askForTo()
        }
    }

    /**
     * Fallback when contact matching AND parse-as-email both failed.
     * Offers the user the choice of spelling or trying again.
     */
    private fun askToSpellEmail(unresolvedQuery: String) {
        voiceCommandEngine.speakThenListen(
            "I couldn't find a contact for $unresolvedQuery. " +
                "Say 'spell' to spell it out letter by letter, 'try again' to repeat, or 'cancel'."
        ) { cmd ->
            val text = recognizedTextOrFreeText(cmd).lowercase().trim()
            when {
                cmd is VoiceCommand.Cancel    -> cancel()
                text.contains("spell")        -> spellEmailLetterByLetter()
                text.contains("try")  || text.contains("again") -> askForTo()
                else                          -> spellEmailLetterByLetter()
            }
        }
    }

    /**
     * Single-character-per-utterance NATO-alphabet spelling mode.
     *
     * Why this exists: Android SpeechRecognizer aggressively merges letters
     * into words when the user spells continuously ("d c o n" → "decon").
     * The recogniser is far more reliable on short, single-token utterances.
     * So we prompt for ONE character at a time and let the user say either:
     *   - the letter itself ("D")
     *   - the NATO phonetic ("delta")
     *   - a digit name ("zero", "one", …) or the digit itself
     *   - "at sign" → @, "dot" → ., "dash" → -, "underscore" → _
     *   - "backspace" / "delete" → remove the last character
     *   - "done" / "send" → finish spelling and confirm
     *   - "cancel" → bail out
     *
     * After every character we read back the buffer so the user can verify.
     */
    private fun spellEmailLetterByLetter() {
        val buf = StringBuilder()
        spellPrompt(buf, "Spelling mode. Say one letter or word at a time. " +
            "For D, say 'D' or 'delta'. Say 'at sign' for the @, 'dot' for a period, " +
            "'backspace' to remove the last character, and 'done' when finished.")
    }

    private fun spellPrompt(buf: StringBuilder, prompt: String) {
        voiceCommandEngine.speakThenListen(prompt) { reply ->
            val text = recognizedTextOrFreeText(reply).lowercase().trim()
            if (reply is VoiceCommand.Cancel || text == "cancel" || text == "stop") {
                askForTo()
                return@speakThenListen
            }
            if (text == "done" || text == "send" || text == "finish" || text == "finished") {
                val candidate = buf.toString()
                val parsed = ContactMatcher.parseDictatedEmail(candidate)
                    ?: ContactMatcher.tryParseSpelledOut(candidate)
                    ?: candidate
                if (parsed.contains("@") && parsed.contains(".")) {
                    confirmRecipient(parsed, displayName = null)
                } else {
                    spellPrompt(buf,
                        "That doesn't look like a complete email address yet. " +
                            "So far I have: ${spelledBack(buf)}. " +
                            "Keep going, or say 'cancel'.")
                }
                return@speakThenListen
            }
            // Editing: "backspace" / "delete (the) last" — remove one char.
            if (text == "backspace" || text == "delete" || text.contains("last")) {
                if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
                spellPrompt(buf, "Removed. So far: ${spelledBack(buf)}. Continue, or say done.")
                return@speakThenListen
            }
            // Try mapping the utterance to a single character.
            val ch = mapSpelledToken(text)
            if (ch != null) {
                buf.append(ch)
                spellPrompt(buf, "${spokenChar(ch)}. So far: ${spelledBack(buf)}.")
            } else {
                spellPrompt(buf,
                    "I didn't catch that. So far: ${spelledBack(buf)}. " +
                        "Say one letter, digit, 'at sign', 'dot', 'backspace', or 'done'.")
            }
        }
    }

    /**
     * Maps a single recogniser utterance to the single character it most
     * likely represents.  Returns null when the token can't be confidently
     * mapped — caller should re-prompt rather than guess.
     */
    private fun mapSpelledToken(token: String): Char? {
        val t = token.trim()
        if (t.isEmpty()) return null
        // Already a single character?
        if (t.length == 1) {
            val c = t[0]
            return if (c.isLetterOrDigit() || c in "@._-+") c else null
        }
        // Punctuation by name.
        when (t) {
            "at", "at sign", "at symbol", "the at sign" -> return '@'
            "dot", "period", "point", "full stop"      -> return '.'
            "dash", "hyphen", "minus"                  -> return '-'
            "underscore", "under score", "underline"   -> return '_'
            "plus", "plus sign"                        -> return '+'
            "space"                                    -> return null // ignore — emails have no spaces
        }
        // Digits by name.
        DIGIT_WORDS[t]?.let { return it }
        // NATO phonetic.
        NATO_TO_LETTER[t]?.let { return it }
        // Common ASR substitutes for single letters.
        LETTER_SUBSTITUTES[t]?.let { return it }
        return null
    }

    /** Pretty-prints the buffer for read-back, e.g. "d, c, o, n at sign, …" */
    private fun spelledBack(buf: StringBuilder): String {
        if (buf.isEmpty()) return "nothing yet"
        return buf.toString().map { spokenChar(it) }.joinToString(", ")
    }

    private fun spokenChar(c: Char): String = when (c) {
        '@'  -> "at sign"
        '.'  -> "dot"
        '-'  -> "dash"
        '_'  -> "underscore"
        '+'  -> "plus"
        else -> c.toString()
    }

    /**
     * Makes an email address pronounceable: replaces "@" → "at" and "." → "dot".
     */
    private fun emailForSpeech(email: String): String =
        email.replace("@", " at ").replace(".", " dot ")

    private fun parseOrdinal(cmd: VoiceCommand, raw: String, max: Int): Int? {
        // Numeric word forms first.
        for ((idx, words) in ORDINAL_WORDS.withIndex()) {
            if (idx >= max) break
            for (w in words) if (raw.contains(w)) return idx
        }
        // Bare digits ("1", "2", …) or "number 1".
        Regex("\\b([1-9])\\b").find(raw)?.let {
            val n = it.groupValues[1].toInt() - 1
            if (n in 0 until max) return n
        }
        // VoiceCommand parser might already classify it.
        if (cmd is VoiceCommand.FreeText) {
            val t = cmd.text.lowercase()
            for ((idx, words) in ORDINAL_WORDS.withIndex()) {
                if (idx >= max) break
                for (w in words) if (t.contains(w)) return idx
            }
        }
        return null
    }

    private companion object {
        /** Top-match score above which we skip enumeration and go to confirm. */
        const val STRONG_MATCH_SCORE = 80

        /** Required lead over the next match to consider top match "unambiguous". */
        const val STRONG_LEAD = 15

        /** Maximum number of options spoken aloud. */
        const val MAX_ENUMERATED = 5

        val ORDINALS = listOf("First", "Second", "Third", "Fourth", "Fifth")

        val ORDINAL_WORDS = listOf(
            listOf("first",  "one",   "number one",   "the first"),
            listOf("second", "two",   "number two",   "the second"),
            listOf("third",  "three", "number three", "the third"),
            listOf("fourth", "four",  "number four",  "the fourth"),
            listOf("fifth",  "five",  "number five",  "the fifth")
        )

        val AFFIRMATIVE_WORDS = listOf(
            "yes", "yeah", "yep", "yup", "yah",
            "correct", "right", "confirm", "confirmed",
            "ok", "okay", "sure", "do it", "send it"
        )

        val NEGATIVE_WORDS = listOf(
            "no", "nope", "nah", "wrong",
            "different", "try again", "incorrect", "not that one", "not that"
        )

        /** NATO phonetic alphabet → letter.  Used by the spelling fallback. */
        val NATO_TO_LETTER = mapOf(
            "alpha" to 'a', "alfa" to 'a',
            "bravo" to 'b',
            "charlie" to 'c',
            "delta" to 'd',
            "echo" to 'e',
            "foxtrot" to 'f', "fox" to 'f',
            "golf" to 'g',
            "hotel" to 'h',
            "india" to 'i', "indigo" to 'i',
            "juliet" to 'j', "juliett" to 'j',
            "kilo" to 'k',
            "lima" to 'l',
            "mike" to 'm',
            "november" to 'n', "nan" to 'n',
            "oscar" to 'o',
            "papa" to 'p',
            "quebec" to 'q', "queen" to 'q',
            "romeo" to 'r',
            "sierra" to 's',
            "tango" to 't',
            "uniform" to 'u', "union" to 'u',
            "victor" to 'v',
            "whiskey" to 'w', "whisky" to 'w',
            "x-ray" to 'x', "xray" to 'x', "x ray" to 'x',
            "yankee" to 'y',
            "zulu" to 'z'
        )

        /** Common single-letter homophones the recogniser tends to return. */
        val LETTER_SUBSTITUTES = mapOf(
            "ay" to 'a', "ae" to 'a', "eh" to 'a',
            "bee" to 'b', "be" to 'b',
            "see" to 'c', "sea" to 'c', "cee" to 'c',
            "dee" to 'd',
            "ee" to 'e', "e" to 'e',
            "ef" to 'f', "eff" to 'f',
            "gee" to 'g',
            "aitch" to 'h', "haitch" to 'h',
            "eye" to 'i', "i" to 'i',
            "jay" to 'j', "jaye" to 'j',
            "kay" to 'k',
            "el" to 'l', "ell" to 'l',
            "em" to 'm',
            "en" to 'n',
            "oh" to 'o',
            "pee" to 'p', "pea" to 'p',
            "cue" to 'q', "queue" to 'q',
            "are" to 'r', "arr" to 'r',
            "es" to 's', "ess" to 's',
            "tee" to 't', "tea" to 't',
            "you" to 'u', "yew" to 'u',
            "vee" to 'v',
            "double you" to 'w', "double u" to 'w',
            "ex" to 'x',
            "why" to 'y', "wye" to 'y',
            "zee" to 'z', "zed" to 'z'
        )

        /** Digit words → digit char. */
        val DIGIT_WORDS = mapOf(
            "zero" to '0', "oh" to '0', "naught" to '0', "nought" to '0',
            "one" to '1', "won" to '1',
            "two" to '2', "to" to '2', "too" to '2',
            "three" to '3', "tree" to '3',
            "four" to '4', "for" to '4', "fore" to '4',
            "five" to '5',
            "six" to '6',
            "seven" to '7',
            "eight" to '8', "ate" to '8',
            "nine" to '9', "niner" to '9'
        )
    }

    private fun askForSubject() {
        voiceCommandEngine.speakThenListen("What is the subject of your email?") { cmd ->
            handleSubjectResult(cmd)
        }
    }

    private fun handleSubjectResult(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.Cancel -> cancel()
            is VoiceCommand.FreeText -> {
                _subject.value = cmd.text
                askForBody()
            }
            else -> {
                val raw = voiceManager.recognizedText.value ?: ""
                if (raw.isNotBlank()) {
                    _subject.value = raw
                    askForBody()
                } else {
                    voiceCommandEngine.speakThenListen(
                        "I didn't catch that. Please say the subject, or say cancel to go back."
                    ) { retry -> handleSubjectResult(retry) }
                }
            }
        }
    }

    private fun askForBody() {
        voiceCommandEngine.speakThenListen(
            "Say your message now, or say 'attach file' to add a file first."
        ) { cmd -> handleBodyResult(cmd) }
    }

    private fun handleBodyResult(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.Cancel -> cancel()
            is VoiceCommand.ReadBack -> readBackMessage()
            is VoiceCommand.AttachFile -> requestFilePicker()
            is VoiceCommand.RemoveAttachment -> removeAttachment(cmd.index) { confirmAndSend() }
            is VoiceCommand.Send -> confirmAndSend()
            is VoiceCommand.FreeText -> {
                _body.value = cmd.text
                confirmAndSend()
            }
            else -> {
                val raw = voiceManager.recognizedText.value ?: ""
                if (raw.isNotBlank()) {
                    _body.value = raw
                    confirmAndSend()
                } else {
                    voiceCommandEngine.speakThenListen(
                        "I didn't catch that. Please say your message, 'attach file', or say cancel to go back."
                    ) { retry -> handleBodyResult(retry) }
                }
            }
        }
    }

    private fun confirmAndSend() {
        val attNote = when (_attachments.value.size) {
            0 -> ""
            1 -> "1 attachment: ${_attachments.value[0].filename}. "
            else -> "${_attachments.value.size} attachments. "
        }
        val removeHint = if (_attachments.value.isNotEmpty())
            "'remove attachment one' to remove a file, " else ""
        voiceCommandEngine.speakThenListen(
            "${attNote}Say 'send' to send, 'read back' to hear your message, " +
                "'attach file' to add a file, ${removeHint}or 'cancel' to go back."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.Send -> sendEmail(_to.value, _subject.value, _body.value)
                is VoiceCommand.ReadBack -> readBackMessage()
                is VoiceCommand.AttachFile -> requestFilePicker()
                is VoiceCommand.RemoveAttachment -> removeAttachment(cmd.index) { confirmAndSend() }
                is VoiceCommand.Cancel -> cancel()
                else -> {
                    voiceCommandEngine.speakThenListen(
                        "Say 'send' to send, 'read back' to hear your message, " +
                            "'attach file' to add a file, ${removeHint}or 'cancel'."
                    ) { retry ->
                        when (retry) {
                            is VoiceCommand.Send -> sendEmail(_to.value, _subject.value, _body.value)
                            is VoiceCommand.ReadBack -> readBackMessage()
                            is VoiceCommand.AttachFile -> requestFilePicker()
                            is VoiceCommand.RemoveAttachment ->
                                removeAttachment(retry.index) { confirmAndSend() }
                            else -> cancel()
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Attachment helpers
    // ------------------------------------------------------------------

    fun requestFilePicker() {
        viewModelScope.launch { _composeEvent.emit(ComposeEvent.OpenFilePicker) }
    }

    fun attachmentSelected(filename: String, mimeType: String, bytes: ByteArray) {
        _attachments.value = _attachments.value + OutgoingAttachment(filename, mimeType, bytes)
        val count = _attachments.value.size
        val countNote = if (count > 1) " You now have $count attachments." else ""
        voiceCommandEngine.speakThenListen(
            "Attached: $filename.$countNote " +
                "Say 'send' to send, 'attach file' to add another, " +
                "'remove attachment one' to remove it, or 'cancel' to go back."
        ) { _ -> confirmAndSend() }
    }

    private fun readBackMessage() {
        val bodyText = _body.value.takeIf { it.isNotBlank() } ?: "no message body"
        val attNote = when (_attachments.value.size) {
            0 -> ""
            1 -> " Attachment: ${_attachments.value[0].filename}."
            else -> " Attachments: ${_attachments.value.joinToString(", ") { it.filename }}."
        }
        val removeHint = if (_attachments.value.isNotEmpty())
            "'remove attachment one' to remove a file, " else ""
        voiceCommandEngine.speakThenListen(
            "To: ${_to.value}. Subject: ${_subject.value}. Message: $bodyText.$attNote " +
                "Say 'send' to send, 'read back' to hear it again, " +
                "'attach file' to add a file, ${removeHint}or 'cancel' to go back."
        ) { cmd ->
            when (cmd) {
                is VoiceCommand.Send -> sendEmail(_to.value, _subject.value, _body.value)
                is VoiceCommand.ReadBack -> readBackMessage()
                is VoiceCommand.AttachFile -> requestFilePicker()
                is VoiceCommand.RemoveAttachment -> removeAttachment(cmd.index) { readBackMessage() }
                is VoiceCommand.Cancel -> cancel()
                else -> confirmAndSend()
            }
        }
    }

    fun attachmentTooLarge() {
        voiceManager.speak(
            "That file is too large. Please choose a file under 10 megabytes."
        )
    }

    private fun removeAttachment(index: Int, onDone: () -> Unit) {
        val current = _attachments.value
        if (current.isEmpty()) {
            voiceCommandEngine.speakThenListen(
                "You have no attachments to remove."
            ) { _ -> onDone() }
            return
        }
        val att = current.getOrNull(index)
        if (att == null) {
            val count = current.size
            voiceCommandEngine.speakThenListen(
                "There is no attachment ${index + 1}. " +
                    "You have $count attachment${if (count == 1) "" else "s"}. " +
                    "Say 'remove attachment one' for the first, and so on."
            ) { _ -> onDone() }
            return
        }
        _attachments.value = current.toMutableList().also { it.removeAt(index) }
        val remaining = _attachments.value.size
        val remainingNote = when (remaining) {
            0 -> " No attachments remaining."
            1 -> " 1 attachment remaining: ${_attachments.value[0].filename}."
            else -> " $remaining attachments remaining."
        }
        voiceCommandEngine.speakThenListen(
            "Removed: ${att.filename}.$remainingNote " +
                "Say 'send' to send, 'attach file' to add a file, or 'cancel'."
        ) { _ -> onDone() }
    }

    private fun cancel() {
        voiceManager.speak("Cancelled.")
        viewModelScope.launch { _navigateBack.emit(Unit) }
    }

    // ------------------------------------------------------------------
    // Traditional (sighted-caretaker) API — kept for manual text entry
    // ------------------------------------------------------------------

    fun updateTo(value: String) { _to.value = value }
    fun updateSubject(value: String) { _subject.value = value }
    fun updateBody(value: String) { _body.value = value }

    fun sendEmail(to: String, subject: String, body: String) {
        viewModelScope.launch {
            _sendState.value = SendState.Sending
            try {
                gmailRepository.sendEmail(to, subject, body, _attachments.value)
                _sendState.value = SendState.Success
                voiceManager.speak("Email sent successfully.")
                _navigateBack.emit(Unit)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to send email"
                _sendState.value = SendState.Error(msg)
                voiceCommandEngine.speakThenListen(
                    "Failed to send email. $msg. Say 'try again' or 'cancel'."
                ) { cmd ->
                    when (cmd) {
                        is VoiceCommand.TryAgain -> sendEmail(to, subject, body)
                        else -> cancel()
                    }
                }
            }
        }
    }

    /**
     * Adapts the legacy [onResult] (String) callback for callers that use the
     * pre-existing manual voice-input API. Uses only the highest-confidence
     * hypothesis from the multi-result recogniser.
     */
    fun startVoiceInput(onResult: (String) -> Unit) {
        voiceManager.startListening { candidates ->
            onResult(candidates.firstOrNull() ?: "")
        }
    }

    fun stopVoiceInput() {
        voiceManager.stopListening()
    }

    fun stopAll() = voiceCommandEngine.stopAll()
}

sealed class SendState {
    object Idle : SendState()
    object Sending : SendState()
    object Success : SendState()
    data class Error(val message: String) : SendState()
}
