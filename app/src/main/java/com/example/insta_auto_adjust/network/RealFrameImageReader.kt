package com.example.insta_auto_adjust.network

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.insta_auto_adjust.presentation.VisionMetricsUi
import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.VisionMetrics
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

data class RealFramePayload(
    val imageBase64: String,
    val metrics: VisionMetrics,
    val metricsUi: VisionMetricsUi
)

/**
 * Converts a decoded camera frame into the D v1 payload.
 *
 * The document-picker overload remains for isolated adapter tests only. The normal runtime path
 * uses [readJpeg] with bytes produced by the GO Ultra preview stream.
 */
object RealFrameImageReader {
    private const val MAX_EDGE = 1_280

    fun read(
        contentResolver: ContentResolver,
        uri: android.net.Uri,
        frameId: String,
        nowEpochMs: Long
    ): RealFramePayload {
        val bitmap = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IOException("Unable to decode selected image")

        val normalized = resizeIfNeeded(bitmap)
        if (normalized !== bitmap) {
            bitmap.recycle()
        }

        return encodeBitmap(normalized, frameId, nowEpochMs, FrameSource.MANUAL_IMPORT)
    }

    fun readJpeg(
        jpegBytes: ByteArray,
        frameId: String,
        nowEpochMs: Long,
        source: FrameSource = FrameSource.SDK_DECODED
    ): RealFramePayload {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IOException("Unable to decode preview JPEG")
        val normalized = resizeIfNeeded(bitmap)
        if (normalized !== bitmap) {
            bitmap.recycle()
        }
        return encodeBitmap(normalized, frameId, nowEpochMs, source)
    }

    private fun encodeBitmap(
        bitmap: Bitmap,
        frameId: String,
        nowEpochMs: Long,
        source: FrameSource
    ): RealFramePayload {
        return try {
            val metrics = calculateMetrics(
                normalized = bitmap,
                frameId = frameId,
                nowEpochMs = nowEpochMs,
                source = source,
            )
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                throw IOException("Unable to encode camera frame")
            }
            RealFramePayload(
                imageBase64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                metrics = metrics,
                metricsUi = VisionMetricsUi(
                    frameId = metrics.frameId,
                    subjectBrightness = (metrics.subjectBrightness ?: 0.5f).toDouble(),
                    highlightRatio = (metrics.highlightRatio ?: 0.0f).toDouble(),
            darkRatio = (metrics.darkRatio ?: 0.0f).toDouble(),
            roiVersion = metrics.roiVersion,
            timestamp = metrics.capturedAtEpochMs
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun resizeIfNeeded(bitmap: Bitmap): Bitmap {
        val edge = max(bitmap.width, bitmap.height)
        if (edge <= MAX_EDGE) return bitmap
        val scale = MAX_EDGE.toFloat() / edge.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun calculateMetrics(
        normalized: Bitmap,
        frameId: String,
        nowEpochMs: Long,
        source: FrameSource
    ): VisionMetrics {
        val sampleStep = max(1, max(normalized.width, normalized.height) / 160)
        var total = 0.0
        var samples = 0
        var highlights = 0
        var dark = 0
        val pixels = IntArray(normalized.width)

        var y = 0
        while (y < normalized.height) {
            normalized.getPixels(pixels, 0, normalized.width, 0, y, normalized.width, 1)
            var x = 0
            while (x < normalized.width) {
                val color = pixels[x]
                val red = (color shr 16) and 0xff
                val green = (color shr 8) and 0xff
                val blue = color and 0xff
                val luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
                total += luminance
                samples += 1
                if (luminance >= 0.90) highlights += 1
                if (luminance <= 0.20) dark += 1
                x += sampleStep
            }
            y += sampleStep
        }

        val average = if (samples == 0) 0.5 else total / samples
        val highlightRatio = if (samples == 0) 0.0 else highlights.toDouble() / samples
        val darkRatio = if (samples == 0) 0.0 else dark.toDouble() / samples
        return VisionMetrics(
            frameId = frameId,
            source = source,
            roiVersion = "full-frame-luminance",
            subjectBrightness = average.toFloat(),
            backgroundBrightness = average.toFloat(),
            highlightRatio = highlightRatio.toFloat(),
            darkRatio = darkRatio.toFloat(),
            motionScore = null,
            capturedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + if (source == FrameSource.SDK_DECODED) {
                REAL_ANALYSIS_WINDOW_MS
            } else {
                LOCAL_FRAME_VALIDITY_MS
            }
        )
    }

    private const val LOCAL_FRAME_VALIDITY_MS = 1_500L
    private const val REAL_ANALYSIS_WINDOW_MS = 35_000L
}
