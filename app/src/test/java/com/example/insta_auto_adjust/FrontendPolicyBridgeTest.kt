package com.example.insta_auto_adjust

import com.example.insta_auto_adjust.policy.FrontendPolicyBridge
import com.example.insta_auto_adjust.presentation.ShootingIntent
import com.example.insta_auto_adjust.presentation.ShootingUiState
import com.lightpilot.core.model.ParameterTarget
import com.lightpilot.core.model.PolicyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendPolicyBridgeTest {
    private val bridge = FrontendPolicyBridge()
    private val now = 1_790_035_200_000L

    @Test
    fun subjectPresetAcceptsUserIntentAndProducesEvUp() {
        val result = bridge.analyze(
            shootingState = ShootingUiState(
                selectedIntent = ShootingIntent.SUBJECT_PRIORITY
            ),
            revision = 1L,
            frameId = "1",
            nowEpochMs = now
        )

        assertEquals(0.9, result.userIntentUi.subjectPriority, 0.0)
        assertEquals(PolicyAction.EV_ONE_STEP_UP, result.coreProposal.action)
        assertEquals(1.0, (result.coreProposal.parameter as ParameterTarget.Ev).value, 0.0)
        assertTrue(result.proposalUi.action.contains("建议提高 EV"))
        assertTrue(result.proposalUi.cost.contains("source=mock"))
        assertTrue(result.sceneRisk.contains("C 策略动作=EV_ONE_STEP_UP"))
    }

    @Test
    fun highlightPresetProducesEvDown() {
        val result = bridge.analyze(
            shootingState = ShootingUiState(
                selectedIntent = ShootingIntent.HIGHLIGHT_PRIORITY
            ),
            revision = 2L,
            frameId = "2",
            nowEpochMs = now
        )

        assertEquals(0.9, result.userIntentUi.highlightProtection, 0.0)
        assertEquals(PolicyAction.EV_ONE_STEP_DOWN, result.coreProposal.action)
        assertEquals(-1.0, (result.coreProposal.parameter as ParameterTarget.Ev).value, 0.0)
        assertTrue(result.proposalUi.action.contains("建议降低 EV"))
    }

    @Test
    fun balancedPresetCanSafelyHoldWhenTradeoffIsWeak() {
        val result = bridge.analyze(
            shootingState = ShootingUiState(
                selectedIntent = ShootingIntent.BALANCED
            ),
            revision = 3L,
            frameId = "3",
            nowEpochMs = now
        )

        assertEquals(PolicyAction.HOLD, result.coreProposal.action)
        assertTrue(result.proposalUi.action.contains("保持当前参数"))
    }

    @Test
    fun sourceTextIsNotParsedIntoCameraPolicy() {
        val result = bridge.analyze(
            shootingState = ShootingUiState(
                selectedIntent = ShootingIntent.BALANCED,
                intentInputText = "人脸太暗了，优先看清人物"
            ),
            revision = 4L,
            frameId = "4",
            nowEpochMs = now
        )

        assertEquals("CONFIRMED_FIXED_ENUM", result.userIntentUi.source)
        assertEquals(0.6, result.userIntentUi.subjectPriority, 0.0)
        assertEquals(PolicyAction.HOLD, result.coreProposal.action)
    }

    @Test
    fun repeatedSubjectAnalysisDoesNotGetPollutedByPreviousMockExecution() {
        val first = bridge.analyze(
            shootingState = ShootingUiState(
                selectedIntent = ShootingIntent.SUBJECT_PRIORITY
            ),
            revision = 5L,
            frameId = "5",
            nowEpochMs = now
        )
        val second = bridge.analyze(
            shootingState = ShootingUiState(
                selectedIntent = ShootingIntent.SUBJECT_PRIORITY
            ),
            revision = 6L,
            frameId = "6",
            nowEpochMs = now + 100L
        )

        assertEquals(PolicyAction.EV_ONE_STEP_UP, first.coreProposal.action)
        assertEquals(PolicyAction.EV_ONE_STEP_UP, second.coreProposal.action)
        assertEquals(1.0, (second.coreProposal.parameter as ParameterTarget.Ev).value, 0.0)
    }

    @Test
    fun missingHigherEvCapabilityReturnsHoldWithCoreReason() {
        val result = bridge.analyze(
            shootingState = ShootingUiState(
                selectedIntent = ShootingIntent.SUBJECT_PRIORITY
            ),
            revision = 7L,
            frameId = "7",
            nowEpochMs = now,
            supportedEv = listOf(-2.0, -1.0, 0.0)
        )

        assertEquals(PolicyAction.HOLD, result.coreProposal.action)
        assertEquals("no_legal_adjacent_ev", result.coreProposal.reason)
    }
}
