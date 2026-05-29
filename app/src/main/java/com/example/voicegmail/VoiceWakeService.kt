package com.example.voicegmail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that:
 *  1. Monitors [Intent.ACTION_SCREEN_ON] and brings [MainActivity] to the front
 *     when the power button is pressed, enabling fully hands-free control for a
 *     totally blind user.
 *  2. Declares [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] so the whole
 *     process is allowed to access the microphone even when the screen is off
 *     and the app is not in the foreground (Android 10+ policy requirement).
 *  3. Holds a [PowerManager.PARTIAL_WAKE_LOCK] so the CPU stays awake with the
 *     screen off, letting TTS speak and SpeechRecognizer listen without the
 *     device going to deep sleep between commands.
 *
 * The service is started by [MainActivity] on first launch and is declared
 * START_STICKY so Android restarts it automatically after resource trimming.
 */
@AndroidEntryPoint
class VoiceWakeService : Service() {

    @Inject lateinit var wakeEventBus: WakeEventBus
    @Inject lateinit var wakePreferences: com.example.voicegmail.voice.WakePreferences

    private var wakeLock: PowerManager.WakeLock? = null

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                if (!wakePreferences.isRunInBackground()) {
                    DebugLogger.log("WakeService", "Screen on — ignored (foreground-only mode)")
                    return
                }
                DebugLogger.log("WakeService", "Screen on — waking app")
                wakeApp()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Acquire PARTIAL_WAKE_LOCK — keeps the CPU alive when the screen turns
        // off so the voice loop (TTS → mic → command → TTS …) can continue
        // uninterrupted without requiring the screen to stay on.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock?.acquire() // indefinite; released in onDestroy

        // Declare the microphone foreground service type on API 29+ so the OS
        // grants this process microphone access while in the background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        DebugLogger.log("WakeService", "Started (PARTIAL_WAKE_LOCK + MICROPHONE FGS type)")
    }

    private fun wakeApp() {
        // Signal InboxViewModel so it re-arms the microphone immediately.
        wakeEventBus.postWake()
        // Bring MainActivity to the front. Because MainActivity declares
        // setShowWhenLocked(true) + setTurnScreenOn(true), it appears over the
        // lock screen without requiring the user to unlock the device.
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SCREEN_WAKE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOnReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        DebugLogger.log("WakeService", "Destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VoiceGmail",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps VoiceGmail ready to listen — press power button to wake"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceGmail")
            .setContentText("Listening in background — press power button to wake")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID       = "voicegmail_wake"
        const val NOTIFICATION_ID  = 1001
        const val WAKE_LOCK_TAG    = "VoiceGmail:WakeServiceLock"
        const val ACTION_SCREEN_WAKE = "com.example.voicegmail.SCREEN_WAKE"

        fun start(context: Context) =
            context.startForegroundService(Intent(context, VoiceWakeService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, VoiceWakeService::class.java))
    }
}
