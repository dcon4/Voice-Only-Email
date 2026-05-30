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
 * Manages Bluetooth SCO (Synchronous Connection-Oriented) audio routing so
 * that voice input comes from a paired Bluetooth headset microphone rather
 * than the phone's built-in mic.
 *
 * Active only in the **bt** product-flavor build ([BuildConfig.BLUETOOTH_AUDIO]
 * == true). In the **standard** flavor every method is a no-op and
 * [ensureScoActive] fires its callback immediately with no side effects.
 *
 * ## Revised approach: SCO only around mic sessions
 *
 * The previous approach kept SCO "hot" for the entire voice session, but this
 * caused problems with Bluetooth hearing aids and headsets:
 * - TTS spoke via SCO (narrowband, poor quality) instead of A2DP (wideband)
 * - Audio stream flipped between A2DP and SCO repeatedly, causing audible
 *   connect/disconnect artifacts (bells, pops, silence gaps)
 * - Some devices immediately disconnected SCO (state 2 → 0)
 *
 * New approach:
 * - TTS always plays via the normal media stream (A2DP if BT connected)
 * - SCO is activated ONLY when the mic needs to open
 * - SCO is deactivated IMMEDIATELY after recognition completes
 * - This eliminates the A2DP↔SCO flip-flop that caused audio gaps
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

    /** Pending callback to fire once SCO is confirmed active. */
    @Volatile private var pendingCallback: (() -> Unit)? = null

    /** Audio mode saved before we switch to MODE_IN_COMMUNICATION. */
    private var savedAudioMode = AudioManager.MODE_NORMAL

    /** Whether SCO is currently connected. */
    @Volatile private var scoConnected = false

    // ------------------------------------------------------------------
    // SCO state receiver
    // ------------------------------------------------------------------

    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            DebugLogger.verbose(tag, "SCO state update: $state")
            Log.d(tag, "SCO state update: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    scoConnected = true
                    DebugLogger.log(tag, "BT SCO connected ✓")
                    Log.i(tag, "BT SCO connected ✓")
                    firePendingCallback()
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    scoConnected = false
                    DebugLogger.log(tag, "BT SCO error — proceeding with fallback mic")
                    Log.w(tag, "BT SCO error — proceeding with fallback mic")
                    firePendingCallback()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    scoConnected = false
                    DebugLogger.verbose(tag, "BT SCO disconnected")
                    Log.d(tag, "BT SCO disconnected")
                    // If we were waiting for a connection and it disconnected,
                    // fire the callback anyway so the mic opens with built-in mic
                    firePendingCallback()
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
     * Returns true when the device reports that a Bluetooth SCO audio path
     * is available (i.e. a headset supporting SCO is currently connected).
     */
    @Suppress("DEPRECATION")
    val isScoAvailable: Boolean
        get() = BuildConfig.BLUETOOTH_AUDIO && audioManager.isBluetoothScoAvailableOffCall

    /**
     * Ensure the Bluetooth SCO channel is active for microphone use,
     * then invoke [onReady].
     *
     * Behaviour:
     * - **Standard build** or **no BT headset connected**: [onReady] fires
     *   immediately on the calling thread.
     * - **SCO already connected**: [onReady] fires immediately.
     * - **SCO not yet connected**: starts the SCO channel and fires [onReady]
     *   once connected (or after timeout as fallback).
     *
     * Must be called on the main thread.
     */
    fun ensureScoActive(onReady: () -> Unit) {
        if (!BuildConfig.BLUETOOTH_AUDIO) { onReady(); return }

        @Suppress("DEPRECATION")
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            DebugLogger.verbose(tag, "ensureScoActive: no BT headset available — using built-in mic")
            onReady(); return
        }

        if (scoConnected) {
            DebugLogger.verbose(tag, "ensureScoActive: SCO already active")
            onReady(); return
        }

        DebugLogger.log(tag, "Starting BT SCO for mic…")
        Log.d(tag, "Starting BT SCO for mic…")
        savedAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        pendingCallback = onReady

        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()

        // Safety net: if the BT stack never confirms the connection, proceed
        // after the timeout so the mic opens regardless (using built-in mic).
        mainHandler.postDelayed({
            if (pendingCallback != null) {
                DebugLogger.log(tag, "BT SCO connect timed out after ${SCO_CONNECT_TIMEOUT_MS}ms — using fallback")
                Log.w(tag, "BT SCO connect timed out — using fallback mic")
                firePendingCallback()
            }
        }, SCO_CONNECT_TIMEOUT_MS)
    }

    /**
     * Tear down the SCO channel immediately after mic use is complete.
     * This allows audio to return to A2DP (wideband media quality) for TTS.
     *
     * Called after every recognition session completes (success, error, or timeout).
     */
    @Suppress("DEPRECATION")
    fun stopSco() {
        if (!BuildConfig.BLUETOOTH_AUDIO) return
        pendingCallback = null
        if (scoConnected || audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.mode = savedAudioMode
            scoConnected = false
            DebugLogger.verbose(tag, "BT SCO stopped after mic use; audio mode restored to $savedAudioMode")
            Log.d(tag, "BT SCO stopped; audio mode restored to $savedAudioMode")
        }
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
        /** Maximum time to wait for SCO_AUDIO_STATE_CONNECTED before giving up. */
        const val SCO_CONNECT_TIMEOUT_MS = 3_000L
    }
}
