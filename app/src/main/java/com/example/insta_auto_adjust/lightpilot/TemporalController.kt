package com.example.insta_auto_adjust.lightpilot

data class TemporalDecision(
    val stableProposal: PolicyProposal?,
    val observedFrames: Int,
    val requiredFrames: Int,
    val reasonCode: ReasonCode,
)

class TemporalController {
    private var lastAction: ExposureAction? = null
    private var lastIntentRevision: Long? = null
    private var lastCameraRevision: Long? = null
    private var observed = 0
    private var cooldownUntilMs = 0L

    @Synchronized
    fun observe(proposal: PolicyProposal, preference: StabilityPreference, nowMs: Long): TemporalDecision {
        val required = if (preference == StabilityPreference.HIGH) 5 else 3
        if (proposal.action == ExposureAction.HOLD) {
            resetSequence()
            return TemporalDecision(proposal, 0, required, proposal.reasonCode)
        }
        if (nowMs < cooldownUntilMs) {
            resetSequence()
            return TemporalDecision(null, 0, required, ReasonCode.COOLDOWN_ACTIVE)
        }
        val sameSequence = lastAction == proposal.action &&
            lastIntentRevision == proposal.intentRevision &&
            lastCameraRevision == proposal.cameraStateRevision
        observed = if (sameSequence) observed + 1 else 1
        lastAction = proposal.action
        lastIntentRevision = proposal.intentRevision
        lastCameraRevision = proposal.cameraStateRevision
        return if (observed >= required) {
            resetSequence()
            TemporalDecision(proposal, required, required, proposal.reasonCode)
        } else {
            TemporalDecision(null, observed, required, ReasonCode.TEMPORAL_CONFIRMATION_PENDING)
        }
    }

    @Synchronized
    fun onExecuted(preference: StabilityPreference, nowMs: Long) {
        cooldownUntilMs = nowMs + if (preference == StabilityPreference.HIGH) 5_000 else 3_000
        resetSequence()
    }

    @Synchronized
    fun reset() {
        cooldownUntilMs = 0
        resetSequence()
    }

    private fun resetSequence() {
        lastAction = null
        lastIntentRevision = null
        lastCameraRevision = null
        observed = 0
    }
}
