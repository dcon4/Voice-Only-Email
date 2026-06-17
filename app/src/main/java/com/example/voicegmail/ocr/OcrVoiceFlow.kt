package com.example.voicegmail.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.voice.VoiceCommand
import com.example.voicegmail.voice.VoiceCommandEngine
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrVoiceFlow @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrRepository: OcrRepository,
    private val voiceCommandEngine: VoiceCommandEngine,
    private val voiceManager: VoiceManager
) {
    private val tag = "OcrVoiceFlow"
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private inner class CameraLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val cameraLifecycle = CameraLifecycleOwner()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var isRunning = false

    @Volatile private var latestFrame: CapturedFrame? = null
    @Volatile private var lastResult: String? = null

    private data class CapturedFrame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int
    )

    private var lifecycleStarted = false

    fun start(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        DebugLogger.log(tag, "OCR flow started")
        isRunning = true
        lastResult = null

        if (!ocrRepository.init()) {
            voiceCommandEngine.speakThenListen(
                "OCR engine failed to initialise. Please restart the app and try again."
            ) { cmd -> onExit(cmd) }
            return
        }

        startCamera(scope) { success ->
            if (success) {
                voiceCommandEngine.speakThenListen(
                    "Scanner mode. Point the camera at the text. " +
                        "Say 'scan' when ready, or 'cancel' to exit."
                ) { cmd -> handleCommand(cmd, scope, onExit) }
            } else {
                voiceCommandEngine.speakThenListen(
                    "Camera could not be opened. " +
                        "Make sure camera permission is granted and no other app is using the camera."
                ) { cmd -> onExit(cmd) }
            }
        }
    }

    fun handleWakeInterrupt(): Boolean {
        if (isRunning) {
            DebugLogger.log(tag, "Wake interrupt — stopping OCR")
            stop()
            return true
        }
        return false
    }

    fun stop() {
        DebugLogger.log(tag, "Stopping OCR flow")
        isRunning = false
        latestFrame = null
        lastResult = null
        stopCamera()
    }

    // ── Command handling ────────────────────────────────────────────────

    private fun handleCommand(cmd: VoiceCommand, scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        if (!isRunning) return

        when (cmd) {
            is VoiceCommand.Cancel, is VoiceCommand.GoBack -> {
                stop()
                onExit(VoiceCommand.None)
            }
            is VoiceCommand.GoToSleep -> {
                stop()
                onExit(cmd)
            }
            is VoiceCommand.SessionTimeout -> {
                stop()
                onExit(cmd)
            }
            is VoiceCommand.FreeText -> {
                val text = cmd.text.trim().lowercase()
                when {
                    text.contains("scan") || text.contains("capture") || text.contains("take") ->
                        captureAndRecognize(scope, onExit)

                    text.contains("repeat") || text.contains("again") ->
                        readLastResult(scope, onExit)

                    text.contains("exit") || text.contains("close") || text.contains("quit") || text.contains("done") -> {
                        stop()
                        onExit(VoiceCommand.None)
                    }
                    text.isBlank() ->
                        voiceCommandEngine.speakThenListen(
                            "I didn't catch that. Say 'scan' to capture the page, 'repeat' to hear the last result, or 'cancel' to exit."
                        ) { retry -> handleCommand(retry, scope, onExit) }
                    else ->
                        voiceCommandEngine.speakThenListen(
                            "Say 'scan' to capture the page, 'repeat' to hear the last result, or 'cancel' to exit."
                        ) { retry -> handleCommand(retry, scope, onExit) }
                }
            }
            else -> {
                stop()
                onExit(cmd)
            }
        }
    }

    private fun captureAndRecognize(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val frame = latestFrame
        if (frame == null) {
            voiceCommandEngine.speakThenListen(
                "No camera frame available. Make sure the camera is pointed at the text, then say 'scan'."
            ) { cmd -> handleCommand(cmd, scope, onExit) }
            return
        }

        voiceManager.speak("Scanning. Please wait.")

        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) { frameToBitmap(frame) }
                ocrRepository.recognize(bitmap) { text ->
                    if (text.isBlank()) {
                        voiceCommandEngine.speakThenListen(
                            "No text found. Make sure the text is in focus and well lit, " +
                                "then say 'scan' again, or 'cancel' to exit."
                        ) { cmd -> handleCommand(cmd, scope, onExit) }
                    } else {
                        lastResult = text
                        voiceManager.speak(text) {
                            voiceCommandEngine.speakThenListen(
                                "Say 'scan again' to capture another page, " +
                                    "'repeat' to hear that again, or 'cancel' to exit."
                            ) { cmd -> handleCommand(cmd, scope, onExit) }
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log(tag, "OCR error: ${e.message}")
                voiceCommandEngine.speakThenListen(
                    "An error occurred while scanning. ${e.message ?: "Please try again."} " +
                        "Say 'scan' to try again, or 'cancel' to exit."
                ) { cmd -> handleCommand(cmd, scope, onExit) }
            }
        }
    }

    private fun readLastResult(scope: CoroutineScope, onExit: (VoiceCommand) -> Unit) {
        val text = lastResult
        if (text.isNullOrBlank()) {
            voiceCommandEngine.speakThenListen(
                "No scan result yet. Say 'scan' to capture the page."
            ) { cmd -> handleCommand(cmd, scope, onExit) }
        } else {
            voiceManager.speak(text) {
                voiceCommandEngine.speakThenListen(
                    "Say 'scan again', 'repeat', or 'cancel'."
                ) { cmd -> handleCommand(cmd, scope, onExit) }
            }
        }
    }

    // ── Frame processing ────────────────────────────────────────────────

    private fun frameToBitmap(frame: CapturedFrame): Bitmap {
        val yuvImage = YuvImage(frame.data, ImageFormat.NV21, frame.width, frame.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, frame.width, frame.height), 85, out)
        val bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        if (frame.rotationDegrees == 0) return bmp
        val matrix = Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    // ── Camera management ───────────────────────────────────────────────

    private fun startCamera(scope: CoroutineScope, onResult: (Boolean) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                cameraLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                cameraLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleStarted = true

                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val image = imageProxy.image
                    if (image != null) {
                        val yuvBytes = yuv420ToNv21(image)
                        latestFrame = CapturedFrame(
                            data = yuvBytes,
                            width = image.width,
                            height = image.height,
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        )
                    }
                    imageProxy.close()
                }

                imageAnalysis = analysis
                provider.unbindAll()
                provider.bindToLifecycle(cameraLifecycle, selector, analysis)

                DebugLogger.log(tag, "Camera started")
                onResult(true)
            } catch (e: Exception) {
                DebugLogger.log(tag, "Camera start failed: ${e.message}")
                onResult(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        try {
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
            cameraProvider?.unbindAll()
            cameraProvider = null
            if (lifecycleStarted) {
                cameraLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                cameraLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                lifecycleStarted = false
            }
            DebugLogger.log(tag, "Camera stopped")
        } catch (e: Exception) {
            DebugLogger.log(tag, "Error stopping camera: ${e.message}")
        }
    }

    // ── YUV conversion ──────────────────────────────────────────────────

    private fun yuv420ToNv21(image: android.media.Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)

        val pixelStride = image.planes[1].pixelStride
        val rowStride = image.planes[1].rowStride

        val uPlane = ByteArray(uSize).also { uBuffer.get(it) }
        val vPlane = ByteArray(vSize).also { vBuffer.get(it) }

        val chromaHeight = image.height / 2
        var offset = ySize
        var pos = 0
        for (row in 0 until chromaHeight) {
            for (col in 0 until image.width / 2) {
                nv21[offset + pos] = vPlane[row * rowStride + col * pixelStride]
                nv21[offset + pos + 1] = uPlane[row * rowStride + col * pixelStride]
                pos += 2
            }
        }
        return nv21
    }
}
