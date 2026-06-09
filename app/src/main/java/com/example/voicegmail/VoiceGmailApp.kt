package com.example.voicegmail

import android.app.Application
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VoiceGmailApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Crash handler from MyApp
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                openFileOutput("last_crash.txt", MODE_PRIVATE).use { fos ->
                    fos.write(e.stackTraceToString().toByteArray())
                }
            } catch (_: Exception) {}
            System.exit(2)
        }

        DebugLogger.init(this)
        val prefs = getSharedPreferences("wake_prefs", MODE_PRIVATE)
        DebugLogger.verboseEnabled = prefs.getBoolean("verbose_logging", false)
        DebugLogger.log("App", "App started — verbose=${DebugLogger.verboseEnabled}, bt=${BuildConfig.BLUETOOTH_AUDIO}")
    }
}
