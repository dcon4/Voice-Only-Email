package com.example.voicegmail.debug

import android.content.Context
import android.util.Log
import com.example.voicegmail.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val TAG = "VoiceGmailDebug"
    private const val LOG_FILE_NAME = "voicegmail-debug.log"

    private var logFile: File? = null

    /** Set by the app on startup from WakePreferences. */
    @Volatile var verboseEnabled: Boolean = false

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        if (logFile != null) return
        logFile = File(context.filesDir, LOG_FILE_NAME)
        try {
            if (!logFile!!.exists()) {
                logFile!!.createNewFile()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize debug log file", e)
        }
        log("DebugLogger", "Logger initialized — log file: ${logFile?.absolutePath}")
    }

    fun log(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        val line = "${timestamp()} [$tag] $message"
        Log.d(TAG, line)
        appendLine(line)
    }

    /** Only writes to the log file when verbose mode is enabled. */
    fun verbose(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        if (!verboseEnabled) return
        val line = "${timestamp()} [$tag] [VERBOSE] $message"
        Log.d(TAG, line)
        appendLine(line)
    }

    fun logException(tag: String, message: String, throwable: Throwable) {
        if (!BuildConfig.DEBUG) return
        val line = "${timestamp()} [$tag] $message — ${throwable.javaClass.simpleName}: ${throwable.message}"
        Log.e(TAG, line, throwable)
        appendLine(line)
        appendLine(throwable.stackTraceToString())
    }

    fun getLogFile(): File? = if (BuildConfig.DEBUG) logFile else null

    fun clearLog() {
        logFile?.writeText("")
    }

    private fun appendLine(line: String) {
        val file = logFile ?: return
        try {
            FileWriter(file, true).use { writer ->
                writer.appendLine(line)
            }
        } catch (_: IOException) {
            Log.e(TAG, "Failed to write debug log line")
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
