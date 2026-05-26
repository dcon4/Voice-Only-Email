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
    @ApplicationContext private val context: Context
) {
    private val tag = "VoiceManager"
    private val utteranceId = "vm_utterance"

    // All SpeechRecognizer interactions MUST happen on the main thread.
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // SpeechRecognizer is created once and reused to avoid recreation cost.
    // It must only be touched from the main thread.
    private var speechRecognizer: SpeechRecognizer? = null

    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    init {
        // TTS can be initialised from any thread; the callback also arrives on
        // the main thread in practice, but we post back to be safe.
        mainHandler.post { initTts() }
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            } else {
                Log.e(tag, "TTS initialization failed: $status")
            }
        }
    }

    fun speak(text: String) {
        mainHandler.post {
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(null)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
    }

    /**
     * Speaks [prompt] via TTS, then automatically starts listening once speech
     * completes. The recognised text is delivered to [onResult].
     *
     * Both TTS and SpeechRecognizer are driven on the main thread so Android
     * does not throw a "SpeechRecognizer must be created on the main thread"
     * exception.
     */
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
                    // The UtteranceProgressListener callback can arrive on a
                    // background thread — post to main to keep SpeechRecognizer happy.
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

    fun startListening(onResult: (String) -> Unit) {
        mainHandler.post { startListeningOnMainThread(onResult) }
    }

    /**
     * Internal helper — MUST be called from the main thread.
     */
    private fun startListeningOnMainThread(onResult: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO permission not granted; cannot start listening")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(tag, "Speech recognition not available")
            return
        }

        // Recreate recogniser each time — reusing a stopped recogniser causes
        // ERROR_RECOGNIZER_BUSY on some devices.
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }
                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: return
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

    /** Stops both TTS and speech recognition immediately. */
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
