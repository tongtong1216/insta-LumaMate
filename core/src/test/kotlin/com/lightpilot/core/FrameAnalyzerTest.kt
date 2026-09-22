package com.lightpilot.core

import com.lightpilot.core.model.Roi
import com.lightpilot.core.vision.FrameAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrameAnalyzerTest {
    @Test
    fun calculatesBrightnessAndRatiosFromNormalizedFrame() {
        val pixels = floatArrayOf(
            0.95f, 0.95f, 0.05f, 0.05f,
            0.95f, 0.80f, 0.20f, 0.05f,
            0.40f, 0.40f, 0.40f, 0.40f,
            0.40f, 0.40f, 0.40f, 0.40f
        )
        val frame = TestFixtures.frame().copy(pixels = pixels)

        val metrics = FrameAnalyzer().analyze(
            frame = frame,
            roi = Roi(0f, 0f, 0.5f, 0.5f, "top-left")
        )

        assertEquals(0.8625f, metrics.subjectBrightness!!, 0.0001f)
        assertEquals(0.3f, metrics.highlightRatio!!, 0.0001f)
        assertEquals(0.1875f, metrics.darkRatio!!, 0.0001f)
        assertEquals(null, metrics.motionScore)
    }

    @Test
    fun detectsMotionBetweenConsecutiveFrames() {
        val analyzer = FrameAnalyzer()
        analyzer.analyze(TestFixtures.frame(value = 0.2f))
        val metrics = analyzer.analyze(TestFixtures.frame(frameId = "frame-002", value = 0.8f))

        assertNotNull(metrics.motionScore)
        assertEquals(0.6f, metrics.motionScore!!, 0.0001f)
        assertTrue(metrics.motionScore!! > 0f)
    }
}
