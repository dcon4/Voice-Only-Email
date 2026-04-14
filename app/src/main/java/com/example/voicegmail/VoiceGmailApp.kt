package com.example.voicegmail

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Hilt dependency injection.
 * Referenced in AndroidManifest.xml as android:name=".VoiceGmailApp".
 */
@HiltAndroidApp
class VoiceGmailApp : Application()
