package com.example.voicegmail.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
                Log.d(tag, "TTS ready — savedEngine=$savedEngine")
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

    /** Package name of the currently active TTS engine, or null for system default. */
    fun getCurrentEngineName(): String? = ttsSettings.getEnginePackage()

    /** Name of the currently active voice, or null if using engine default. */
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
                        Log.d(tag, "Re-init complete — engine=$enginePackage")
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
     * Speaks [prompt] and then opens the mic. Calls [onResults] with the list
     * of recognition hypotheses (highest-confidence first). On repeated errors
     * the recogniser retries silently up to [MAX_RETRIES] times before calling
     * [onResults] with an empty list, which causes the ViewModel's "didn't
     * understand" branch to fire.
     */
    fun speakAndThenListen(prompt: String, onResults: (List<String>) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                Log.w(tag, "TTS not ready — falling back to immediate listen")
                startListeningOnMainThread(onResults)
                return@post
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {}
                override fun onDone(uid: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.post { startListeningOnMainThread(onResults) }
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(uid: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.post { startListeningOnMainThread(onResults) }
                }
            })
            tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun startListening(onResults: (List<String>) -> Unit) {
        mainHandler.post { startListeningOnMainThread(onResults) }
    }

    // ------------------------------------------------------------------
    // Core recognition loop (with retry on no-match / timeout)
    // ------------------------------------------------------------------

    private fun startListeningOnMainThread(
        onResults: (List<String>) -> Unit,
        retryCount: Int = 0
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO not granted")
            onResults(emptyList())
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(tag, "Speech recognition unavailable")
            onResults(emptyList())
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { _isListening.value = true }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
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
                    Log.w(tag, "Recognition error $error (retry $retryCount/${MAX_RETRIES})")
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
            // Up to 5 recognition hypotheses — higher-confidence first.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Prefer on-device recognition for lower latency and better reliability.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Wait up to 2 s of silence before deciding speech has ended.
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2000L)
            // Allow slightly faster finalisation when speech sounds done.
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 1200L)
            // Do not require minimum speech length.
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 0L)
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
            // Silent retry — no spoken prompt so the UX feels seamless.
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600L else 200L
            mainHandler.postDelayed({
                startListeningOnMainThread(onResults, retryCount + 1)
            }, delay)
        } else {
            Log.e(tag, "Recognition giving up after $retryCount retries (error=$error)")
            onResults(emptyList())
        }
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    fun stopListening() {
        mainHandler.post { speechRecognizer?.stopListening(); _isListening.value = false }
    }

    fun stopAll() {
        mainHandler.post { tts?.stop(); speechRecognizer?.stopListening(); _isListening.value = false }
    }

    fun shutdown() {
        mainHandler.post {
            tts?.stop(); tts?.shutdown()
            speechRecognizer?.destroy(); speechRecognizer = null
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
