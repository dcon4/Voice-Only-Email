package com.example.voicegmail.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only logger that appends timestamped entries to [LOG_FILE_NAME] inside
 * the app's private files directory.  Replaced by a no-op stub in release builds.
 */
object DebugLogger {

    private const val LOG_FILE_NAME = "voicegmail-debug.log"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        log("DebugLogger", "Logger initialized — log file: ${logFile?.absolutePath}")
    }

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp [$tag] $message\n"
        try {
            logFile?.appendText(line)
        } catch (e: Exception) {
            Log.e("DebugLogger", "Failed to write to log file", e)
        }
        Log.d(tag, message)
    }

    fun logException(tag: String, message: String, e: Throwable) {
        log(tag, "$message — ${e.javaClass.simpleName}: ${e.message}")
    }

    /** Returns the log [File], or null if [init] has not been called. */
    fun getLogFile(): File? = logFile

    fun clearLog() {
        logFile?.writeText("")
    }
}
