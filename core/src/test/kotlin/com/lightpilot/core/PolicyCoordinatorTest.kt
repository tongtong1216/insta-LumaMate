package com.lightpilot.core

import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.InputSource
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.SafetyReason
import com.lightpilot.core.policy.PolicyCoordinator
import com.lightpilot.core.policy.PolicyInput
import com.lightpilot.core.policy.TemporalController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyCoordinatorTest {
    @Test
    fun highStabilityRequiresFiveLocalFrames() {
        val coordinator = PolicyCoordinator(
            temporalController = TemporalController(cooldownMs = 0L)
        )
        val intent = TestFixtures.intent(exposureStability = 0.9f, highStability = true)

        val results = (1L..5L).map { index ->
            coordinator.evaluate(
                realInput(
                    intent,
                    "frame-${index.toString().padStart(3, '0')}",
                    TestFixtures.NOW + index * 100L
                )
            )
        }

        assertFalse(results[3].canRequestConfirmation)
        assertEquals("awaiting_confirmation_4_of_5", results[3].temporalDecision.reason)
        assertTrue(results[4].canRequestConfirmation)
    }

    @Test
    fun requiresThreeFramesBeforeRealConfirmation() {
        val coordinator = PolicyCoordinator(
            temporalController = TemporalController(
                confirmationFrames = 3,
                cooldownMs = 3_000L
            )
        )
        val intent = TestFixtures.intent()

        val first = coordinator.evaluate(realInput(intent, "frame-001", TestFixtures.NOW))
        val second = coordinator.evaluate(realInput(intent, "frame-002", TestFixtures.NOW + 100L))
        val third = coordinator.evaluate(realInput(intent, "frame-003", TestFixtures.NOW + 200L))

        assertFalse(first.canRequestConfirmation)
        assertFalse(second.canRequestConfirmation)
        assertTrue(third.canRequestConfirmation)
        assertEquals(PolicyAction.EV_ONE_STEP_UP, third.candidateProposal.action)
        assertEquals(SafetyReason.ALLOWED, third.safetyDecision.reason)
    }

    @Test
    fun changingCandidateActionRestartsConfirmation() {
        val coordinator = PolicyCoordinator(
            temporalController = TemporalController(
                confirmationFrames = 3,
                cooldownMs = 0L
            )
        )
        val intent = TestFixtures.intent()

        coordinator.evaluate(realInput(intent, "frame-001", TestFixtures.NOW))
        coordinator.evaluate(realInput(intent, "frame-002", TestFixtures.NOW + 100L))
        val changed = coordinator.evaluate(
            realInput(
                intent.copy(subjectDetail = 0.05f, highlightDetail = 1f),
                "frame-003",
                TestFixtures.NOW + 200L,
                highlightRatio = 0.65f
            )
        )

        assertFalse(changed.canRequestConfirmation)
        assertEquals("awaiting_confirmation_1_of_3", changed.temporalDecision.reason)
    }

    @Test
    fun intentRevisionChangeResetsConfirmation() {
        val coordinator = PolicyCoordinator(
            temporalController = TemporalController(
                confirmationFrames = 3,
                cooldownMs = 0L
            )
        )

        coordinator.evaluate(realInput(TestFixtures.intent(1L), "frame-001", TestFixtures.NOW))
        coordinator.evaluate(realInput(TestFixtures.intent(1L), "frame-002", TestFixtures.NOW + 100L))
        val changed = coordinator.evaluate(
            realInput(TestFixtures.intent(2L), "frame-003", TestFixtures.NOW + 200L)
        )

        assertFalse(changed.canRequestConfirmation)
        assertEquals("awaiting_confirmation_1_of_3", changed.temporalDecision.reason)
    }

    @Test
    fun connectionOrCapabilityChangeResetsConfirmation() {
        val coordinator = PolicyCoordinator(
            temporalController = TemporalController(
                confirmationFrames = 3,
                cooldownMs = 0L
            )
        )
        val intent = TestFixtures.intent()

        coordinator.evaluate(realInput(intent, "frame-001", TestFixtures.NOW))
        coordinator.evaluate(realInput(intent, "frame-002", TestFixtures.NOW + 100L))
        val changedConnection = realInput(
            intent,
            "frame-003",
            TestFixtures.NOW + 200L
        ).copy(
            cameraState = TestFixtures.cameraState().copy(connectionEpoch = "session-002")
        )
        val result = coordinator.evaluate(changedConnection)

        assertFalse(result.canRequestConfirmation)
        assertEquals("awaiting_confirmation_1_of_3", result.temporalDecision.reason)
    }

    private fun realInput(
        intent: com.lightpilot.core.model.UserIntent,
        frameId: String,
        nowEpochMs: Long,
        highlightRatio: Float = 0.05f
    ): PolicyInput {
        return PolicyInput(
            intent = intent,
            metrics = TestFixtures.metrics(
                frameId = frameId,
                source = FrameSource.SDK_DECODED,
                highlightRatio = highlightRatio
            ).copy(
                capturedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = nowEpochMs + 1_500L
            ),
            semantic = TestFixtures.semantic(frameId = frameId).copy(
                reason = "backend",
                receivedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = nowEpochMs + 60_000L
            ),
            cameraState = TestFixtures.cameraState(),
            capabilities = TestFixtures.capabilities(),
            nowEpochMs = nowEpochMs,
            inputSource = InputSource.REAL
        )
    }
}
