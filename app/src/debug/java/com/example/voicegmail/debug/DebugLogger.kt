package com.example.voicegmail.debug

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.voicegmail.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
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
    private val processId = Process.myPid()

    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        log("DebugLogger", "==================================================")
        log(
            "DebugLogger",
            "Logger initialized — log file: ${logFile?.absolutePath}, " +
                "appId=${BuildConfig.APPLICATION_ID}, version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}), " +
                "debug=${BuildConfig.DEBUG}, sdk=${Build.VERSION.SDK_INT}, device=${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp [pid=$processId thread=${Thread.currentThread().name}] [$tag] $message\n"
        try {
            logFile?.appendText(line)
        } catch (e: Exception) {
            Log.e("DebugLogger", "Failed to write to log file", e)
        }
        Log.d(tag, message)
    }

    fun logException(tag: String, message: String, e: Throwable) {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            e.printStackTrace(printWriter)
        }
        log(
            tag,
            "$message — ${e.javaClass.name}: ${e.message ?: "no message"}\n${writer}"
        )
    }

    /** Returns the log [File], or null if [init] has not been called. */
    fun getLogFile(): File? = logFile

    @Synchronized
    fun clearLog() {
        logFile?.writeText("")
    }
}
