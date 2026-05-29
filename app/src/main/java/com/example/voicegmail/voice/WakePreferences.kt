package com.example.voicegmail.voice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the "run in background" toggle.
 *
 * - **true** (default): VoiceWakeService starts on launch and responds to
 *   SCREEN_ON events — the app wakes and listens whenever the power button
 *   is pressed, even with the screen off. Ideal for a blind user.
 *
 * - **false**: VoiceWakeService does not start (or is stopped). The app
 *   only speaks/listens when it is the foreground activity. Ideal for a
 *   sighted user who wants VoiceGmail installed but not always active.
 */
@Singleton
class WakePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wake_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RUN_IN_BACKGROUND = "run_in_background"
    }

    /** Whether the app should run in background mode (wake on power button). Default: true. */
    fun isRunInBackground(): Boolean =
        prefs.getBoolean(KEY_RUN_IN_BACKGROUND, true)

    fun setRunInBackground(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RUN_IN_BACKGROUND, enabled).apply()
    }
}
