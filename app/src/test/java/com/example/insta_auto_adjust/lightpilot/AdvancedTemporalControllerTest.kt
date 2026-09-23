package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedTemporalControllerTest {
    @Test
    fun normalRequiresThreeMatchingFrames() {
        val controller = AdvancedTemporalController()
        assertNull(controller.observe(proposal(), StabilityPreference.NORMAL, 0).stableProposal)
        assertNull(controller.observe(proposal(), StabilityPreference.NORMAL, 1).stableProposal)
        assertNotNull(controller.observe(proposal(), StabilityPreference.NORMAL, 2).stableProposal)
    }

    @Test
    fun targetOrIntentChangeResetsSequence() {
        val controller = AdvancedTemporalController()
        controller.observe(proposal(), StabilityPreference.NORMAL, 0)
        val changedTarget = controller.observe(
            proposal().copy(targetIso = 1600), StabilityPreference.NORMAL, 1)
        assertEquals(1, changedTarget.observedFrames)
        val changedIntent = controller.observe(
            proposal().copy(targetIso = 1600, intentRevision = 4),
            StabilityPreference.NORMAL, 2)
        assertEquals(1, changedIntent.observedFrames)
    }

    private fun proposal() = AdvancedPolicyProposal(
        stage = AdvancedStage.MOTION_NOISE,
        intentRevision = 3,
        cameraStateRevision = 4,
        semanticFrameId = 8,
        metricsFrameId = 10,
        semanticStatus = SemanticStatus.OK,
        executionMode = ExecutionMode.MOCK,
        inputSource = InputSource.MOCK,
        action = AdvancedAction.SET_ISO,
        targetIso = 800,
        reasonCode = AdvancedReasonCode.SCENE_TOO_DARK,
        reason = "test",
        risk = RiskLevel.MEDIUM,
        createdAtMs = 0,
        expiresAtMs = 5_000,
    )
}
