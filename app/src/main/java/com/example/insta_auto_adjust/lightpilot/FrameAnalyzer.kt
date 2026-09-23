package com.example.insta_auto_adjust.lightpilot

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.max

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    fun isValid(width: Int, height: Int) =
        left >= 0 && top >= 0 && right <= width && bottom <= height && right > left && bottom > top
}

object FrameAnalyzer {
    private const val MAX_ANALYSIS_EDGE = 320

    fun analyze(bitmap: Bitmap, frameId: Long, subjectRoi: PixelRect? = null): VisionMetrics {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return analyzeArgb(pixels, bitmap.width, bitmap.height, frameId, subjectRoi)
    }

    fun analyzeArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        frameId: Long,
        subjectRoi: PixelRect? = null,
    ): VisionMetrics {
        require(width > 0 && height > 0 && pixels.size >= width * height)
        val roi = subjectRoi?.takeIf { it.isValid(width, height) }
        val stride = max(1, ceil(max(width, height).toDouble() / MAX_ANALYSIS_EDGE).toInt())
        var allSum = 0.0
        var allCount = 0
        var subjectSum = 0.0
        var subjectCount = 0
        var backgroundSum = 0.0
        var backgroundCount = 0
        var highlights = 0
        var dark = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val color = pixels[y * width + x]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                allSum += luminance
                allCount++
                if (luminance >= 0.98) highlights++
                if (luminance <= 0.12) dark++
                if (roi != null && roi.contains(x, y)) {
                    subjectSum += luminance
                    subjectCount++
                } else {
                    backgroundSum += luminance
                    backgroundCount++
                }
                x += stride
            }
            y += stride
        }
        val wholeAverage = allSum / allCount
        return VisionMetrics(
            frameId = frameId,
            subjectBrightness = if (subjectCount > 0) subjectSum / subjectCount else null,
            backgroundBrightness = if (backgroundCount > 0) backgroundSum / backgroundCount else wholeAverage,
            highlightClippingRatio = highlights.toDouble() / allCount,
            darkRatio = dark.toDouble() / allCount,
        )
    }
}
