package com.example.voicegmail.voice

import android.content.Context
import android.content.SharedPreferences
import com.example.voicegmail.debug.DebugLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class LauncherApp(
    val packageName: String,
    val displayName: String,
    val voiceCommand: String
)

@Singleton
class AppLauncherPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "AppLauncherPrefs"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_launcher", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getApps(): List<LauncherApp> {
        val json = prefs.getString("apps", null) ?: return emptyList()
        val type = object : TypeToken<List<LauncherApp>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            DebugLogger.log(tag, "Failed to parse saved apps: ${e.message}")
            emptyList()
        }
    }

    fun saveApps(apps: List<LauncherApp>) {
        prefs.edit().putString("apps", gson.toJson(apps)).apply()
    }

    fun saveApp(app: LauncherApp) {
        val apps = getApps().toMutableList()
        val idx = apps.indexOfFirst { it.packageName == app.packageName }
        if (idx >= 0) apps[idx] = app else apps.add(app)
        saveApps(apps)
    }

    fun removeApp(packageName: String) {
        val apps = getApps().filter { it.packageName != packageName }
        saveApps(apps)
    }

    fun findByVoiceCommand(cmd: String): LauncherApp? {
        val lower = cmd.lowercase().trim()
        return getApps().firstOrNull {
            lower.startsWith(it.voiceCommand.lowercase().trim())
        }
    }
}
