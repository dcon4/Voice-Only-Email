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
import android.os.IBinder
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that monitors [Intent.ACTION_SCREEN_ON] and brings
 * [MainActivity] to the front whenever the screen wakes, enabling a totally
 * blind user to control the app by pressing the power button alone.
 *
 * The service is started by [MainActivity] on first launch and is declared
 * START_STICKY so Android restarts it automatically after resource trimming.
 */
@AndroidEntryPoint
class VoiceWakeService : Service() {

    @Inject lateinit var wakeEventBus: WakeEventBus

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                DebugLogger.log("WakeService", "Screen on — waking app")
                wakeApp()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        DebugLogger.log("WakeService", "Started")
    }

    private fun wakeApp() {
        // Signal the ViewModel so it re-arms the microphone.
        wakeEventBus.postWake()
        // Bring MainActivity to the front. Because MainActivity declares
        // setShowWhenLocked(true) + setTurnScreenOn(true), it appears over the
        // lock screen without requiring the user to unlock.
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SCREEN_WAKE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOnReceiver) }
        DebugLogger.log("WakeService", "Destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VoiceGmail Wake",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps VoiceGmail ready to listen when the screen wakes"
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
            .setContentText("Press power button to start listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "voicegmail_wake"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SCREEN_WAKE = "com.example.voicegmail.SCREEN_WAKE"

        fun start(context: Context) =
            context.startForegroundService(Intent(context, VoiceWakeService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, VoiceWakeService::class.java))
    }
}
