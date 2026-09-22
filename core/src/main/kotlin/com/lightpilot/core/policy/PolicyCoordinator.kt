package com.lightpilot.core.policy

import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.PolicyProposal
import com.lightpilot.core.model.SafetyDecision
import com.lightpilot.core.model.SafetyReason
import com.lightpilot.core.model.ExecutionMode
import com.lightpilot.core.model.InputSource

data class PolicyCycleResult(
    val candidateProposal: PolicyProposal,
    val temporalDecision: TemporalDecision,
    val safetyDecision: SafetyDecision,
    val canRequestConfirmation: Boolean
)

/**
 * Coordinates one frame's policy evaluation without owning any SDK or HTTP
 * implementation. The coordinator invalidates temporal state when the
 * camera/intent context changes and only preflights SafetyGuard after the
 * candidate survives temporal confirmation.
 */
class PolicyCoordinator(
    private val engine: PolicyEngine = PolicyEngine(),
    private val temporalController: TemporalController = TemporalController(),
    private val safetyGuard: SafetyGuard = SafetyGuard()
) {
    private var lastIntentRevision: Long? = null
    private var lastConnectionEpoch: String? = null
    private var lastCapabilityRevision: Long? = null

    fun evaluate(
        input: PolicyInput,
        commandId: String? = null
    ): PolicyCycleResult {
        resetForContextChange(input)
        val candidate = engine.propose(input)
        val temporal = temporalController.observe(
            metrics = input.metrics,
            candidateAction = candidate.action,
            candidateSignature = candidateSignature(candidate),
            nowEpochMs = input.nowEpochMs
        )

        if (candidate.action == PolicyAction.HOLD || !temporal.ready) {
            return PolicyCycleResult(
                candidateProposal = candidate,
                temporalDecision = temporal,
                safetyDecision = pendingDecision(candidate, input.nowEpochMs, temporal),
                canRequestConfirmation = false
            )
        }

        val safety = safetyGuard.evaluate(
            proposal = candidate,
            currentState = input.cameraState,
            capabilities = input.capabilities,
            currentMetrics = input.metrics,
            currentIntent = input.intent,
            nowEpochMs = input.nowEpochMs,
            commandId = commandId ?: "preflight-${candidate.proposalId}",
            userLocked = input.userLocked
        )
        val canRequestConfirmation = safety.allowed &&
            candidate.inputSource == InputSource.REAL &&
            candidate.executionMode == ExecutionMode.REAL

        return PolicyCycleResult(
            candidateProposal = candidate,
            temporalDecision = temporal,
            safetyDecision = safety,
            canRequestConfirmation = canRequestConfirmation
        )
    }

    fun reset() {
        temporalController.reset()
        safetyGuard.clearForNewConnection()
        lastIntentRevision = null
        lastConnectionEpoch = null
        lastCapabilityRevision = null
    }

    private fun resetForContextChange(input: PolicyInput) {
        val changed = lastIntentRevision != null &&
            (lastIntentRevision != input.intent.revision ||
                lastConnectionEpoch != input.cameraState.connectionEpoch ||
                lastCapabilityRevision != input.cameraState.capabilityRevision)
        if (changed) {
            temporalController.reset()
        }
        if (lastConnectionEpoch != null &&
            lastConnectionEpoch != input.cameraState.connectionEpoch
        ) {
            safetyGuard.clearForNewConnection()
        }
        lastIntentRevision = input.intent.revision
        lastConnectionEpoch = input.cameraState.connectionEpoch
        lastCapabilityRevision = input.cameraState.capabilityRevision
    }

    private fun pendingDecision(
        candidate: PolicyProposal,
        nowEpochMs: Long,
        temporal: TemporalDecision
    ): SafetyDecision {
        val message = if (candidate.action == PolicyAction.HOLD) {
            "No camera command is proposed: ${candidate.reason}"
        } else {
            "Waiting for temporal confirmation: ${temporal.reason}"
        }
        return SafetyDecision(
            allowed = false,
            reason = SafetyReason.NO_ACTION,
            message = message,
            checkedProposalId = candidate.proposalId,
            checkedAtEpochMs = nowEpochMs
        )
    }

    private fun candidateSignature(candidate: PolicyProposal): String {
        return "${candidate.action}:${candidate.parameter}"
    }
}
