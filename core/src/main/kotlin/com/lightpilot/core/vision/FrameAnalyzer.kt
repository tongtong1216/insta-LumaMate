package com.lightpilot.core.vision

import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.GrayFrame
import com.lightpilot.core.model.Roi
import com.lightpilot.core.model.VisionMetrics
import kotlin.math.abs
import kotlin.math.max

class FrameAnalyzer(
    private val highlightThreshold: Float = 0.98f,
    private val darkThreshold: Float = 0.12f,
    private val frameValidityMs: Long = 1_500L
) {
    private var previousFrame: GrayFrame? = null

    init {
        require(highlightThreshold in 0f..1f)
        require(darkThreshold in 0f..1f)
        require(darkThreshold < highlightThreshold)
        require(frameValidityMs >= 0L)
    }

    fun analyze(
        frame: GrayFrame,
        roi: Roi? = null,
        nowEpochMs: Long = frame.capturedAtEpochMs
    ): VisionMetrics {
        require(max(frame.width, frame.height) <= 320) {
            "FrameAnalyzer requires an sRGB frame with longest edge <= 320 pixels"
        }
        val subject = RegionStats()
        val background = RegionStats()
        var highlightCount = 0
        var darkCount = 0

        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val value = frame.pixel(x, y)
                val normalizedX = x.toFloat() / frame.width
                val normalizedY = y.toFloat() / frame.height
                if (roi?.contains(normalizedX, normalizedY) == true) {
                    subject.add(value)
                } else {
                    background.add(value)
                }
                if (value >= highlightThreshold) highlightCount++
                if (value <= darkThreshold) darkCount++
            }
        }

        val pixelCount = frame.width * frame.height
        val motionScore = previousFrame
            ?.takeIf { it.width == frame.width && it.height == frame.height }
            ?.let { meanAbsoluteDifference(it, frame) }

        previousFrame = frame

        val clampedNow = nowEpochMs.coerceAtLeast(frame.capturedAtEpochMs)
        return VisionMetrics(
            frameId = frame.frameId,
            source = frame.source,
            roiVersion = roi?.version ?: "none",
            subjectBrightness = subject.meanOrNull(),
            backgroundBrightness = background.meanOrNull(),
            highlightRatio = highlightCount.toFloat() / pixelCount,
            darkRatio = darkCount.toFloat() / pixelCount,
            motionScore = motionScore,
            capturedAtEpochMs = frame.capturedAtEpochMs,
            expiresAtEpochMs = clampedNow + frameValidityMs
        )
    }

    private fun meanAbsoluteDifference(first: GrayFrame, second: GrayFrame): Float {
        var total = 0f
        for (index in first.pixels.indices) {
            total += abs(first.pixels[index] - second.pixels[index])
        }
        return (total / first.pixels.size).coerceIn(0f, 1f)
    }

    private class RegionStats {
        private var sum = 0f
        private var count = 0

        fun add(value: Float) {
            sum += value
            count++
        }

        fun meanOrNull(): Float? {
            return if (count == 0) null else (sum / count).coerceIn(0f, 1f)
        }
    }
}
