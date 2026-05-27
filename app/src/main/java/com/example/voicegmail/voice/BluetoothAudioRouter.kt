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
 * ## Why keep SCO "hot"
 * Starting a Bluetooth SCO channel takes 1–3 seconds because the Android
 * Bluetooth stack must negotiate the synchronous link with the headset. If we
 * started and stopped SCO around every microphone session the user would
 * experience an audible gap before each listening window. Instead, once SCO
 * connects we leave it open for the entire voice session. Call [stopSco] only
 * when the app goes to sleep ([VoiceCommand.SessionTimeout]) or shuts down.
 *
 * ## Audio quality trade-off
 * While SCO is active the TTS output is also routed through the HFP channel
 * (narrowband voice quality, ~8 kHz). This is acceptable for voice prompts.
 * When SCO is off the audio route falls back to A2DP (stereo quality).
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

    // ------------------------------------------------------------------
    // SCO state receiver
    // ------------------------------------------------------------------

    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.d(tag, "SCO state update: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Log.i(tag, "BT SCO connected ✓")
                    firePendingCallback()
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    // Headset present but SCO negotiation failed.
                    // Fire anyway — the recogniser will fall back to built-in mic.
                    Log.w(tag, "BT SCO error — proceeding with fallback mic")
                    firePendingCallback()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    Log.d(tag, "BT SCO disconnected")
                    pendingCallback = null
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
            Log.d(tag, "BluetoothAudioRouter initialised (bt flavor)")
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
     * Ensure the Bluetooth SCO channel is active, then invoke [onReady].
     *
     * Behaviour:
     * - **Standard build** or **no BT headset connected**: [onReady] fires
     *   immediately on the calling thread.
     * - **SCO already connected**: [onReady] fires immediately.
     * - **SCO not yet connected**: starts the SCO channel and fires [onReady]
     *   once [AudioManager.SCO_AUDIO_STATE_CONNECTED] is received (or after
     *   [SCO_CONNECT_TIMEOUT_MS] as a safety fallback).
     *
     * Must be called on the main thread.
     */
    fun ensureScoActive(onReady: () -> Unit) {
        if (!BuildConfig.BLUETOOTH_AUDIO) { onReady(); return }

        @Suppress("DEPRECATION")
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            // No compatible headset is currently connected.
            onReady(); return
        }

        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoOn) {
            // SCO is already up — nothing to do.
            onReady(); return
        }

        Log.d(tag, "Starting BT SCO…")
        savedAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        pendingCallback = onReady

        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()

        // Safety net: if the BT stack never confirms the connection, proceed
        // after the timeout so the mic opens regardless.
        mainHandler.postDelayed({
            if (pendingCallback != null) {
                Log.w(tag, "BT SCO connect timed out after ${SCO_CONNECT_TIMEOUT_MS} ms — using fallback")
                firePendingCallback()
            }
        }, SCO_CONNECT_TIMEOUT_MS)
    }

    /**
     * Tear down the SCO channel and restore the audio mode that was active
     * before we switched to [AudioManager.MODE_IN_COMMUNICATION].
     *
     * Call this when the app is going to sleep (session timeout) or shutting
     * down — not between individual microphone sessions.
     */
    @Suppress("DEPRECATION")
    fun stopSco() {
        if (!BuildConfig.BLUETOOTH_AUDIO) return
        pendingCallback = null
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.mode = savedAudioMode
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
