package com.lightpilot.core

import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.policy.IntentPreset
import com.lightpilot.core.policy.PolicyEngine
import com.lightpilot.core.policy.PolicyInput
import com.lightpilot.core.policy.UserIntentPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyScenarioTest {
    private val engine = PolicyEngine()

    @Test
    fun sameBacklitFrameChangesRecommendationWithUserIntent() {
        val metrics = TestFixtures.metrics(
            subjectBrightness = 0.22f,
            highlightRatio = 0.36f,
            darkRatio = 0.42f
        )
        val state = TestFixtures.cameraState(currentEv = 0.0)
        val capabilities = TestFixtures.capabilities()
        val semantic = TestFixtures.semantic()

        val subjectFirst = engine.propose(
            PolicyInput(
                intent = UserIntentPresets.create(IntentPreset.SUBJECT_FIRST, revision = 1L),
                metrics = metrics,
                semantic = semantic,
                cameraState = state,
                capabilities = capabilities,
                nowEpochMs = TestFixtures.NOW
            )
        )
        val highlightFirst = engine.propose(
            PolicyInput(
                intent = UserIntentPresets.create(IntentPreset.HIGHLIGHT_FIRST, revision = 2L),
                metrics = metrics,
                semantic = semantic,
                cameraState = state,
                capabilities = capabilities,
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.EV_ONE_STEP_UP, subjectFirst.action)
        assertEquals(PolicyAction.EV_ONE_STEP_DOWN, highlightFirst.action)
    }

    @Test
    fun balancedIntentHoldsWhenNeitherSideClearlyWins() {
        val proposal = engine.propose(
            PolicyInput(
                intent = UserIntentPresets.create(IntentPreset.BALANCED, revision = 1L),
                metrics = TestFixtures.metrics(
                    subjectBrightness = 0.46f,
                    highlightRatio = 0.13f,
                    darkRatio = 0.12f
                ),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(currentEv = 0.0),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertTrue(
            proposal.reason == "exposure_stability_prefers_hold" ||
                proposal.reason == "tradeoff_is_not_decisive"
        )
    }

    @Test
    fun semanticForAnotherFrameCannotInfluenceCurrentProposal() {
        val proposal = engine.propose(
            PolicyInput(
                intent = UserIntentPresets.create(IntentPreset.SUBJECT_FIRST, revision = 1L),
                metrics = TestFixtures.metrics(frameId = "frame-current"),
                semantic = TestFixtures.semantic(frameId = "frame-old"),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("scene_semantic_frame_mismatch", proposal.reason)
    }

    @Test
    fun userLockAlwaysWinsOverAUsefulSuggestion() {
        val proposal = engine.propose(
            PolicyInput(
                intent = UserIntentPresets.create(IntentPreset.SUBJECT_FIRST, revision = 1L),
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW,
                userLocked = true
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("user_locked_parameters", proposal.reason)
    }
}
