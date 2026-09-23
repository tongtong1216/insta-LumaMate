package com.lightpilot.core

import com.lightpilot.core.model.ExecutionMode
import com.lightpilot.core.model.ParameterTarget
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.SafetyReason
import com.lightpilot.core.policy.PolicyEngine
import com.lightpilot.core.policy.PolicyInput
import com.lightpilot.core.policy.SafetyGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AdvancedPolicyTest {
    @Test
    fun motionIntentCreatesMockShutterProposal() {
        val intent = TestFixtures.intent(
            subjectDetail = 0f,
            highlightDetail = 0f
        ).copy(motionClarity = 1f)
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = intent,
                metrics = TestFixtures.metrics().copy(motionScore = 0.85f),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW,
                executionMode = ExecutionMode.MOCK,
                inputSource = com.lightpilot.core.model.InputSource.MOCK
            )
        )

        assertEquals(PolicyAction.SET_SHUTTER, proposal.action)
        assertEquals(
            com.lightpilot.core.model.ShutterSpeed(1.0, 120.0),
            (proposal.parameter as ParameterTarget.Shutter).value
        )
        assertEquals(ExecutionMode.MOCK, proposal.executionMode)

        val decision = SafetyGuard().evaluate(
            proposal = proposal,
            currentState = TestFixtures.cameraState(),
            capabilities = TestFixtures.capabilities(),
            currentMetrics = TestFixtures.metrics(),
            currentIntent = intent,
            nowEpochMs = TestFixtures.NOW,
            commandId = "mock-shutter"
        )
        assertFalse(decision.allowed)
        assertEquals(SafetyReason.UNSUPPORTED_PARAMETER, decision.reason)
    }

    @Test
    fun lowNoiseIntentCreatesMockIsoProposal() {
        val state = TestFixtures.cameraState().copy(currentIso = 800)
        val intent = TestFixtures.intent(
            subjectDetail = 0f,
            highlightDetail = 0f
        ).copy(lowNoise = 1f)
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = intent,
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic(),
                cameraState = state,
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW,
                executionMode = ExecutionMode.MOCK,
                inputSource = com.lightpilot.core.model.InputSource.MOCK
            )
        )

        assertEquals(PolicyAction.SET_ISO, proposal.action)
        assertEquals(400, (proposal.parameter as ParameterTarget.Iso).value)
    }

    @Test
    fun naturalColorIntentCreatesMockWhiteBalanceProposal() {
        val intent = TestFixtures.intent(
            subjectDetail = 0f,
            highlightDetail = 0f
        ).copy(colorNeutrality = 1f)
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = intent,
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW,
                executionMode = ExecutionMode.MOCK,
                inputSource = com.lightpilot.core.model.InputSource.MOCK
            )
        )

        assertEquals(PolicyAction.SET_WHITE_BALANCE, proposal.action)
        assertEquals(5000, (proposal.parameter as ParameterTarget.WhiteBalance).value)
    }

    @Test
    fun atmosphereNeedsColoredLightSemantic() {
        val intent = TestFixtures.intent(
            subjectDetail = 0f,
            highlightDetail = 0f
        ).copy(atmospherePreservation = 1f)
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = intent,
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic().copy(coloredLight = false),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW,
                executionMode = ExecutionMode.MOCK,
                inputSource = com.lightpilot.core.model.InputSource.MOCK
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals(
            "atmosphere_semantic_or_white_balance_capability_unavailable",
            proposal.reason
        )
    }

    @Test
    fun realAdvancedIntentDoesNotCreateExecutableProposal() {
        val intent = TestFixtures.intent(
            subjectDetail = 0f,
            highlightDetail = 0f
        ).copy(motionClarity = 1f)
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = intent,
                metrics = TestFixtures.metrics(source = com.lightpilot.core.model.FrameSource.SDK_DECODED),
                semantic = TestFixtures.semantic().copy(reason = "backend"),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW,
                executionMode = ExecutionMode.REAL,
                inputSource = com.lightpilot.core.model.InputSource.REAL
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals(
            "advanced_parameter_requires_verified_execution",
            proposal.reason
        )
    }

    @Test
    fun ambiguousAdvancedPreferencesHoldInsteadOfGuessing() {
        val intent = TestFixtures.intent(
            subjectDetail = 0f,
            highlightDetail = 0f
        ).copy(
            motionClarity = 0.9f,
            lowNoise = 0.85f
        )
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = intent,
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState(),
                capabilities = TestFixtures.capabilities(),
                nowEpochMs = TestFixtures.NOW,
                executionMode = ExecutionMode.MOCK,
                inputSource = com.lightpilot.core.model.InputSource.MOCK
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("advanced_tradeoff_not_decisive", proposal.reason)
    }

    @Test
    fun missingAdvancedCapabilityListFallsBackToHold() {
        val intent = TestFixtures.intent(
            subjectDetail = 0f,
            highlightDetail = 0f
        ).copy(lowNoise = 1f)
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = intent,
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic(),
                cameraState = TestFixtures.cameraState().copy(currentIso = 800),
                capabilities = TestFixtures.capabilities().copy(supportedIso = emptyList()),
                nowEpochMs = TestFixtures.NOW,
                executionMode = ExecutionMode.MOCK,
                inputSource = com.lightpilot.core.model.InputSource.MOCK
            )
        )

        assertEquals(PolicyAction.HOLD, proposal.action)
        assertEquals("iso_capability_unavailable", proposal.reason)
    }
}
