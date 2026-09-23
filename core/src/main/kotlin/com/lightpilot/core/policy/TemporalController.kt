package com.lightpilot.core.policy

import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.VisionMetrics

data class TemporalDecision(
    val metrics: VisionMetrics,
    val action: PolicyAction,
    val ready: Boolean,
    val reason: String
)

class TemporalController(
    private val smoothingAlpha: Float = 0.35f,
    private val confirmationFrames: Int = 3,
    private val cooldownMs: Long = 3_000L
) {
    private var smoothed: VisionMetrics? = null
    private var pendingAction: PolicyAction? = null
    private var pendingSignature: String? = null
    private var pendingCount = 0
    private var lastCommittedAtMs: Long? = null

    init {
        require(smoothingAlpha in 0f..1f)
        require(confirmationFrames > 0)
        require(cooldownMs >= 0L)
    }

    fun smooth(metrics: VisionMetrics): VisionMetrics {
        val previous = smoothed
        if (previous == null) {
            smoothed = metrics
            return metrics
        }

        val result = metrics.copy(
            subjectBrightness = blend(previous.subjectBrightness, metrics.subjectBrightness),
            backgroundBrightness = blend(previous.backgroundBrightness, metrics.backgroundBrightness),
            highlightRatio = blend(previous.highlightRatio, metrics.highlightRatio),
            darkRatio = blend(previous.darkRatio, metrics.darkRatio),
            motionScore = blend(previous.motionScore, metrics.motionScore)
        )
        smoothed = result
        return result
    }

    fun observe(
        metrics: VisionMetrics,
        candidateAction: PolicyAction,
        nowEpochMs: Long,
        candidateSignature: String = candidateAction.name,
        requiredConfirmationFrames: Int = confirmationFrames,
        requiredCooldownMs: Long = cooldownMs
    ): TemporalDecision {
        require(requiredConfirmationFrames > 0)
        require(requiredCooldownMs >= 0L)
        val filtered = smooth(metrics)
        if (candidateAction == PolicyAction.HOLD) {
            resetPending()
            return TemporalDecision(
                metrics = filtered,
                action = PolicyAction.HOLD,
                ready = true,
                reason = "candidate_hold"
            )
        }

        val lastCommit = lastCommittedAtMs
        if (lastCommit != null && nowEpochMs - lastCommit < requiredCooldownMs) {
            resetPending()
            return TemporalDecision(
                metrics = filtered,
                action = PolicyAction.HOLD,
                ready = false,
                reason = "cooldown"
            )
        }

        if (candidateAction != pendingAction || candidateSignature != pendingSignature) {
            pendingAction = candidateAction
            pendingSignature = candidateSignature
            pendingCount = 1
        } else {
            pendingCount++
        }

        if (pendingCount < requiredConfirmationFrames) {
            return TemporalDecision(
                metrics = filtered,
                action = PolicyAction.HOLD,
                ready = false,
                reason = "awaiting_confirmation_${pendingCount}_of_$requiredConfirmationFrames"
            )
        }

        lastCommittedAtMs = nowEpochMs
        resetPending()
        return TemporalDecision(
            metrics = filtered,
            action = candidateAction,
            ready = true,
            reason = "confirmed"
        )
    }

    fun reset() {
        smoothed = null
        resetPending()
        lastCommittedAtMs = null
    }

    private fun resetPending() {
        pendingAction = null
        pendingSignature = null
        pendingCount = 0
    }

    private fun blend(previous: Float?, current: Float?): Float? {
        if (previous == null) return current
        if (current == null) return previous
        return previous + smoothingAlpha * (current - previous)
    }
}
