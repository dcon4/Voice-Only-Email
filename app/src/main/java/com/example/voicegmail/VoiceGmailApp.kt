package com.example.voicegmail

import android.app.Application
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VoiceGmailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
        // Load verbose logging preference
        val prefs = getSharedPreferences("wake_prefs", MODE_PRIVATE)
        DebugLogger.verboseEnabled = prefs.getBoolean("verbose_logging", false)
        DebugLogger.log("App", "App started — verbose=${DebugLogger.verboseEnabled}, bt=${BuildConfig.BLUETOOTH_AUDIO}")
    }
}
