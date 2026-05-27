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
                Log.d(tag, "TTS ready — engine=${tts?.defaultEngine} savedEngine=$savedEngine")
            } else {
                Log.e(tag, "TTS initialization failed: $status")
            }
        }
        tts = if (savedEngine != null) {
            TextToSpeech(context, listener, savedEngine)
        } else {
            TextToSpeech(context, listener)
        }
    }

    /** Apply the saved voice preference after TTS has finished initialising. */
    private fun applyVoicePreference() {
        val voiceName = ttsSettings.getVoiceName() ?: return
        val voice = tts?.voices?.find { it.name == voiceName }
        if (voice != null) {
            tts?.voice = voice
            Log.d(tag, "Applied saved voice: $voiceName")
        } else {
            Log.w(tag, "Saved voice '$voiceName' not found in current engine; clearing preference")
            ttsSettings.clearVoiceName()
        }
    }

    // ------------------------------------------------------------------
    // Engine & voice switching (called from InboxViewModel voice-settings flow)
    // ------------------------------------------------------------------

    /** Returns all installed TTS engines, sorted by label. */
    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> =
        tts?.engines?.sortedBy { it.label } ?: emptyList()

    /**
     * Returns available voices for the current engine, offline voices first
     * and sorted by display name.
     */
    fun getAvailableVoices(): List<Voice> {
        val voices = tts?.voices?.toList() ?: return emptyList()
        return voices.sortedWith(
            compareBy({ it.isNetworkConnectionRequired }, { it.locale.displayLanguage }, { it.name })
        )
    }

    /**
     * Sets and persists a voice by name. Returns true on success.
     * Must be called from any thread — posts to main thread internally.
     */
    fun setVoiceByName(name: String) {
        mainHandler.post {
            val voice = tts?.voices?.find { it.name == name }
            if (voice != null) {
                tts?.voice = voice
                ttsSettings.saveVoiceName(name)
                Log.d(tag, "Voice set: $name")
            } else {
                Log.w(tag, "Voice not found: $name")
            }
        }
    }

    /**
     * Resets to the engine's default voice and clears the saved preference.
     */
    fun clearVoicePreference() {
        ttsSettings.clearVoiceName()
        mainHandler.post {
            if (ttsReady) tts?.language = Locale.US  // resets to best voice for locale
        }
    }

    /**
     * Reinitialises TTS with the given engine package and calls [onReady]
     * on the main thread once the new engine is ready to speak. Falls back
     * to the default engine if re-init fails.
     */
    fun reinitWithEngine(enginePackage: String, onReady: () -> Unit) {
        mainHandler.post {
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
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
                        Log.e(tag, "Re-init failed for $enginePackage ($status); falling back to default")
                        initTts()
                    }
                }
            }
            tts = TextToSpeech(context, listener, enginePackage)
        }
    }

    /**
     * Returns a human-readable description of a [Voice] suitable for TTS
     * announcement — e.g. "English United Kingdom female".
     */
    fun friendlyVoiceName(voice: Voice): String {
        val locale  = voice.locale
        val lang    = locale.displayLanguage
        val country = if (locale.country.isNotEmpty()) " ${locale.displayCountry}" else ""
        val nameLow = voice.name.lowercase()
        val gender  = when {
            nameLow.contains("female") || nameLow.contains("-f-") ||
                nameLow.endsWith("-f")  -> " female"
            nameLow.contains("male") || nameLow.contains("-m-") ||
                nameLow.endsWith("-m")  -> " male"
            else                        -> ""
        }
        val network = if (voice.isNetworkConnectionRequired) " online" else ""
        return "$lang$country$gender$network".trim()
    }

    // ------------------------------------------------------------------
    // Speech output
    // ------------------------------------------------------------------

    fun speak(text: String) {
        mainHandler.post {
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(null)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
    }

    fun speakAndThenListen(prompt: String, onResult: (String) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                Log.w(tag, "TTS not ready; falling back to immediate listen")
                startListeningOnMainThread(onResult)
                return@post
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.post { startListeningOnMainThread(onResult) }
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String?) {
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.post { startListeningOnMainThread(onResult) }
                }
            })
            tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    // ------------------------------------------------------------------
    // Speech recognition
    // ------------------------------------------------------------------

    fun startListening(onResult: (String) -> Unit) {
        mainHandler.post { startListeningOnMainThread(onResult) }
    }

    private fun startListeningOnMainThread(onResult: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO not granted; cannot listen")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(tag, "Speech recognition unavailable")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { _isListening.value = true }
                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    _recognizedText.value = text
                    onResult(text)
                }
                override fun onError(error: Int) {
                    _isListening.value = false
                    Log.e(tag, "Speech recognition error: $error")
                }
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onRmsChanged(rmsdB: Float) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        mainHandler.post {
            speechRecognizer?.stopListening()
            _isListening.value = false
        }
    }

    fun stopAll() {
        mainHandler.post {
            tts?.stop()
            speechRecognizer?.stopListening()
            _isListening.value = false
        }
    }

    fun shutdown() {
        mainHandler.post {
            tts?.stop()
            tts?.shutdown()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }
}
