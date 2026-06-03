package com.example.voicegmail.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.voicegmail.BuildConfig
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Bluetooth SCO audio routing for microphone input from BT headsets.
 *
 * ## Design principles (revised):
 *
 * 1. **Audio mode only changes AFTER SCO connects** — not before. This prevents
 *    the A2DP audio stream from being interrupted while SCO is still negotiating.
 *
 * 2. **SCO failure tracking** — if SCO fails 2+ times consecutively (state 2→0),
 *    skip SCO attempts until the next app restart or successful connection.
 *    This prevents repeated failed attempts that add delay with no benefit.
 *
 * 3. **No mode change when BT disconnected** — if `isBluetoothScoAvailableOffCall`
 *    is false, fire callback immediately without touching audio mode.
 *
 * 4. **TTS always uses A2DP** — SCO is only for mic input, torn down immediately
 *    after recognition completes so TTS plays through the normal media stream.
 */
@Singleton
class BluetoothAudioRouter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "VoiceGmail.BT"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** Pending callback to fire once SCO is confirmed active (or fallback). */
    @Volatile private var pendingCallback: (() -> Unit)? = null

    /** Audio mode saved before we switch to MODE_IN_COMMUNICATION. */
    private var savedAudioMode = AudioManager.MODE_NORMAL

    /** Whether SCO is currently connected. */
    @Volatile private var scoConnected = false

    /** Whether we changed the audio mode (so we know to restore it). */
    private var modeChanged = false

    /** Consecutive SCO failures (state 2→0 without reaching 1). */
    private var consecutiveFailures = 0

    /** If true, skip SCO attempts entirely (too many failures). */
    private var scoDisabled = false

    // ------------------------------------------------------------------
    // SCO state receiver
    // ------------------------------------------------------------------

    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            DebugLogger.verbose(tag, "SCO state update: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    scoConnected = true
                    consecutiveFailures = 0 // Reset failure counter on success
                    scoDisabled = false
                    // NOW change audio mode — only after SCO is confirmed
                    if (!modeChanged) {
                        savedAudioMode = audioManager.mode
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        modeChanged = true
                    }
                    DebugLogger.log(tag, "BT SCO connected ✓")
                    firePendingCallback()
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    scoConnected = false
                    consecutiveFailures++
                    DebugLogger.log(tag, "BT SCO error (failures=$consecutiveFailures) — using fallback mic")
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        scoDisabled = true
                        DebugLogger.log(tag, "BT SCO disabled after $consecutiveFailures consecutive failures")
                    }
                    firePendingCallback()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    val wasConnected = scoConnected
                    scoConnected = false
                    if (!wasConnected && pendingCallback != null) {
                        // SCO went 2→0 without ever reaching 1 — this is a failure
                        consecutiveFailures++
                        DebugLogger.verbose(tag, "BT SCO connect failed (2→0, failures=$consecutiveFailures)")
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            scoDisabled = true
                            DebugLogger.log(tag, "BT SCO disabled after $consecutiveFailures consecutive failures")
                        }
                        firePendingCallback()
                    } else {
                        DebugLogger.verbose(tag, "BT SCO disconnected")
                    }
                }
            }
        }
    }

    init {
        if (BuildConfig.BLUETOOTH_AUDIO) {
            context.registerReceiver(
                scoStateReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )
            DebugLogger.log(tag, "BluetoothAudioRouter initialised (bt flavor)")
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns true when a BT SCO audio path is available.
     */
    @Suppress("DEPRECATION")
    val isScoAvailable: Boolean
        get() = BuildConfig.BLUETOOTH_AUDIO && !scoDisabled &&
                audioManager.isBluetoothScoAvailableOffCall

    /**
     * Ensure the Bluetooth SCO channel is active for microphone use,
     * then invoke [onReady].
     *
     * - Standard build or no BT: fires immediately
     * - SCO disabled (too many failures): fires immediately (uses built-in mic)
     * - SCO already connected: fires immediately
     * - Otherwise: starts SCO, fires on connect or timeout
     */
    fun ensureScoActive(onReady: () -> Unit) {
        if (!BuildConfig.BLUETOOTH_AUDIO) { onReady(); return }

        if (scoDisabled) {
            DebugLogger.verbose(tag, "ensureScoActive: SCO disabled (too many failures) — using built-in mic")
            onReady(); return
        }

        @Suppress("DEPRECATION")
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            DebugLogger.verbose(tag, "ensureScoActive: no BT headset available — using built-in mic")
            onReady(); return
        }

        if (scoConnected) {
            DebugLogger.verbose(tag, "ensureScoActive: SCO already active")
            onReady(); return
        }

        DebugLogger.verbose(tag, "Starting BT SCO for mic…")
        pendingCallback = onReady

        // Do NOT change audio mode here — wait until SCO actually connects
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()

        // Safety net: proceed after timeout even if SCO never connects
        mainHandler.postDelayed({
            if (pendingCallback != null) {
                DebugLogger.log(tag, "BT SCO connect timed out — using fallback mic")
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    scoDisabled = true
                    DebugLogger.log(tag, "BT SCO disabled after timeout (failures=$consecutiveFailures)")
                }
                firePendingCallback()
            }
        }, SCO_CONNECT_TIMEOUT_MS)
    }

    /**
     * Tear down SCO after mic use. Restores audio mode so TTS uses A2DP.
     */
    @Suppress("DEPRECATION")
    fun stopSco() {
        if (!BuildConfig.BLUETOOTH_AUDIO) return
        pendingCallback = null
        if (scoConnected || audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
        }
        if (modeChanged) {
            audioManager.mode = savedAudioMode
            modeChanged = false
            DebugLogger.verbose(tag, "BT SCO stopped; audio mode restored to $savedAudioMode")
        }
        scoConnected = false
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun firePendingCallback() {
        val cb = pendingCallback ?: return
        pendingCallback = null
        mainHandler.post(cb)
    }

    private companion object {
        const val SCO_CONNECT_TIMEOUT_MS = 2_500L
        /** After this many consecutive failures, disable SCO until next restart. */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
