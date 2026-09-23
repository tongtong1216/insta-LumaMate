package com.lightpilot.core

import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.SemanticUncertaintyDetail
import com.lightpilot.core.policy.PolicyEngine
import com.lightpilot.core.policy.PolicyInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolicyEngineTest {
    @Test
    fun subjectWarningBlocksOnlyTheDependentStage() {
        val semantic = TestFixtures.semantic().copy(
            uncertaintyDetails = listOf(
                SemanticUncertaintyDetail(
                    code = "subject_occluded",
                    severity = "warning",
                    affects = setOf("subject_type", "subject_roi")
                )
            )
        )
        val subjectProposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(subjectDetail = 1f, highlightDetail = 0.1f),
                metrics = TestFixtures.metrics(),
                semantic = semantic,
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )
        val highlightProposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(subjectDetail = 0.1f, highlightDetail = 1f),
                metrics = TestFixtures.metrics(
                    subjectBrightness = 0.65f,
                    highlightRatio = 0.65f,
                    darkRatio = 0.05f
                ),
                semantic = semantic,
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, subjectProposal.action)
        assertEquals("subject_roi_or_semantic_unavailable", subjectProposal.reason)
        assertEquals(PolicyAction.EV_ONE_STEP_DOWN, highlightProposal.action)
    }

    @Test
    fun missingReliableRoiBlocksSubjectButNotHighlightPolicy() {
        val noRoi = TestFixtures.metrics().copy(subjectBrightness = null, roiVersion = "none")
        val subjectProposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(subjectDetail = 1f, highlightDetail = 0.1f),
                metrics = noRoi,
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )
        val highlightProposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(subjectDetail = 0.1f, highlightDetail = 1f),
                metrics = noRoi.copy(highlightRatio = 0.65f, darkRatio = 0.05f),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, subjectProposal.action)
        assertEquals(PolicyAction.EV_ONE_STEP_DOWN, highlightProposal.action)
    }

    @Test
    fun subjectIntentSuggestsNextLegalEvUp() {
        val input = PolicyInput(
            intent = TestFixtures.intent(subjectDetail = 1f, highlightDetail = 0.05f),
            metrics = TestFixtures.metrics(subjectBrightness = 0.18f, darkRatio = 0.5f),
            semantic = TestFixtures.semantic(),
            cameraState = TestFixtures.cameraState(currentEv = 0.0),
            capabilities = TestFixtures.capabilities(),
            nowEpochMs = TestFixtures.NOW
        )

        val proposal = PolicyEngine().propose(input)

        assertEquals(PolicyAction.EV_ONE_STEP_UP, proposal.action)
        assertEquals(1.0, (proposal.parameter as com.lightpilot.core.model.ParameterTarget.Ev).value)
        assertTrue(proposal.reason.contains("subject_priority"))
        assertNotNull(proposal.diagnostics)
        assertTrue(proposal.diagnostics!!.upScore > proposal.diagnostics!!.downScore)
    }

    @Test
    fun highlightIntentSuggestsNextLegalEvDown() {
        val input = PolicyInput(
            intent = TestFixtures.intent(subjectDetail = 0.1f, highlightDetail = 1f),
            metrics = TestFixtures.metrics(
                subjectBrightness = 0.65f,
                highlightRatio = 0.65f,
                darkRatio = 0.05f
            ),
            semantic = TestFixtures.semantic(),
            cameraState = TestFixtures.cameraState(currentEv = 0.0),
            capabilities = TestFixtures.capabilities(),
            nowEpochMs = TestFixtures.NOW
        )

        val proposal = PolicyEngine().propose(input)

        assertEquals(PolicyAction.EV_ONE_STEP_DOWN, proposal.action)
        assertEquals(-1.0, (proposal.parameter as com.lightpilot.core.model.ParameterTarget.Ev).value)
    }

    @Test
    fun unavailableModelFallsBackToHold() {
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(),
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic(available = false),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("scene_semantic_unavailable", proposal.reason)
    }

    @Test
    fun manualModeDoesNotPretendToProduceEvProposal() {
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(),
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(
                    exposureProgram = com.lightpilot.core.model.ExposureProgram.MANUAL
                ),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("p0_ev_policy_requires_auto_mode", proposal.reason)
    }

    @Test
    fun edgeOfLegalEvRangeFallsBackToHold() {
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(subjectDetail = 1f, highlightDetail = 0f),
                metrics = TestFixtures.metrics(subjectBrightness = 0.1f, darkRatio = 0.8f),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(currentEv = 2.0),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("no_legal_adjacent_ev", proposal.reason)
    }

    @Test
    fun missingExposureBiasDeclarationFallsBackToHold() {
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(),
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities().copy(supportParam = emptySet()),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("ev_capability_unavailable", proposal.reason)
    }

    @Test
    fun staleSemanticCannotDriveAProposal() {
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(),
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic().copy(
                    receivedAtEpochMs = TestFixtures.NOW - 61_000L,
                    expiresAtEpochMs = null
                ),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("scene_semantic_stale", proposal.reason)
    }

    @Test
    fun semanticFromAnotherIntentRevisionCannotDriveAProposal() {
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(revision = 4L),
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic().copy(intentRevision = 3L),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("scene_semantic_intent_mismatch", proposal.reason)
    }
}
