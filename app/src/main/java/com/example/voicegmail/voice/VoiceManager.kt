package com.example.voicegmail.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsSettings: TtsSettingsRepository
) {
    private val tag = "VoiceManager"
    private val utteranceId = "vm_utterance"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private var recognitionWakeLock: PowerManager.WakeLock? = null

    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    /**
     * Separate speech rate used only for reading email bodies and attachments.
     * Adjusted by "read slower" / "read faster" commands (+/- 0.05).
     * All other TTS (prompts, instructions, confirmations) always runs at 1.0.
     */
    private var emailReadRate: Float = ttsSettings.getEmailReadRate()

    init {
        mainHandler.post { initTts() }
    }

    // ------------------------------------------------------------------
    // TTS initialisation
    // ------------------------------------------------------------------

    private fun initTts() {
        val savedEngine = ttsSettings.getEnginePackage()
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                applyVoicePreference()
                Log.d(tag, "TTS ready — engine=$savedEngine")
            } else {
                Log.e(tag, "TTS initialization failed: $status")
            }
        }
        tts = if (savedEngine != null) TextToSpeech(context, listener, savedEngine)
               else TextToSpeech(context, listener)
    }

    private fun applyVoicePreference() {
        val name = ttsSettings.getVoiceName() ?: return
        val voice = tts?.voices?.find { it.name == name }
        if (voice != null) {
            tts?.voice = voice
            Log.d(tag, "Applied saved voice: $name")
        } else {
            Log.w(tag, "Saved voice '$name' not found; clearing preference")
            ttsSettings.clearVoiceName()
        }
    }

    // ------------------------------------------------------------------
    // Engine & voice introspection
    // ------------------------------------------------------------------

    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> =
        tts?.engines?.sortedBy { it.label } ?: emptyList()

    fun getAvailableVoices(): List<Voice> =
        tts?.voices?.toList()?.sortedWith(
            compareBy({ it.isNetworkConnectionRequired }, { it.locale.displayLanguage }, { it.name })
        ) ?: emptyList()

    fun getCurrentEngineName(): String? = ttsSettings.getEnginePackage()
    fun getCurrentVoiceName(): String? = tts?.voice?.name ?: ttsSettings.getVoiceName()

    fun setVoiceByName(name: String) {
        mainHandler.post {
            val voice = tts?.voices?.find { it.name == name }
            if (voice != null) {
                tts?.voice = voice
                ttsSettings.saveVoiceName(name)
            }
        }
    }

    fun clearVoicePreference() {
        ttsSettings.clearVoiceName()
        mainHandler.post { if (ttsReady) tts?.language = Locale.US }
    }

    fun reinitWithEngine(enginePackage: String, onReady: () -> Unit) {
        mainHandler.post {
            tts?.stop(); tts?.shutdown(); tts = null; ttsReady = false
            val listener = TextToSpeech.OnInitListener { status ->
                mainHandler.post {
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.US
                        ttsReady = true
                        ttsSettings.saveEnginePackage(enginePackage)
                        applyVoicePreference()
                        onReady()
                    } else {
                        Log.e(tag, "Re-init failed for $enginePackage; falling back")
                        initTts()
                    }
                }
            }
            tts = TextToSpeech(context, listener, enginePackage)
        }
    }

    fun friendlyVoiceName(voice: Voice): String {
        val locale  = voice.locale
        val lang    = locale.displayLanguage
        val country = if (locale.country.isNotEmpty()) " ${locale.displayCountry}" else ""
        val n       = voice.name.lowercase()
        val gender  = when {
            n.contains("female") || n.contains("-f-") || n.endsWith("-f") -> " female"
            n.contains("male")   || n.contains("-m-") || n.endsWith("-m") -> " male"
            else -> ""
        }
        val network = if (voice.isNetworkConnectionRequired) " (online)" else ""
        return "$lang$country$gender$network".trim()
    }

    // ------------------------------------------------------------------
    // Email reading rate
    // ------------------------------------------------------------------

    /** Current email-reading rate as a percentage string, e.g. "95". */
    fun getEmailReadRatePct(): Int = (emailReadRate * 100).toInt()

    /**
     * Adjusts the email reading rate by [delta] (e.g. -0.05 or +0.05).
     * Clamped to [0.25, 3.0]. Persisted immediately.
     */
    fun adjustEmailReadRate(delta: Float) {
        emailReadRate = (emailReadRate + delta).coerceIn(0.25f, 3.0f)
        ttsSettings.saveEmailReadRate(emailReadRate)
        Log.d(tag, "Email read rate → ${getEmailReadRatePct()}%")
    }

    // ------------------------------------------------------------------
    // Phonetic correction — applied to every TTS string
    // ------------------------------------------------------------------

    private fun phoneticize(text: String): String {
        val protections = listOf(
            "unread"         to "\u0000A\u0000",
            "marked as read" to "\u0000B\u0000",
            "mark as read"   to "\u0000C\u0000",
            "already read"   to "\u0000D\u0000",
            "been read"      to "\u0000E\u0000",
            "was read"       to "\u0000F\u0000",
            "is read"        to "\u0000G\u0000",
            "not read"       to "\u0000H\u0000"
        )
        var result = text
        for ((phrase, token) in protections) {
            result = result.replace(phrase, token, ignoreCase = true)
        }
        result = result.replace(Regex("\\bread\\b", RegexOption.IGNORE_CASE), "reed")
        for ((phrase, token) in protections) {
            result = result.replace(token, phrase)
        }
        return result
    }

    // ------------------------------------------------------------------
    // TTS output — normal rate (prompts, confirmations, instructions)
    // ------------------------------------------------------------------

    fun speak(text: String) {
        mainHandler.post {
            if (ttsReady) {
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(null)
                tts?.speak(phoneticize(text), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
    }

    /**
     * Speaks [prompt] at the normal UI rate (1.0), then opens the mic.
     * Use for all prompts, instructions, and confirmations.
     */
    fun speakAndThenListen(prompt: String, onResults: (List<String>) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                startListeningOnMainThread(onResults)
                return@post
            }
            tts?.setSpeechRate(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {}
                override fun onDone(uid: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.postDelayed({ startListeningOnMainThread(onResults) }, TTS_TO_MIC_GAP_MS)
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(uid: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.postDelayed({ startListeningOnMainThread(onResults) }, TTS_TO_MIC_GAP_MS)
                }
            })
            tts?.speak(phoneticize(prompt), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    // ------------------------------------------------------------------
    // TTS output — email rate (email bodies and attachments only)
    // ------------------------------------------------------------------

    /**
     * Speaks [text] at the user's chosen email-reading rate, then opens the mic.
     * Restores rate to 1.0 after playback so all subsequent prompts sound normal.
     * Use only for reading email content and attachment text.
     */
    fun speakEmailAndThenListen(text: String, onResults: (List<String>) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                startListeningOnMainThread(onResults)
                return@post
            }
            tts?.setSpeechRate(emailReadRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {}
                override fun onDone(uid: String?) {
                    tts?.setSpeechRate(1.0f)
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.postDelayed({ startListeningOnMainThread(onResults) }, TTS_TO_MIC_GAP_MS)
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(uid: String?) {
                    tts?.setSpeechRate(1.0f)
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.postDelayed({ startListeningOnMainThread(onResults) }, TTS_TO_MIC_GAP_MS)
                }
            })
            tts?.speak(phoneticize(text), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    // ------------------------------------------------------------------
    // TTS output — voice audition (voice chooser, always rate 1.0)
    // ------------------------------------------------------------------

    fun speakWithVoiceAndThenListen(text: String, voice: Voice, onResults: (List<String>) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                startListeningOnMainThread(onResults)
                return@post
            }
            tts?.setSpeechRate(1.0f)
            tts?.voice = voice
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {}
                override fun onDone(uid: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.postDelayed({ startListeningOnMainThread(onResults) }, TTS_TO_MIC_GAP_MS)
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(uid: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.postDelayed({ startListeningOnMainThread(onResults) }, TTS_TO_MIC_GAP_MS)
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun startListening(onResults: (List<String>) -> Unit) {
        mainHandler.post { startListeningOnMainThread(onResults) }
    }

    // ------------------------------------------------------------------
    // Core recognition loop
    // ------------------------------------------------------------------

    /**
     * @param retryCount       Retries after the user spoke but wasn't understood.
     *                         Capped at [MAX_RETRIES] before returning empty results.
     * @param noSpeechRetries  Silent restarts when the recogniser fired before the
     *                         user began speaking. Capped at [NO_SPEECH_MAX_RETRIES].
     *                         When the cap is hit the session times out and the
     *                         sentinel "SESSION_TIMEOUT" is returned so the app can
     *                         go silent and wait for the next power-button wake.
     */
    private fun startListeningOnMainThread(
        onResults: (List<String>) -> Unit,
        retryCount: Int = 0,
        noSpeechRetries: Int = 0
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO not granted"); onResults(emptyList()); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(tag, "Speech recognition unavailable"); onResults(emptyList()); return
        }

        acquireRecognitionWakeLock()
        muteRecognitionBeep()

        var speechBegan = false

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    unmuteRecognitionBeep()
                }

                override fun onBeginningOfSpeech() {
                    speechBegan = true
                    Log.d(tag, "Speech began (retry=$retryCount noSpeech=$noSpeechRetries)")
                }

                override fun onPartialResults(partial: Bundle?) {
                    val text = partial
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }
                    if (!text.isNullOrBlank()) {
                        speechBegan = true
                        Log.d(tag, "Partial: $text")
                    }
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    releaseRecognitionWakeLock()
                    val candidates = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()
                    when {
                        candidates.isNotEmpty() -> {
                            _recognizedText.value = candidates[0]
                            Log.d(tag, "Results (${candidates.size}): ${candidates.take(3)}")
                            onResults(candidates)
                        }
                        !speechBegan && noSpeechRetries < NO_SPEECH_MAX_RETRIES -> {
                            Log.d(tag, "No speech — silent retry ${noSpeechRetries + 1}/$NO_SPEECH_MAX_RETRIES")
                            mainHandler.postDelayed({
                                startListeningOnMainThread(onResults, retryCount, noSpeechRetries + 1)
                            }, NO_SPEECH_RETRY_DELAY_MS)
                        }
                        !speechBegan -> {
                            // Hit the no-speech cap — session timeout.
                            Log.d(tag, "Session timeout after $NO_SPEECH_MAX_RETRIES no-speech retries")
                            onResults(listOf("SESSION_TIMEOUT"))
                        }
                        else -> retryOrFail(SpeechRecognizer.ERROR_NO_MATCH, onResults, retryCount)
                    }
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    releaseRecognitionWakeLock()
                    unmuteRecognitionBeep()
                    Log.w(tag, "Recognition error $error speechBegan=$speechBegan retry=$retryCount noSpeech=$noSpeechRetries")

                    when {
                        !speechBegan && noSpeechRetries < NO_SPEECH_MAX_RETRIES -> {
                            mainHandler.postDelayed({
                                startListeningOnMainThread(onResults, retryCount, noSpeechRetries + 1)
                            }, NO_SPEECH_RETRY_DELAY_MS)
                        }
                        !speechBegan -> {
                            Log.d(tag, "Session timeout (error path) after $NO_SPEECH_MAX_RETRIES no-speech retries")
                            onResults(listOf("SESSION_TIMEOUT"))
                        }
                        else -> retryOrFail(error, onResults, retryCount)
                    }
                }

                override fun onBufferReceived(buf: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(type: Int, params: Bundle?) {}
                override fun onRmsChanged(rmsdB: Float) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 500L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun retryOrFail(error: Int, onResults: (List<String>) -> Unit, retryCount: Int) {
        val retriable = error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        if (retriable && retryCount < MAX_RETRIES) {
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600L else 300L
            Log.d(tag, "Retrying recognition ($retryCount → ${retryCount + 1})")
            mainHandler.postDelayed({
                startListeningOnMainThread(onResults, retryCount + 1)
            }, delay)
        } else {
            Log.e(tag, "Recognition giving up (error=$error retries=$retryCount)")
            onResults(emptyList())
        }
    }

    // ------------------------------------------------------------------
    // Beep suppression
    // ------------------------------------------------------------------

    private fun muteRecognitionBeep() {
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            mainHandler.postDelayed(::unmuteRecognitionBeep, 1000)
        }
    }

    private fun unmuteRecognitionBeep() {
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    // ------------------------------------------------------------------
    // Wake lock
    // ------------------------------------------------------------------

    private fun acquireRecognitionWakeLock() {
        recognitionWakeLock?.let { if (it.isHeld) return }
        recognitionWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "VoiceGmail:RecognitionWakeLock"
        ).also { it.acquire(30_000L) }
    }

    private fun releaseRecognitionWakeLock() {
        recognitionWakeLock?.let { if (it.isHeld) it.release() }
        recognitionWakeLock = null
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    fun stopListening() {
        mainHandler.post { speechRecognizer?.stopListening(); _isListening.value = false }
    }

    fun stopAll() {
        mainHandler.post {
            tts?.stop()
            tts?.setSpeechRate(1.0f)
            speechRecognizer?.stopListening()
            _isListening.value = false
            unmuteRecognitionBeep()
        }
    }

    fun shutdown() {
        mainHandler.post {
            tts?.stop(); tts?.shutdown()
            speechRecognizer?.destroy(); speechRecognizer = null
            releaseRecognitionWakeLock()
        }
    }

    private companion object {
        const val TTS_TO_MIC_GAP_MS        = 1200L
        const val MAX_RETRIES              = 3
        /**
         * After this many consecutive no-speech mic openings the session times
         * out and the sentinel "SESSION_TIMEOUT" is emitted. The app then goes
         * silent and waits for the next power-button wake event.
         * With the default value of 4 the user hears 5 beeps total
         * (initial open + 4 silent retries) before the app goes quiet.
         */
        const val NO_SPEECH_MAX_RETRIES    = 4
        const val NO_SPEECH_RETRY_DELAY_MS = 250L
    }
}
