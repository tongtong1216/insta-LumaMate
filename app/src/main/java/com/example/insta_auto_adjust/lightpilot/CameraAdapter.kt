package com.example.insta_auto_adjust.lightpilot

import kotlin.math.abs

sealed interface SetEvResult {
    data object Success : SetEvResult
    data object Timeout : SetEvResult
    data class Failure(val code: String) : SetEvResult
}

interface CameraAdapter {
    fun readCapabilities(): CameraCapabilities
    fun readState(): CameraState
    fun setEv(targetEv: Double, commandId: String): SetEvResult
    fun readCurrentEv(): Double?
    fun markStateUnknown()
    fun resyncState(): Boolean
}

/**
 * Development-only adapter. Its default MOCK capability is intentionally rejected by SafetyGuard.
 */
class FakeCameraAdapter(
    initialEv: Double = 0.0,
    private val supportedEv: List<Double> = listOf(-2.0, -1.0, 0.0, 1.0, 2.0),
    private val executionMode: ExecutionMode = ExecutionMode.MOCK,
) : CameraAdapter {
    private var state = CameraState(1, "photo", RecordingState.IDLE, false, initialEv, true)
    var nextSetResult: SetEvResult = SetEvResult.Success

    override fun readCapabilities() = CameraCapabilities(
        supportedEv = supportedEv,
        evReadable = true,
        evWritable = true,
        canWriteEvWhileRecording = false,
        executionMode = executionMode,
    )

    override fun readState() = state

    override fun setEv(targetEv: Double, commandId: String): SetEvResult {
        val result = nextSetResult
        nextSetResult = SetEvResult.Success
        if (result == SetEvResult.Success) state = state.copy(currentEv = targetEv)
        return result
    }

    override fun readCurrentEv() = state.currentEv.takeIf { state.stateKnown }

    override fun markStateUnknown() {
        state = state.copy(stateKnown = false, cameraStateRevision = state.cameraStateRevision + 1)
    }

    override fun resyncState(): Boolean {
        state = state.copy(stateKnown = true, cameraStateRevision = state.cameraStateRevision + 1)
        return true
    }
}

class SafetyGuard {
    private val attemptedCommands = mutableSetOf<String>()

    @Synchronized
    fun checkAndReserve(
        proposal: PolicyProposal,
        confirmedProposalId: String?,
        commandId: String,
        currentIntentRevision: Long,
        cameraState: CameraState,
        capabilities: CameraCapabilities,
        nowMs: Long,
    ): SafetyDecision {
        val rejection = when {
            proposal.action == ExposureAction.HOLD -> SafetyRejection.HOLD_ACTION
            confirmedProposalId != proposal.proposalId -> SafetyRejection.USER_NOT_CONFIRMED
            proposal.intentRevision != currentIntentRevision -> SafetyRejection.STALE_INTENT
            proposal.cameraStateRevision != cameraState.cameraStateRevision ->
                SafetyRejection.STALE_CAMERA_STATE
            nowMs > proposal.expiresAtMs -> SafetyRejection.EXPIRED
            !cameraState.stateKnown -> SafetyRejection.CAMERA_STATE_UNKNOWN
            cameraState.isBusy -> SafetyRejection.CAMERA_BUSY
            cameraState.recordingState == RecordingState.UNKNOWN -> SafetyRejection.CAMERA_STATE_UNKNOWN
            cameraState.recordingState == RecordingState.RECORDING &&
                !capabilities.canWriteEvWhileRecording -> SafetyRejection.RECORDING_WRITE_UNSUPPORTED
            !capabilities.evReadable -> SafetyRejection.EV_NOT_READABLE
            !capabilities.evWritable -> SafetyRejection.EV_NOT_WRITABLE
            proposal.targetEv == null || capabilities.supportedEv.none {
                abs(it - proposal.targetEv) < 1e-6
            } -> SafetyRejection.ILLEGAL_TARGET
            commandId in attemptedCommands -> SafetyRejection.DUPLICATE_COMMAND
            proposal.semanticStatus != SemanticStatus.OK -> SafetyRejection.SEMANTIC_NOT_OK
            proposal.executionMode != ExecutionMode.REAL ||
                capabilities.executionMode != ExecutionMode.REAL -> SafetyRejection.MOCK_EXECUTION
            else -> null
        }
        if (rejection != null) return SafetyDecision(false, rejection)
        attemptedCommands += commandId
        return SafetyDecision(true)
    }
}

class CameraExecutor(
    private val adapter: CameraAdapter,
    private val safetyGuard: SafetyGuard,
) {
    fun execute(
        proposal: PolicyProposal,
        confirmedProposalId: String?,
        commandId: String,
        currentIntentRevision: Long,
        nowMs: Long,
    ): ExecutionRecord {
        val state = adapter.readState()
        val capabilities = adapter.readCapabilities()
        val decision = safetyGuard.checkAndReserve(
            proposal, confirmedProposalId, commandId, currentIntentRevision,
            state, capabilities, nowMs,
        )
        if (!decision.allowed) {
            return ExecutionRecord(commandId, proposal.proposalId, false, state.currentEv,
                proposal.targetEv, null, decision.rejection!!.name)
        }
        val before = adapter.readCurrentEv()
        return when (val result = adapter.setEv(proposal.targetEv!!, commandId)) {
            SetEvResult.Success -> {
                val readback = adapter.readCurrentEv()
                val success = readback != null && abs(readback - proposal.targetEv) < 1e-6
                ExecutionRecord(commandId, proposal.proposalId, success, before, proposal.targetEv,
                    readback, if (success) "OK" else "READBACK_MISMATCH")
            }
            SetEvResult.Timeout -> {
                adapter.markStateUnknown()
                ExecutionRecord(commandId, proposal.proposalId, false, before, proposal.targetEv,
                    null, "SET_TIMEOUT_STATE_UNKNOWN")
            }
            is SetEvResult.Failure -> ExecutionRecord(commandId, proposal.proposalId, false, before,
                proposal.targetEv, adapter.readCurrentEv(), result.code)
        }
    }
}
