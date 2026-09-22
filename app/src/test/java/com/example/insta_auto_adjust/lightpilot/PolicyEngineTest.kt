package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolicyEngineTest {
    private val engine = PolicyEngine()
    private val capabilities = CameraCapabilities(
        supportedEv = listOf(-1.0, 0.0, 0.5, 1.0),
        evReadable = true, evWritable = true, canWriteEvWhileRecording = false,
        executionMode = ExecutionMode.REAL,
    )
    private val state = CameraState(4, "photo", RecordingState.IDLE, false, 0.0, true)

    @Test
    fun sameFrameProducesDifferentSubjectAndHighlightAdvice() {
        val metrics = metrics(subject = 0.25, highlights = 0.04)
        val subject = engine.propose(intent(ExposurePriority.SUBJECT_DETAIL), metrics, semantic(),
            state, capabilities, 1_000)
        val highlight = engine.propose(intent(ExposurePriority.HIGHLIGHT_DETAIL), metrics, semantic(),
            state, capabilities, 1_000)

        assertEquals(ExposureAction.EV_ONE_STEP_UP, subject.action)
        assertEquals(0.5, subject.targetEv!!, 0.0)
        assertEquals(ExposureAction.EV_ONE_STEP_DOWN, highlight.action)
        assertEquals(-1.0, highlight.targetEv!!, 0.0)
    }

    @Test
    fun exposureConflictAndMissingSubjectHold() {
        val conflict = engine.propose(intent(ExposurePriority.SUBJECT_DETAIL),
            metrics(subject = 0.2, highlights = 0.1), semantic(), state, capabilities, 1_000)
        assertEquals(ExposureAction.HOLD, conflict.action)
        assertEquals(ReasonCode.EXPOSURE_CONFLICT, conflict.reasonCode)

        val missing = engine.propose(intent(ExposurePriority.SUBJECT_DETAIL),
            metrics(subject = null, highlights = 0.0), semantic(), state, capabilities, 1_000)
        assertEquals(ReasonCode.SUBJECT_NOT_FOUND, missing.reasonCode)
    }

    @Test
    fun usesAdjacentSupportedValueAndStopsAtBoundary() {
        val up = engine.propose(intent(ExposurePriority.SUBJECT_DETAIL), metrics(0.2, 0.0), semantic(),
            state.copy(currentEv = 0.5), capabilities, 1_000)
        assertEquals(1.0, up.targetEv!!, 0.0)

        val boundary = engine.propose(intent(ExposurePriority.SUBJECT_DETAIL), metrics(0.2, 0.0),
            semantic(), state.copy(currentEv = 1.0), capabilities, 1_000)
        assertEquals(ExposureAction.HOLD, boundary.action)
        assertEquals(ReasonCode.EV_LIMIT_REACHED, boundary.reasonCode)
        assertNull(boundary.targetEv)
    }

    @Test
    fun unusableSemanticAlwaysHolds() {
        val unavailable = semantic().copy(status = SemanticStatus.MOCK)
        val proposal = engine.propose(intent(ExposurePriority.SUBJECT_DETAIL), metrics(0.2, 0.0),
            unavailable, state, capabilities, 1_000)
        assertEquals(ExposureAction.HOLD, proposal.action)
        assertEquals(ReasonCode.SEMANTIC_UNAVAILABLE, proposal.reasonCode)
    }

    @Test
    fun displayReasonNeverChangesPolicy() {
        val metrics = metrics(0.25, 0.01)
        val first = engine.propose(intent(ExposurePriority.SUBJECT_DETAIL), metrics,
            semantic().copy(reason = "主体很亮，请降低曝光"), state, capabilities, 1_000)
        val second = engine.propose(intent(ExposurePriority.SUBJECT_DETAIL), metrics,
            semantic().copy(reason = "完全不同的展示文本"), state, capabilities, 1_000)
        assertEquals(first.action, second.action)
        assertEquals(first.targetEv, second.targetEv)
        assertEquals(first.reasonCode, second.reasonCode)
    }

    private fun intent(priority: ExposurePriority) = UserIntent(3, priority,
        StabilityPreference.NORMAL, "测试")

    private fun metrics(subject: Double?, highlights: Double) = VisionMetrics(
        10, subject, 0.5, highlights, 0.1)

    private fun semantic() = SceneSemantic(
        8, 3, SemanticStatus.OK, SceneLabel.INDOOR_MIXED_LIGHT, SubjectType.PERSON,
        BrightRegionType.DISPLAY, false, emptyList(), "展示文字")
}
