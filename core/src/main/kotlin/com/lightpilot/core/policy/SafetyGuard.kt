package com.lightpilot.core.policy

import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.ParameterTarget
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.PolicyProposal
import com.lightpilot.core.model.RecordingState
import com.lightpilot.core.model.SafetyDecision
import com.lightpilot.core.model.SafetyReason
import com.lightpilot.core.model.UserIntent
import com.lightpilot.core.model.VisionMetrics
import kotlin.math.abs

class SafetyGuard(
    private val maxFrameAgeMs: Long = 1_500L
) {
    private val executedCommands = mutableSetOf<String>()

    init {
        require(maxFrameAgeMs >= 0L)
    }

    fun evaluate(
        proposal: PolicyProposal,
        currentState: CameraState,
        capabilities: CameraCapabilities,
        currentMetrics: VisionMetrics,
        currentIntent: UserIntent,
        nowEpochMs: Long,
        commandId: String?,
        userLocked: Boolean = false
    ): SafetyDecision {
        if (proposal.action == PolicyAction.HOLD) {
            return decision(
                allowed = false,
                reason = SafetyReason.NO_ACTION,
                message = "No camera command is proposed",
                proposal = proposal,
                nowEpochMs = nowEpochMs
            )
        }
        val normalizedCommandId = commandId?.takeIf { it.isNotBlank() }
            ?: return decision(
                false,
                SafetyReason.MISSING_COMMAND_ID,
                "Command id is required",
                proposal,
                nowEpochMs
            )
        if (nowEpochMs > proposal.validUntilEpochMs) {
            return decision(false, SafetyReason.EXPIRED_PROPOSAL, "Proposal expired", proposal, nowEpochMs)
        }
        if (proposal.intentRevision != currentIntent.revision) {
            return decision(false, SafetyReason.STALE_INTENT, "Intent revision changed", proposal, nowEpochMs)
        }
        if (proposal.connectionEpoch != currentState.connectionEpoch) {
            return decision(false, SafetyReason.CONNECTION_CHANGED, "Connection epoch changed", proposal, nowEpochMs)
        }
        if (proposal.capabilityRevision != currentState.capabilityRevision ||
            proposal.capabilityRevision != capabilities.capabilityRevision
        ) {
            return decision(false, SafetyReason.STALE_CAPABILITY, "Capability revision changed", proposal, nowEpochMs)
        }
        if (proposal.frameId != currentMetrics.frameId ||
            nowEpochMs - currentMetrics.capturedAtEpochMs > maxFrameAgeMs ||
            currentMetrics.expiresAtEpochMs != null &&
            nowEpochMs > currentMetrics.expiresAtEpochMs
        ) {
            return decision(false, SafetyReason.STALE_FRAME, "Frame is stale or changed", proposal, nowEpochMs)
        }
        if (userLocked) {
            return decision(false, SafetyReason.USER_LOCKED, "User locked camera parameters", proposal, nowEpochMs)
        }
        if (currentState.isBusy || currentState.isPreRecording == true) {
            return decision(false, SafetyReason.CAMERA_BUSY, "Camera is busy", proposal, nowEpochMs)
        }
        if (currentState.isWorking == null) {
            return decision(false, SafetyReason.UNKNOWN_CAMERA_STATE, "Camera working state is unknown", proposal, nowEpochMs)
        }
        if (currentState.isWorking == true) {
            return decision(false, SafetyReason.CAMERA_BUSY, "Camera reports working", proposal, nowEpochMs)
        }
        if (currentState.recordingState != RecordingState.IDLE) {
            return decision(false, SafetyReason.RECORDING, "Camera is not idle", proposal, nowEpochMs)
        }
        if (executedCommands.contains(normalizedCommandId)) {
            return decision(false, SafetyReason.DUPLICATE_COMMAND, "Command was already evaluated", proposal, nowEpochMs)
        }

        val target = proposal.parameter
        when (proposal.action) {
            PolicyAction.EV_ONE_STEP_UP,
            PolicyAction.EV_ONE_STEP_DOWN -> {
                if (currentState.exposureProgram != ExposureProgram.AUTO) {
                    return decision(false, SafetyReason.UNSUPPORTED_PARAMETER, "EV proposal requires Auto mode", proposal, nowEpochMs)
                }
                if (!supportsExposureBias(capabilities)) {
                    return decision(false, SafetyReason.UNSUPPORTED_PARAMETER, "Exposure bias is not supported", proposal, nowEpochMs)
                }
                val ev = target as? ParameterTarget.Ev
                    ?: return decision(false, SafetyReason.ILLEGAL_TARGET, "EV target is missing", proposal, nowEpochMs)
                val currentEv = currentState.currentEv
                    ?: return decision(false, SafetyReason.UNKNOWN_CAMERA_STATE, "Current EV is unknown", proposal, nowEpochMs)
                val supportedEv = capabilities.sortedSupportedEv
                if (supportedEv.none { abs(it - ev.value) < 1e-6 }) {
                    return decision(false, SafetyReason.ILLEGAL_TARGET, "EV target is not supported", proposal, nowEpochMs)
                }
                if (supportedEv.none { abs(it - currentEv) < 1e-6 }) {
                    return decision(false, SafetyReason.UNKNOWN_CAMERA_STATE, "Current EV is not in the capability list", proposal, nowEpochMs)
                }
                val currentIndex = supportedEv.indexOfFirst { abs(it - currentEv) < 1e-6 }
                val targetIndex = supportedEv.indexOfFirst { abs(it - ev.value) < 1e-6 }
                val indexDelta = targetIndex - currentIndex
                if (proposal.action == PolicyAction.EV_ONE_STEP_UP &&
                    indexDelta != 1
                ) {
                    return decision(false, SafetyReason.ILLEGAL_TARGET, "EV target is not the next legal step up", proposal, nowEpochMs)
                }
                if (proposal.action == PolicyAction.EV_ONE_STEP_DOWN &&
                    indexDelta != -1
                ) {
                    return decision(false, SafetyReason.ILLEGAL_TARGET, "EV target is not the next legal step down", proposal, nowEpochMs)
                }
            }
            PolicyAction.SET_SHUTTER,
            PolicyAction.SET_ISO,
            PolicyAction.SET_WHITE_BALANCE -> {
                return decision(
                    false,
                    SafetyReason.UNSUPPORTED_PARAMETER,
                    "Advanced parameter execution is not enabled in P0",
                    proposal,
                    nowEpochMs
                )
            }
            PolicyAction.HOLD -> Unit
        }

        executedCommands.add(normalizedCommandId)
        return decision(true, SafetyReason.ALLOWED, "Proposal passed safety checks", proposal, nowEpochMs)
    }

    fun clearForNewConnection() {
        executedCommands.clear()
    }

    private fun supportsExposureBias(capabilities: CameraCapabilities): Boolean {
        return capabilities.supportParam.any {
            it.equals("exposureBias", ignoreCase = true) ||
                it.equals("exposure_bias", ignoreCase = true) ||
                it.equals("exposurebias", ignoreCase = true) ||
                it.equals("EV", ignoreCase = true)
        }
    }

    private fun decision(
        allowed: Boolean,
        reason: SafetyReason,
        message: String,
        proposal: PolicyProposal,
        nowEpochMs: Long
    ): SafetyDecision {
        return SafetyDecision(
            allowed = allowed,
            reason = reason,
            message = message,
            checkedProposalId = proposal.proposalId,
            checkedAtEpochMs = nowEpochMs
        )
    }
}
