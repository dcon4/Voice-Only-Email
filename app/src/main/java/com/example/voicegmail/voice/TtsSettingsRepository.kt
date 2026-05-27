package com.example.voicegmail.voice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)

    fun getEnginePackage(): String? = prefs.getString("engine_package", null)
    fun getVoiceName(): String?     = prefs.getString("voice_name", null)
    fun getEmailReadRate(): Float   = prefs.getFloat("email_read_rate", 1.0f)

    fun saveEnginePackage(pkg: String) {
        prefs.edit().putString("engine_package", pkg).apply()
    }

    fun saveVoiceName(name: String) {
        prefs.edit().putString("voice_name", name).apply()
    }

    fun clearVoiceName() {
        prefs.edit().remove("voice_name").apply()
    }

    fun saveEmailReadRate(rate: Float) {
        prefs.edit().putFloat("email_read_rate", rate).apply()
    }
}
