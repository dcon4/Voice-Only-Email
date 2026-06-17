package com.example.voicegmail.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

object ImageUtils {

    fun yuv420ToBitmap(image: Image, rotationDegrees: Int): Bitmap {
        val width = image.width
        val height = image.height
        val yuvBytes = yuv420ToNv21(image)
        val yuvImage = YuvImage(yuvBytes, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
        val jpegBytes = out.toByteArray()
        var bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }
        return bmp
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
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

    fun downsample(bitmap: Bitmap, maxWidth: Int = 1080): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / w
        val newW = maxWidth
        val newH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
