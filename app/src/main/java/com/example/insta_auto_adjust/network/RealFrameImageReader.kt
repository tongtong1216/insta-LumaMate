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
    private const val MAX_UPLOAD_EDGE = 1_280
    private const val MAX_METRICS_EDGE = 320
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

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
            val jpeg = output.toByteArray()
            if (jpeg.size > MAX_IMAGE_BYTES) {
                throw IOException("Representative frame exceeds D's 4 MiB image limit")
            }
            RealFramePayload(
                imageBase64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
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
        if (edge <= MAX_UPLOAD_EDGE) return bitmap
        val scale = MAX_UPLOAD_EDGE.toFloat() / edge.toFloat()
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
        val metricsBitmap = resizeForMetrics(normalized)
        var total = 0.0
        var samples = 0
        var highlights = 0
        var dark = 0
        val pixels = IntArray(metricsBitmap.width)

        try {
            for (y in 0 until metricsBitmap.height) {
                metricsBitmap.getPixels(pixels, 0, metricsBitmap.width, 0, y, metricsBitmap.width, 1)
                for (x in 0 until metricsBitmap.width) {
                    val color = pixels[x]
                    val red = (color shr 16) and 0xff
                    val green = (color shr 8) and 0xff
                    val blue = color and 0xff
                    val luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
                    total += luminance
                    samples += 1
                    if (luminance >= 0.98) highlights += 1
                    if (luminance <= 0.12) dark += 1
                }
            }
        } finally {
            if (metricsBitmap !== normalized) metricsBitmap.recycle()
        }

        val average = if (samples == 0) 0.5 else total / samples
        val highlightRatio = if (samples == 0) 0.0 else highlights.toDouble() / samples
        val darkRatio = if (samples == 0) 0.0 else dark.toDouble() / samples
        return VisionMetrics(
            frameId = frameId,
            source = source,
            // No tracked subject ROI is available yet.  Expose the measured frame-average value
            // explicitly instead of letting the presentation layer substitute a fixed 0.5.
            roiVersion = "frame-average-no-roi",
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

    private fun resizeForMetrics(bitmap: Bitmap): Bitmap {
        val edge = max(bitmap.width, bitmap.height)
        if (edge <= MAX_METRICS_EDGE) return bitmap
        val scale = MAX_METRICS_EDGE.toFloat() / edge.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private const val LOCAL_FRAME_VALIDITY_MS = 1_500L
    private const val REAL_ANALYSIS_WINDOW_MS = 35_000L
}
