package com.example.insta_auto_adjust.lightpilot

data class AdvancedTemporalDecision(
    val stableProposal: AdvancedPolicyProposal?,
    val observedFrames: Int,
    val requiredFrames: Int,
    val reasonCode: AdvancedReasonCode,
)

class AdvancedTemporalController {
    private var lastTarget: String? = null
    private var lastStage: AdvancedStage? = null
    private var lastIntentRevision: Long? = null
    private var lastCameraRevision: Long? = null
    private var observed = 0
    private var cooldownUntilMs = 0L

    @Synchronized
    fun observe(
        proposal: AdvancedPolicyProposal,
        preference: StabilityPreference,
        nowMs: Long,
    ): AdvancedTemporalDecision {
        val required = if (preference == StabilityPreference.HIGH) 5 else 3
        if (proposal.action == AdvancedAction.HOLD) {
            resetSequence()
            return AdvancedTemporalDecision(proposal, 0, required, proposal.reasonCode)
        }
        if (nowMs < cooldownUntilMs) {
            resetSequence()
            return AdvancedTemporalDecision(null, 0, required,
                AdvancedReasonCode.COOLDOWN_ACTIVE)
        }
        val same = lastTarget == proposal.targetSignature && lastStage == proposal.stage &&
            lastIntentRevision == proposal.intentRevision &&
            lastCameraRevision == proposal.cameraStateRevision
        observed = if (same) observed + 1 else 1
        lastTarget = proposal.targetSignature
        lastStage = proposal.stage
        lastIntentRevision = proposal.intentRevision
        lastCameraRevision = proposal.cameraStateRevision
        return if (observed >= required) {
            resetSequence()
            AdvancedTemporalDecision(proposal, required, required, proposal.reasonCode)
        } else {
            AdvancedTemporalDecision(null, observed, required,
                AdvancedReasonCode.TEMPORAL_CONFIRMATION_PENDING)
        }
    }

    @Synchronized
    fun onAcknowledged(preference: StabilityPreference, nowMs: Long) {
        cooldownUntilMs = nowMs + if (preference == StabilityPreference.HIGH) 5_000 else 3_000
        resetSequence()
    }

    @Synchronized
    fun reset() {
        cooldownUntilMs = 0
        resetSequence()
    }

    private fun resetSequence() {
        lastTarget = null
        lastStage = null
        lastIntentRevision = null
        lastCameraRevision = null
        observed = 0
    }
}

/** Explicitly proves that Stage 2/3 planning output cannot enter the real camera path. */
object AdvancedSafetyGuard {
    fun check(proposal: AdvancedPolicyProposal): SafetyDecision = when {
        proposal.action == AdvancedAction.HOLD -> SafetyDecision(false, SafetyRejection.HOLD_ACTION)
        proposal.executionMode != ExecutionMode.REAL || proposal.inputSource != InputSource.REAL ->
            SafetyDecision(false, SafetyRejection.MOCK_EXECUTION)
        else -> SafetyDecision(false, SafetyRejection.MOCK_EXECUTION)
    }
}
