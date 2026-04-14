package com.example.voicegmail.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android TextToSpeech for reading email content aloud.
 * Automatically initializes TTS on construction and releases on [shutdown].
 */
@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var speechRate: Float = 1.0f
    private val pendingQueue = mutableListOf<String>()
    private val lock = Any()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        synchronized(lock) {
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(speechRate)
                isReady = true
                // Drain any queued utterances
                val queued = pendingQueue.toList()
                pendingQueue.clear()
                queued.forEach { speak(it) }
            }
        }
    }

    /** Speak [text] aloud. If TTS is not yet ready, the text is queued. */
    fun speak(text: String) {
        synchronized(lock) {
            if (!isReady) {
                pendingQueue += text
                return
            }
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    /** Interrupt and stop current speech, then speak [text]. */
    fun speakNow(text: String) {
        synchronized(lock) {
            if (!isReady) {
                pendingQueue.clear()
                pendingQueue += text
                return
            }
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    /** Stop current speech. */
    fun stopSpeaking() {
        tts?.stop()
    }

    /** Update speech rate (range 0.5 – 2.0). */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }

    fun getSpeechRate(): Float = speechRate

    /**
     * Register a callback to be invoked when an utterance finishes.
     * Only one listener is active at a time.
     */
    fun setOnDoneListener(onDone: () -> Unit) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = onDone()
            override fun onError(utteranceId: String) = onDone()
        })
    }

    /** Release TTS resources. Call from the Activity/Application onDestroy. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
