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
    // TTS output
    // ------------------------------------------------------------------

    fun speak(text: String) {
        mainHandler.post {
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(null)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
    }

    /**
     * Speaks [prompt] then opens the mic after a short gap so that:
     * 1. TTS audio has fully decayed (prevents echo being recognised as a command).
     * 2. The recognition start beep has played and been muted before it reaches
     *    the callback chain.
     */
    fun speakAndThenListen(prompt: String, onResults: (List<String>) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                startListeningOnMainThread(onResults)
                return@post
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {}
                override fun onDone(uid: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    // 500 ms gap: lets TTS audio decay so the microphone doesn't
                    // immediately pick up echo, and gives the user a natural beat
                    // to start speaking.
                    mainHandler.postDelayed({ startListeningOnMainThread(onResults) }, 500)
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(uid: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.postDelayed({ startListeningOnMainThread(onResults) }, 300)
                }
            })
            tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun startListening(onResults: (List<String>) -> Unit) {
        mainHandler.post { startListeningOnMainThread(onResults) }
    }

    // ------------------------------------------------------------------
    // Core recognition loop
    // ------------------------------------------------------------------

    private fun startListeningOnMainThread(
        onResults: (List<String>) -> Unit,
        retryCount: Int = 0
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO not granted")
            onResults(emptyList()); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(tag, "Speech recognition unavailable")
            onResults(emptyList()); return
        }

        // Keep the CPU awake during the full recognition cycle (screen may be off).
        acquireRecognitionWakeLock()

        // Mute the recognition-start beep that the system UI plays on STREAM_MUSIC.
        muteRecognitionBeep()

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    // Unmute now — the beep has already played (or been suppressed).
                    unmuteRecognitionBeep()
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    releaseRecognitionWakeLock()
                    val candidates = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()
                    if (candidates.isNotEmpty()) {
                        _recognizedText.value = candidates[0]
                        Log.d(tag, "Results (${candidates.size}): ${candidates.take(3)}")
                        onResults(candidates)
                    } else {
                        retryOrFail(SpeechRecognizer.ERROR_NO_MATCH, onResults, retryCount)
                    }
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    releaseRecognitionWakeLock()
                    unmuteRecognitionBeep()
                    Log.w(tag, "Recognition error $error (retry $retryCount/$MAX_RETRIES)")
                    retryOrFail(error, onResults, retryCount)
                }

                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buf: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(type: Int, params: Bundle?) {}
                override fun onPartialResults(partial: Bundle?) {}
                override fun onRmsChanged(rmsdB: Float) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Wait up to 2 s of silence before finalising.
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2000L)
            // Tentative silence threshold.
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 1200L)
            // Must hear at least 500 ms of speech — prevents spurious instant timeouts.
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 500L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun retryOrFail(
        error: Int,
        onResults: (List<String>) -> Unit,
        retryCount: Int
    ) {
        val retriable = error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        if (retriable && retryCount < MAX_RETRIES) {
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600L else 250L
            mainHandler.postDelayed({
                startListeningOnMainThread(onResults, retryCount + 1)
            }, delay)
        } else {
            Log.e(tag, "Recognition giving up after $retryCount retries (error=$error)")
            onResults(emptyList())
        }
    }

    // ------------------------------------------------------------------
    // Beep suppression (best-effort — some devices ignore stream mutes)
    // ------------------------------------------------------------------

    private fun muteRecognitionBeep() {
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            // Safety net: always unmute within 1 s even if onReadyForSpeech never fires.
            mainHandler.postDelayed(::unmuteRecognitionBeep, 1000)
        }
    }

    private fun unmuteRecognitionBeep() {
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    // ------------------------------------------------------------------
    // Wake lock — holds CPU awake during recognition with screen off
    // ------------------------------------------------------------------

    private fun acquireRecognitionWakeLock() {
        recognitionWakeLock?.let { if (it.isHeld) return }
        recognitionWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceGmail:RecognitionWakeLock"
        ).also { it.acquire(30_000L) } // 30 s max per recognition pass
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
        const val MAX_RETRIES = 2
    }
}
