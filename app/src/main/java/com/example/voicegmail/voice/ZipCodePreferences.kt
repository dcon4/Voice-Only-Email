package com.example.voicegmail.voice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the user's USA zip code for time/weather commands. */
@Singleton
class ZipCodePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("time_weather_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ZIP_CODE = "zip_code"
    }

    fun getZipCode(): String =
        prefs.getString(KEY_ZIP_CODE, "") ?: ""

    fun setZipCode(value: String) {
        prefs.edit().putString(KEY_ZIP_CODE, value).apply()
    }
}
