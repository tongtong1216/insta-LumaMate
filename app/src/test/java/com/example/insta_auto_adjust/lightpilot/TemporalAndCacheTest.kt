package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalAndCacheTest {
    @Test
    fun normalRequiresThreeLocalFramesAndResetsWhenDirectionChanges() {
        val temporal = TemporalController()
        assertNull(temporal.observe(proposal(ExposureAction.EV_ONE_STEP_UP),
            StabilityPreference.NORMAL, 0).stableProposal)
        assertNull(temporal.observe(proposal(ExposureAction.EV_ONE_STEP_DOWN),
            StabilityPreference.NORMAL, 1).stableProposal)
        assertEquals(2, temporal.observe(proposal(ExposureAction.EV_ONE_STEP_DOWN),
            StabilityPreference.NORMAL, 2).observedFrames)
        assertNotNull(temporal.observe(proposal(ExposureAction.EV_ONE_STEP_DOWN),
            StabilityPreference.NORMAL, 3).stableProposal)
    }

    @Test
    fun highRequiresFiveAndCooldownBlocksNewSequence() {
        val temporal = TemporalController()
        repeat(4) { index ->
            assertNull(temporal.observe(proposal(ExposureAction.EV_ONE_STEP_UP),
                StabilityPreference.HIGH, index.toLong()).stableProposal)
        }
        assertNotNull(temporal.observe(proposal(ExposureAction.EV_ONE_STEP_UP),
            StabilityPreference.HIGH, 4).stableProposal)
        temporal.onExecuted(StabilityPreference.HIGH, 4)
        val blocked = temporal.observe(proposal(ExposureAction.EV_ONE_STEP_UP),
            StabilityPreference.HIGH, 4_000)
        assertEquals(ReasonCode.COOLDOWN_ACTIVE, blocked.reasonCode)
    }

    @Test
    fun intentAndCameraRevisionChangesResetConfirmation() {
        val temporal = TemporalController()
        temporal.observe(proposal(ExposureAction.EV_ONE_STEP_UP), StabilityPreference.NORMAL, 0)
        val intentChanged = temporal.observe(
            proposal(ExposureAction.EV_ONE_STEP_UP).copy(intentRevision = 2),
            StabilityPreference.NORMAL, 1)
        assertEquals(1, intentChanged.observedFrames)
        val cameraChanged = temporal.observe(
            proposal(ExposureAction.EV_ONE_STEP_UP).copy(intentRevision = 2, cameraStateRevision = 2),
            StabilityPreference.NORMAL, 2)
        assertEquals(1, cameraChanged.observedFrames)
    }

    @Test
    fun cacheAcceptsOnlyLatestMatchingResponseAndInvalidatesOnMetricShift() {
        val cache = SceneSemanticCache()
        val baseline = metrics(0.4)
        assertTrue(cache.beginRequest(11))
        assertFalse(cache.beginRequest(12))
        assertFalse(cache.accept(semantic(frame = 10), 3, 2, baseline, 1_000))
        assertTrue(cache.beginRequest(12))
        assertTrue(cache.accept(semantic(frame = 12), 3, 2, baseline, 1_000))
        assertNotNull(cache.get(3, 2, metrics(0.45), 2_000))
        assertNull(cache.get(3, 2, metrics(0.7), 2_000))
    }

    @Test
    fun unavailableRefreshClearsPreviouslyValidSemantic() {
        val cache = SceneSemanticCache()
        val metrics = metrics(0.4)
        assertTrue(cache.beginRequest(1))
        assertTrue(cache.accept(semantic(frame = 1), 3, 2, metrics, 0))
        assertNotNull(cache.get(3, 2, metrics, 1))

        assertTrue(cache.beginRequest(2))
        val unavailable = semantic(frame = 2).copy(status = SemanticStatus.UNAVAILABLE,
            scene = null, subjectType = null, brightRegionType = null, coloredLight = null,
            uncertainty = listOf("timeout"))
        assertFalse(cache.accept(unavailable, 3, 2, metrics, 2))
        assertNull(cache.get(3, 2, metrics, 3))
    }

    private fun proposal(action: ExposureAction) = PolicyProposal(
        intentRevision = 1, cameraStateRevision = 1, semanticFrameId = 1,
        metricsFrameId = 1, semanticStatus = SemanticStatus.OK, executionMode = ExecutionMode.REAL,
        action = action, targetEv = if (action == ExposureAction.HOLD) null else 1.0,
        reasonCode = ReasonCode.SUBJECT_TOO_DARK, reason = "test", risk = RiskLevel.LOW,
        createdAtMs = 0, expiresAtMs = 10_000,
    )

    private fun metrics(background: Double) = VisionMetrics(1, 0.4, background, 0.01, 0.1)

    private fun semantic(frame: Long) = SceneSemantic(frame, 3, SemanticStatus.OK,
        SceneLabel.INDOOR_EVEN_LIGHT, SubjectType.PERSON, BrightRegionType.NONE,
        false, emptyList(), "test")
}
