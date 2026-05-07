package com.example.voicegmail.debug

import android.content.Context
import java.io.File

/**
 * No-op stub used in release builds.
 * All calls are inlined away by the compiler; no file I/O or overhead at runtime.
 */
object DebugLogger {
    fun init(context: Context) = Unit
    fun log(tag: String, message: String) = Unit
    fun logException(tag: String, message: String, e: Throwable) = Unit
    fun getLogFile(): File? = null
    fun clearLog() = Unit
}
