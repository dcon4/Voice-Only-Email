package com.example.voicegmail

import android.app.Application
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VoiceGmailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
        DebugLogger.log("App", "App started")
    }
}
