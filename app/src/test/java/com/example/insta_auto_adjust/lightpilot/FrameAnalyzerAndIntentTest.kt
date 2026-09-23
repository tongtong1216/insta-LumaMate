package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAnalyzerAndIntentTest {
    @Test
    fun computesBt709MetricsAndOptionalSubject() {
        val black = 0xff000000.toInt()
        val white = 0xffffffff.toInt()
        val pixels = intArrayOf(black, black, white, white)
        val noRoi = FrameAnalyzer.analyzeArgb(pixels, 2, 2, 1)
        assertNull(noRoi.subjectBrightness)
        assertEquals(0.5, noRoi.backgroundBrightness, 1e-6)
        assertEquals(0.5, noRoi.highlightClippingRatio, 1e-6)
        assertEquals(0.5, noRoi.darkRatio, 1e-6)

        val withRoi = FrameAnalyzer.analyzeArgb(pixels, 2, 2, 2, PixelRect(0, 0, 2, 1))
        assertEquals(0.0, withRoi.subjectBrightness!!, 1e-6)
        assertEquals(1.0, withRoi.backgroundBrightness, 1e-6)
    }

    @Test
    fun textMappingIsDeterministicAndConflictFallsBackToBalanced() {
        assertEquals(ExposurePriority.SUBJECT_DETAIL, IntentMapper.suggest("请拍清楚人物").first)
        assertEquals(ExposurePriority.HIGHLIGHT_DETAIL, IntentMapper.suggest("保留灯光氛围").first)
        val conflict = IntentMapper.suggest("人物要清楚，高光不过曝，而且不要频繁变化")
        assertEquals(ExposurePriority.BALANCED, conflict.first)
        assertEquals(StabilityPreference.HIGH, conflict.second)
    }
}
