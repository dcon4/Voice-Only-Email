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
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsSettings: TtsSettingsRepository,
    /**
     * Manages Bluetooth SCO routing in the "bt" product-flavor build.
     * In the "standard" build every method is a no-op so injection is always safe.
     */
    private val bluetoothRouter: BluetoothAudioRouter
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

    private var emailReadRate: Float = ttsSettings.getEmailReadRate()

    init {
        mainHandler.post { initTts() }
    }

    // ------------------------------------------------------------------
    // TTS initialisation
    // ------------------------------------------------------------------

    private fun initTts() {
        val savedEngine = ttsSettings.getEnginePackage()
        DebugLogger.log(tag, "initTts — savedEngine=$savedEngine")
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                applyVoicePreference()

                // Only set language explicitly if the engine exposes voices.
                // Engines like IVONA manage their own voice/language internally
                // and setting language can interfere with their default behavior.
                val availableVoices = tts?.voices
                if (!availableVoices.isNullOrEmpty()) {
                    tts?.language = Locale.US
                    // If no voice was applied (no saved preference) and TTS has no
                    // default voice selected, pick the first available English voice.
                    if (tts?.voice == null) {
                        val fallbackVoice = availableVoices
                            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                            .minByOrNull { it.name }
                        if (fallbackVoice != null) {
                            tts?.voice = fallbackVoice
                            DebugLogger.log(tag, "No voice set — auto-selected: ${fallbackVoice.name}")
                        }
                    }
                } else {
                    DebugLogger.log(tag, "Engine reports 0 voices — using engine's internal default")
                }

                val engineName = tts?.defaultEngine
                val voiceCount = availableVoices?.size ?: 0
                val allEngines = tts?.engines?.map { "${it.label} (${it.name})" } ?: emptyList()
                DebugLogger.log(tag, "TTS ready — defaultEngine=$engineName, activeVoice=${tts?.voice?.name}, voices=$voiceCount, allEngines=$allEngines")
            } else {
                DebugLogger.log(tag, "TTS initialization FAILED: status=$status")
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

    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> {
        val engines = tts?.engines?.sortedBy { it.label } ?: emptyList()
        DebugLogger.log(tag, "getAvailableEngines: ${engines.map { "${it.label}(${it.name})" }}")
        return engines
    }

    fun getAvailableVoices(): List<Voice> {
        val voices = tts?.voices?.toList()?.sortedWith(
            compareBy({ it.isNetworkConnectionRequired }, { it.locale.displayLanguage }, { it.name })
        ) ?: emptyList()
        DebugLogger.log(tag, "getAvailableVoices: ${voices.size} voices" +
            if (voices.isNotEmpty()) " (first: ${voices[0].name})" else "")
        return voices
    }

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

    fun getEmailReadRatePct(): Int = (emailReadRate * 100).toInt()

    fun adjustEmailReadRate(delta: Float) {
        emailReadRate = (emailReadRate + delta).coerceIn(0.25f, 3.0f)
        ttsSettings.saveEmailReadRate(emailReadRate)
        Log.d(tag, "Email read rate -> ${getEmailReadRatePct()}%")
    }

    // ------------------------------------------------------------------
    // Phonetic correction
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
                DebugLogger.verbose(tag, "speak: ${text.take(80)}")
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(null)
                tts?.speak(phoneticize(text), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
    }

    fun speakAndThenListen(prompt: String, onResults: (List<String>) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                startListeningOnMainThread(onResults); return@post
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

    fun speakEmailAndThenListen(text: String, onResults: (List<String>) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                startListeningOnMainThread(onResults); return@post
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

    /**
     * Speak one sentence/chunk of an email at email reading rate, then open a
     * *brief* listening window ([noSpeechMaxRetries] = 0).  Silence returns
     * an empty list immediately so the caller can advance to the next chunk
     * without the full session-timeout delay.
     */
    fun speakEmailSentenceAndThenListen(text: String, onResults: (List<String>) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                startListeningOnMainThread(onResults, 0, 0, 0); return@post
            }
            tts?.setSpeechRate(emailReadRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {}
                override fun onDone(uid: String?) {
                    tts?.setSpeechRate(1.0f)
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.postDelayed({
                        startListeningOnMainThread(onResults, 0, 0, 0)
                    }, TTS_TO_MIC_GAP_MS)
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(uid: String?) {
                    tts?.setSpeechRate(1.0f)
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.postDelayed({
                        startListeningOnMainThread(onResults, 0, 0, 0)
                    }, TTS_TO_MIC_GAP_MS)
                }
            })
            tts?.speak(phoneticize(text), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    // ------------------------------------------------------------------
    // TTS output — voice audition (always rate 1.0)
    // ------------------------------------------------------------------

    fun speakWithVoiceAndThenListen(text: String, voice: Voice, onResults: (List<String>) -> Unit) {
        mainHandler.post {
            if (!ttsReady) {
                startListeningOnMainThread(onResults); return@post
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
    // Recognition — entry point (permission + BT SCO gate)
    // ------------------------------------------------------------------

    /**
     * Checks permissions/availability, ensures Bluetooth SCO is active (in the
     * bt build), then delegates to [doStartRecognizer].
     *
     * [noSpeechMaxRetries] controls how many times the recognizer retries on
     * silence before giving up.  Pass 0 for a brief "between-chunk" listen that
     * returns an empty list immediately on silence instead of SESSION_TIMEOUT.
     */
    private fun startListeningOnMainThread(
        onResults: (List<String>) -> Unit,
        retryCount: Int = 0,
        noSpeechRetries: Int = 0,
        noSpeechMaxRetries: Int = NO_SPEECH_MAX_RETRIES
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO not granted"); onResults(emptyList()); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(tag, "Speech recognition unavailable"); onResults(emptyList()); return
        }

        bluetoothRouter.ensureScoActive {
            DebugLogger.verbose(tag, "Mic starting (retry=$retryCount noSpeech=$noSpeechRetries max=$noSpeechMaxRetries)")
            doStartRecognizer(onResults, retryCount, noSpeechRetries, noSpeechMaxRetries)
        }
    }

    // ------------------------------------------------------------------
    // Recognition — core recogniser loop
    // ------------------------------------------------------------------

    private fun doStartRecognizer(
        onResults: (List<String>) -> Unit,
        retryCount: Int = 0,
        noSpeechRetries: Int = 0,
        noSpeechMaxRetries: Int = NO_SPEECH_MAX_RETRIES
    ) {
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
                    Log.d(tag, "Speech began (retry=$retryCount noSpeech=$noSpeechRetries max=$noSpeechMaxRetries)")
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
                            DebugLogger.verbose(tag, "Recognition results (${candidates.size}): ${candidates.take(3)}")
                            Log.d(tag, "Results (${candidates.size}): ${candidates.take(3)}")
                            bluetoothRouter.stopSco() // Release SCO so TTS uses A2DP
                            onResults(candidates)
                        }
                        !speechBegan && noSpeechRetries < noSpeechMaxRetries -> {
                            Log.d(tag, "No speech — silent retry ${noSpeechRetries + 1}/$noSpeechMaxRetries")
                            mainHandler.postDelayed({
                                startListeningOnMainThread(onResults, retryCount, noSpeechRetries + 1, noSpeechMaxRetries)
                            }, NO_SPEECH_RETRY_DELAY_MS)
                        }
                        !speechBegan -> {
                            if (noSpeechMaxRetries == 0) {
                                Log.d(tag, "Brief listen: no speech — returning empty (continue reading)")
                                bluetoothRouter.stopSco()
                                onResults(emptyList())
                            } else {
                                Log.d(tag, "Session timeout after $noSpeechMaxRetries no-speech retries")
                                bluetoothRouter.stopSco()
                                onResults(listOf("SESSION_TIMEOUT"))
                            }
                        }
                        else -> retryOrFail(SpeechRecognizer.ERROR_NO_MATCH, onResults, retryCount, noSpeechMaxRetries)
                    }
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    releaseRecognitionWakeLock()
                    unmuteRecognitionBeep()
                    Log.w(tag, "Recognition error $error speechBegan=$speechBegan retry=$retryCount noSpeech=$noSpeechRetries max=$noSpeechMaxRetries")

                    when {
                        !speechBegan && noSpeechRetries < noSpeechMaxRetries -> {
                            mainHandler.postDelayed({
                                startListeningOnMainThread(onResults, retryCount, noSpeechRetries + 1, noSpeechMaxRetries)
                            }, NO_SPEECH_RETRY_DELAY_MS)
                        }
                        !speechBegan -> {
                            if (noSpeechMaxRetries == 0) {
                                Log.d(tag, "Brief listen error: no speech — returning empty (continue reading)")
                                bluetoothRouter.stopSco()
                                onResults(emptyList())
                            } else {
                                Log.d(tag, "Session timeout (error path) after $noSpeechMaxRetries no-speech retries")
                                bluetoothRouter.stopSco()
                                onResults(listOf("SESSION_TIMEOUT"))
                            }
                        }
                        else -> retryOrFail(error, onResults, retryCount, noSpeechMaxRetries)
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

    private fun retryOrFail(
        error: Int,
        onResults: (List<String>) -> Unit,
        retryCount: Int,
        noSpeechMaxRetries: Int = NO_SPEECH_MAX_RETRIES
    ) {
        val retriable = error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        if (retriable && retryCount < MAX_RETRIES) {
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600L else 300L
            Log.d(tag, "Retrying recognition ($retryCount -> ${retryCount + 1})")
            mainHandler.postDelayed({
                startListeningOnMainThread(onResults, retryCount + 1, 0, noSpeechMaxRetries)
            }, delay)
        } else {
            Log.e(tag, "Recognition giving up (error=$error retries=$retryCount)")
            bluetoothRouter.stopSco()
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

    /**
     * Silently destroy the current recognizer session without firing any
     * error or result callbacks.  Use this when a higher-priority event
     * (e.g. power-button wake) needs to discard whatever recognition was in
     * flight — [stopListening] fires ERROR_CLIENT which triggers retries and
     * can interfere with the new session.
     */
    /**
     * Speak one email chunk at [emailReadRate] then invoke [onDone] — no mic
     * session is opened between chunks, so there is no recognition beep or
     * initialisation pause.  The power button wake-event is the only interrupt
     * path during reading.
     *
     * Safe against QUEUE_FLUSH races: if [onDone] fires after the utterance was
     * flushed by a new [speakAndThenListen] call, the ViewModel's generation
     * counter in [readNextChunk] will discard the stale callback before it can
     * advance the chunk index.
     */
    fun speakEmailChunk(text: String, onDone: () -> Unit) {
        mainHandler.post {
            if (!ttsReady) { mainHandler.post(onDone); return@post }
            val uid = System.nanoTime().toString()
            tts?.setSpeechRate(emailReadRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(u: String?) {}
                override fun onDone(u: String?) {
                    tts?.setSpeechRate(1.0f)
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.post(onDone)
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(u: String?) {
                    tts?.setSpeechRate(1.0f)
                    tts?.setOnUtteranceProgressListener(null)
                    mainHandler.post(onDone)
                }
            })
            tts?.speak(phoneticize(text), TextToSpeech.QUEUE_FLUSH, null, uid)
        }
    }

    fun cancelListening() {
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
            _isListening.value = false
            unmuteRecognitionBeep()
        }
    }

    fun stopListening() {
        mainHandler.post { speechRecognizer?.stopListening(); _isListening.value = false }
    }

    /**
     * Stop all audio (TTS + mic). Also tears down the BT SCO channel so audio
     * routes back to A2DP (better quality output) while the app is idle.
     */
    fun stopAll() {
        mainHandler.post {
            tts?.stop()
            tts?.setSpeechRate(1.0f)
            speechRecognizer?.stopListening()
            _isListening.value = false
            unmuteRecognitionBeep()
            bluetoothRouter.stopSco()
        }
    }

    fun shutdown() {
        mainHandler.post {
            tts?.stop(); tts?.shutdown()
            speechRecognizer?.destroy(); speechRecognizer = null
            releaseRecognitionWakeLock()
            bluetoothRouter.stopSco()
        }
    }

    private companion object {
        const val TTS_TO_MIC_GAP_MS        = 1200L
        const val MAX_RETRIES              = 3
        const val NO_SPEECH_MAX_RETRIES    = 4
        const val NO_SPEECH_RETRY_DELAY_MS = 250L
    }
}
