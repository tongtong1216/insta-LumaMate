package com.example.insta_auto_adjust

import com.example.insta_auto_adjust.policy.MockPolicyScenario
import com.example.insta_auto_adjust.policy.LocalKeywordIntentResolver
import com.example.insta_auto_adjust.policy.PolicyDemo
import com.lightpilot.core.model.ExecutionMode
import com.lightpilot.core.model.ParameterTarget
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.SafetyReason
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyDemoTest {
    private val intentResolver = LocalKeywordIntentResolver()

    @Test
    fun subjectFirstSuggestsEvUp() {
        val result = PolicyDemo.run(
            scenario = MockPolicyScenario.SUBJECT_FIRST,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(PolicyAction.EV_ONE_STEP_UP, result.proposal.action)
        assertEquals(SafetyReason.ALLOWED, result.safetyDecision.reason)
    }

    @Test
    fun highlightFirstSuggestsEvDown() {
        val result = PolicyDemo.run(
            scenario = MockPolicyScenario.HIGHLIGHT_FIRST,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(PolicyAction.EV_ONE_STEP_DOWN, result.proposal.action)
        assertEquals(SafetyReason.ALLOWED, result.safetyDecision.reason)
    }

    @Test
    fun balancedIntentHolds() {
        val result = PolicyDemo.run(
            scenario = MockPolicyScenario.BALANCED,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(PolicyAction.HOLD, result.proposal.action)
        assertEquals(SafetyReason.NO_ACTION, result.safetyDecision.reason)
    }

    @Test
    fun naturalLanguageIntentIsConvertedBeforePolicyRuns() {
        val resolution = intentResolver.resolve(
            sourceText = "人脸要清楚，窗外天空不要过曝，夜景尽量少噪点",
            revision = 4L,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(4L, resolution.intent.revision)
        assertEquals(
            "人脸要清楚，窗外天空不要过曝，夜景尽量少噪点",
            resolution.intent.sourceText
        )
        assertEquals(0.85f, resolution.intent.subjectDetail)
        assertEquals(0.85f, resolution.intent.highlightDetail)
        assertEquals(1.0f, resolution.intent.lowNoise)
        assertEquals(false, resolution.usedFallback)
    }

    @Test
    fun unknownNaturalLanguageFallsBackToBalancedIntent() {
        val resolution = intentResolver.resolve(
            sourceText = "帮我拍得好看一点",
            revision = 5L,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(true, resolution.usedFallback)
        assertEquals(0.7f, resolution.intent.subjectDetail)
        assertEquals(0.7f, resolution.intent.highlightDetail)
    }

    @Test
    fun parsedIntentFlowsIntoPolicyDemo() {
        val resolution = intentResolver.resolve(
            sourceText = "优先看清人物，允许背景亮一点",
            revision = 6L,
            nowEpochMs = 1_790_035_200_000L
        )
        val result = PolicyDemo.run(
            intent = resolution.intent,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(resolution.intent, result.intent)
        assertEquals(PolicyAction.EV_ONE_STEP_UP, result.proposal.action)
        assertEquals(SafetyReason.ALLOWED, result.safetyDecision.reason)
    }

    @Test
    fun motionScenarioProducesDisplayOnlyShutterProposal() {
        val result = PolicyDemo.run(
            scenario = MockPolicyScenario.MOTION_FIRST,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(PolicyAction.SET_SHUTTER, result.proposal.action)
        assertEquals(ExecutionMode.MOCK, result.proposal.executionMode)
        assertEquals(
            120.0,
            (result.proposal.parameter as ParameterTarget.Shutter).value.denominator,
            0.0
        )
        assertEquals(SafetyReason.UNSUPPORTED_PARAMETER, result.safetyDecision.reason)
    }

    @Test
    fun lowNoiseScenarioProducesDisplayOnlyIsoProposal() {
        val result = PolicyDemo.run(
            scenario = MockPolicyScenario.LOW_NOISE,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(PolicyAction.SET_ISO, result.proposal.action)
        assertEquals(400, (result.proposal.parameter as ParameterTarget.Iso).value)
        assertEquals(SafetyReason.UNSUPPORTED_PARAMETER, result.safetyDecision.reason)
    }

    @Test
    fun colorScenariosProduceWhiteBalanceOrSafeHold() {
        val natural = PolicyDemo.run(
            scenario = MockPolicyScenario.NATURAL_COLOR,
            nowEpochMs = 1_790_035_200_000L
        )
        val atmosphere = PolicyDemo.run(
            scenario = MockPolicyScenario.ATMOSPHERE_FIRST,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(PolicyAction.SET_WHITE_BALANCE, natural.proposal.action)
        assertEquals(5000, (natural.proposal.parameter as ParameterTarget.WhiteBalance).value)
        assertEquals(PolicyAction.SET_WHITE_BALANCE, atmosphere.proposal.action)
        assertEquals(3200, (atmosphere.proposal.parameter as ParameterTarget.WhiteBalance).value)
    }

    @Test
    fun unavailableModelScenarioHoldsSafely() {
        val result = PolicyDemo.run(
            scenario = MockPolicyScenario.MODEL_UNAVAILABLE,
            nowEpochMs = 1_790_035_200_000L
        )

        assertEquals(PolicyAction.HOLD, result.proposal.action)
        assertEquals("scene_semantic_unavailable", result.proposal.reason)
        assertEquals(SafetyReason.NO_ACTION, result.safetyDecision.reason)
    }

    @Test
    fun coordinatedMockSessionRequiresThreeFramesButNeverAllowsRealExecution() {
        val session = PolicyDemo.newSession()
        val intent = intentResolver.resolve(
            sourceText = "动作不要拖影，优先快门",
            revision = 7L,
            nowEpochMs = 1_790_035_200_000L
        ).intent

        val first = session.run(intent, MockPolicyScenario.MOTION_FIRST, 1_790_035_200_000L)
        val second = session.run(intent, MockPolicyScenario.MOTION_FIRST, 1_790_035_200_100L)
        val third = session.run(intent, MockPolicyScenario.MOTION_FIRST, 1_790_035_200_200L)

        assertEquals("awaiting_confirmation_1_of_3", first.temporalDecision?.reason)
        assertEquals("awaiting_confirmation_2_of_3", second.temporalDecision?.reason)
        assertEquals("confirmed", third.temporalDecision?.reason)
        assertEquals(false, third.canRequestConfirmation)
        assertEquals(SafetyReason.UNSUPPORTED_PARAMETER, third.safetyDecision.reason)
    }
}
