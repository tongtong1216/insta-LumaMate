package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {
    @Test
    fun mockModeCanNeverExecute() {
        val adapter = FakeCameraAdapter()
        val result = CameraExecutor(adapter, SafetyGuard()).execute(
            proposal(ExecutionMode.MOCK), "proposal", "command", 2, 1_000)
        assertFalse(result.success)
        assertEquals("MOCK_EXECUTION", result.message)
        assertEquals(0.0, adapter.readCurrentEv()!!, 0.0)
    }

    @Test
    fun confirmedRealProposalExecutesAndReadsBack() {
        val adapter = FakeCameraAdapter(executionMode = ExecutionMode.REAL)
        val result = CameraExecutor(adapter, SafetyGuard()).execute(
            proposal(ExecutionMode.REAL), "proposal", "command", 2, 1_000)
        assertTrue(result.success)
        assertEquals(0.0, result.beforeEv!!, 0.0)
        assertEquals(1.0, result.targetEv!!, 0.0)
        assertEquals(1.0, result.readbackEv!!, 0.0)
    }

    @Test
    fun duplicateCommandIsRejectedAndTimeoutMarksStateUnknown() {
        val adapter = FakeCameraAdapter(executionMode = ExecutionMode.REAL)
        val executor = CameraExecutor(adapter, SafetyGuard())
        executor.execute(proposal(ExecutionMode.REAL), "proposal", "same", 2, 1_000)
        val duplicate = executor.execute(proposal(ExecutionMode.REAL), "proposal", "same", 2, 1_000)
        assertEquals("DUPLICATE_COMMAND", duplicate.message)

        val timeoutAdapter = FakeCameraAdapter(executionMode = ExecutionMode.REAL)
        timeoutAdapter.nextSetResult = SetEvResult.Timeout
        val timeout = CameraExecutor(timeoutAdapter, SafetyGuard()).execute(
            proposal(ExecutionMode.REAL), "proposal", "timeout", 2, 1_000)
        assertEquals("SET_TIMEOUT_STATE_UNKNOWN", timeout.message)
        assertFalse(timeoutAdapter.readState().stateKnown)
        assertTrue(timeoutAdapter.resyncState())
        assertTrue(timeoutAdapter.readState().stateKnown)
    }

    @Test
    fun rejectsEveryStaleBusyRecordingAndIllegalState() {
        val baseProposal = proposal(ExecutionMode.REAL)
        val baseState = CameraState(1, "photo", RecordingState.IDLE, false, 0.0, true)
        val baseCapabilities = CameraCapabilities(listOf(-1.0, 0.0, 1.0), true, true,
            false, ExecutionMode.REAL)

        fun reject(
            expected: SafetyRejection,
            candidate: PolicyProposal = baseProposal,
            confirmed: String? = "proposal",
            intentRevision: Long = 2,
            state: CameraState = baseState,
            capabilities: CameraCapabilities = baseCapabilities,
            nowMs: Long = 1_000,
        ) {
            val decision = SafetyGuard().checkAndReserve(candidate, confirmed, expected.name,
                intentRevision, state, capabilities, nowMs)
            assertFalse(decision.allowed)
            assertEquals(expected, decision.rejection)
        }

        reject(SafetyRejection.USER_NOT_CONFIRMED, confirmed = null)
        reject(SafetyRejection.STALE_INTENT, intentRevision = 3)
        reject(SafetyRejection.STALE_CAMERA_STATE,
            state = baseState.copy(cameraStateRevision = 2))
        reject(SafetyRejection.EXPIRED, nowMs = 5_001)
        reject(SafetyRejection.CAMERA_STATE_UNKNOWN, state = baseState.copy(stateKnown = false))
        reject(SafetyRejection.CAMERA_BUSY, state = baseState.copy(isBusy = true))
        reject(SafetyRejection.RECORDING_WRITE_UNSUPPORTED,
            state = baseState.copy(recordingState = RecordingState.RECORDING))
        reject(SafetyRejection.EV_NOT_READABLE,
            capabilities = baseCapabilities.copy(evReadable = false))
        reject(SafetyRejection.EV_NOT_WRITABLE,
            capabilities = baseCapabilities.copy(evWritable = false))
        reject(SafetyRejection.ILLEGAL_TARGET,
            candidate = baseProposal.copy(targetEv = 0.25))
        reject(SafetyRejection.SEMANTIC_NOT_OK,
            candidate = baseProposal.copy(semanticStatus = SemanticStatus.UNAVAILABLE))
    }

    private fun proposal(mode: ExecutionMode) = PolicyProposal(
        proposalId = "proposal", intentRevision = 2, cameraStateRevision = 1,
        semanticFrameId = 1, metricsFrameId = 1, semanticStatus = SemanticStatus.OK,
        executionMode = mode, action = ExposureAction.EV_ONE_STEP_UP, targetEv = 1.0,
        reasonCode = ReasonCode.SUBJECT_TOO_DARK, reason = "test", risk = RiskLevel.MEDIUM,
        createdAtMs = 0, expiresAtMs = 5_000,
    )
}
