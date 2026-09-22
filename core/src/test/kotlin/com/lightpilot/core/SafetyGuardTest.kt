package com.lightpilot.core

import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.SafetyReason
import com.lightpilot.core.policy.PolicyEngine
import com.lightpilot.core.policy.PolicyInput
import com.lightpilot.core.policy.SafetyGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyGuardTest {
    @Test
    fun acceptsLegalFreshEvProposal() {
        val metrics = TestFixtures.metrics()
        val intent = TestFixtures.intent()
        val state = TestFixtures.cameraState()
        val capabilities = TestFixtures.capabilities()
        val proposal = PolicyEngine().propose(
            PolicyInput(intent, metrics, TestFixtures.semantic(), state, capabilities, TestFixtures.NOW)
        )

        val decision = SafetyGuard().evaluate(
            proposal = proposal,
            currentState = state,
            capabilities = capabilities,
            currentMetrics = metrics,
            currentIntent = intent,
            nowEpochMs = TestFixtures.NOW,
            commandId = "command-001"
        )

        assertTrue(decision.allowed)
        assertEquals(SafetyReason.ALLOWED, decision.reason)
    }

    @Test
    fun rejectsBusyCamera() {
        val metrics = TestFixtures.metrics()
        val intent = TestFixtures.intent()
        val state = TestFixtures.cameraState(isBusy = true)
        val capabilities = TestFixtures.capabilities()
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent,
                metrics,
                TestFixtures.semantic(),
                state.copy(isBusy = false),
                capabilities,
                TestFixtures.NOW
            )
        )

        val decision = SafetyGuard().evaluate(
            proposal,
            state,
            capabilities,
            metrics,
            intent,
            TestFixtures.NOW,
            "command-002"
        )

        assertFalse(decision.allowed)
        assertEquals(SafetyReason.CAMERA_BUSY, decision.reason)
    }

    @Test
    fun rejectsStaleIntentAndDuplicateCommand() {
        val metrics = TestFixtures.metrics()
        val intent = TestFixtures.intent(revision = 1L)
        val state = TestFixtures.cameraState()
        val capabilities = TestFixtures.capabilities()
        val proposal = PolicyEngine().propose(
            PolicyInput(intent, metrics, TestFixtures.semantic(), state, capabilities, TestFixtures.NOW)
        )
        val guard = SafetyGuard()

        val stale = guard.evaluate(
            proposal,
            state,
            capabilities,
            metrics,
            intent.copy(revision = 2L),
            TestFixtures.NOW,
            "command-003"
        )
        val first = guard.evaluate(
            proposal,
            state,
            capabilities,
            metrics,
            intent,
            TestFixtures.NOW,
            "command-004"
        )
        val duplicate = guard.evaluate(
            proposal,
            state,
            capabilities,
            metrics,
            intent,
            TestFixtures.NOW,
            "command-004"
        )

        assertEquals(SafetyReason.STALE_INTENT, stale.reason)
        assertTrue(first.allowed)
        assertFalse(duplicate.allowed)
        assertEquals(SafetyReason.DUPLICATE_COMMAND, duplicate.reason)
    }

    @Test
    fun rejectsAdvancedActionInP0() {
        val guard = SafetyGuard()
        val state = TestFixtures.cameraState()
        val capabilities = TestFixtures.capabilities()
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = TestFixtures.intent(),
                metrics = TestFixtures.metrics(),
                semantic = TestFixtures.semantic(),
                cameraState = state,
                capabilities = capabilities,
                nowEpochMs = TestFixtures.NOW
            )
        ).copy(
            action = PolicyAction.SET_ISO,
            parameter = com.lightpilot.core.model.ParameterTarget.Iso(200)
        )

        val decision = guard.evaluate(
            proposal,
            state.copy(exposureProgram = ExposureProgram.MANUAL),
            capabilities,
            TestFixtures.metrics(),
            TestFixtures.intent(),
            TestFixtures.NOW,
            "command-005"
        )

        assertFalse(decision.allowed)
        assertEquals(SafetyReason.UNSUPPORTED_PARAMETER, decision.reason)
    }

    @Test
    fun rejectsEvTargetThatSkipsALegalStep() {
        val metrics = TestFixtures.metrics()
        val intent = TestFixtures.intent()
        val state = TestFixtures.cameraState(currentEv = 0.0)
        val capabilities = TestFixtures.capabilities()
        val proposal = PolicyEngine().propose(
            PolicyInput(intent, metrics, TestFixtures.semantic(), state, capabilities, TestFixtures.NOW)
        ).copy(
            parameter = com.lightpilot.core.model.ParameterTarget.Ev(2.0),
            action = PolicyAction.EV_ONE_STEP_UP
        )

        val decision = SafetyGuard().evaluate(
            proposal,
            state,
            capabilities,
            metrics,
            intent,
            TestFixtures.NOW,
            "command-006"
        )

        assertFalse(decision.allowed)
        assertEquals(SafetyReason.ILLEGAL_TARGET, decision.reason)
    }

    @Test
    fun rejectsUnknownCurrentEv() {
        val metrics = TestFixtures.metrics()
        val intent = TestFixtures.intent()
        val state = TestFixtures.cameraState(currentEv = 0.0)
        val capabilities = TestFixtures.capabilities()
        val proposal = PolicyEngine().propose(
            PolicyInput(intent, metrics, TestFixtures.semantic(), state, capabilities, TestFixtures.NOW)
        )

        val decision = SafetyGuard().evaluate(
            proposal,
            state.copy(currentEv = null),
            capabilities,
            metrics,
            intent,
            TestFixtures.NOW,
            "command-007"
        )

        assertFalse(decision.allowed)
        assertEquals(SafetyReason.UNKNOWN_CAMERA_STATE, decision.reason)
    }

    @Test
    fun requiresCommandIdBeforeARealExecution() {
        val metrics = TestFixtures.metrics()
        val intent = TestFixtures.intent()
        val state = TestFixtures.cameraState()
        val capabilities = TestFixtures.capabilities()
        val proposal = PolicyEngine().propose(
            PolicyInput(intent, metrics, TestFixtures.semantic(), state, capabilities, TestFixtures.NOW)
        )

        val decision = SafetyGuard().evaluate(
            proposal,
            state,
            capabilities,
            metrics,
            intent,
            TestFixtures.NOW,
            commandId = null
        )

        assertFalse(decision.allowed)
        assertEquals(SafetyReason.MISSING_COMMAND_ID, decision.reason)
    }
}
