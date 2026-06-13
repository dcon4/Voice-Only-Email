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

    fun getBibleVoiceName(): String = prefs.getString("bible_voice_name", "") ?: ""

    fun saveBibleVoiceName(name: String) {
        prefs.edit().putString("bible_voice_name", name).apply()
    }

    fun clearBibleVoiceName() {
        prefs.edit().remove("bible_voice_name").apply()
    }

    // ── Bible engine (separate TTS engine for Bible reading) ────────────────

    fun getBibleEnginePackage(): String? = prefs.getString("bible_engine_package", null)

    fun saveBibleEnginePackage(pkg: String) {
        prefs.edit().putString("bible_engine_package", pkg).apply()
    }

    fun clearBibleEnginePackage() {
        prefs.edit().remove("bible_engine_package").apply()
    }

    /**
     * Crash-safe temporary store for the main engine while Bible engine is in
     * use.  Written before switching to the Bible engine; read back and cleared
     * on app startup if the previous session crashed mid-Bible-reading.
     */
    fun getSavedMainEnginePackage(): String? = prefs.getString("saved_main_engine", null)

    fun saveMainEnginePackage(pkg: String) {
        prefs.edit().putString("saved_main_engine", pkg).apply()
    }

    fun clearSavedMainEngine() {
        prefs.edit().remove("saved_main_engine").apply()
    }

    fun saveEmailReadRate(rate: Float) {
        prefs.edit().putFloat("email_read_rate", rate).apply()
    }
}
