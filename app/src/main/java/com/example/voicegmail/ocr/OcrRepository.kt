package com.example.voicegmail.ocr

import android.content.Context
import android.graphics.Bitmap
import com.example.voicegmail.debug.DebugLogger
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "OcrRepository"

    @Volatile private var tess: TessBaseAPI? = null
    private var isInitialized = false

    private val tessDir: File by lazy {
        val dir = File(context.filesDir, "tesseract")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val tessDataDir: File by lazy {
        val dir = File(tessDir, "tessdata")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun init(): Boolean {
        if (isInitialized) return true
        try {
            copyTrainedDataIfNeeded()
            val api = TessBaseAPI()
            api.init(tessDir.absolutePath, "eng")
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            tess = api
            isInitialized = true
            DebugLogger.log(tag, "Tesseract initialized")
            return true
        } catch (e: Exception) {
            DebugLogger.log(tag, "Tesseract init failed: ${e.message}")
            return false
        }
    }

    fun recognize(bitmap: Bitmap, onResult: (String) -> Unit) {
        val api = tess
        if (api == null) {
            onResult("")
            return
        }
        try {
            val gray = toGrayscale(bitmap)
            val downsampled = ImageUtils.downsample(gray, 1080)
            api.setImage(downsampled)
            val text = api.utF8Text ?: ""
            val cleaned = text.trim()
            DebugLogger.log(tag, "OCR result: ${cleaned.take(100)}")
            if (cleaned.isNotBlank()) {
                onResult(cleaned)
            } else {
                onResult("")
            }
        } catch (e: Exception) {
            DebugLogger.log(tag, "OCR recognition failed: ${e.message}")
            onResult("")
        }
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val grayPixels = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            grayPixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        result.setPixels(grayPixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun copyTrainedDataIfNeeded() {
        val targetFile = File(tessDataDir, "eng.traineddata")
        if (targetFile.exists()) return
        try {
            context.assets.open("tessdata/eng.traineddata").use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            DebugLogger.log(tag, "Copied eng.traineddata to ${targetFile.absolutePath}")
        } catch (e: Exception) {
            DebugLogger.log(tag, "Failed to copy traineddata: ${e.message}")
        }
    }

    fun release() {
        try {
            tess?.end()
        } catch (_: Exception) {}
        tess = null
        isInitialized = false
        DebugLogger.log(tag, "Tesseract released")
    }
}
