package com.lightpilot.core.policy

import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.InputSource
import com.lightpilot.core.model.ParameterTarget
import com.lightpilot.core.model.PolicyDiagnostics
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.PolicyProposal
import com.lightpilot.core.model.RiskLevel
import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.model.UserIntent
import com.lightpilot.core.model.VisionMetrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PolicyConfig(
    val maxFrameAgeMs: Long = 1_500L,
    val maxSemanticAgeMs: Long = 4_000L,
    val minActionScore: Float = 0.18f,
    val minScoreMargin: Float = 0.08f,
    val subjectTargetBrightness: Float = 0.48f,
    val highlightWarningRatio: Float = 0.12f,
    val darkWarningRatio: Float = 0.35f,
    val proposalValidityMs: Long = 2_000L,
    val semanticConfidenceBonus: Float = 0.10f
) {
    init {
        require(maxFrameAgeMs >= 0L)
        require(maxSemanticAgeMs >= 0L)
        require(minActionScore in 0f..1f)
        require(minScoreMargin in 0f..1f)
        require(subjectTargetBrightness in 0f..1f)
        require(highlightWarningRatio in 0f..1f)
        require(darkWarningRatio in 0f..1f)
        require(darkWarningRatio < 1f)
        require(proposalValidityMs >= 0L)
        require(semanticConfidenceBonus in 0f..1f)
    }
}

data class PolicyInput(
    val intent: UserIntent,
    val metrics: VisionMetrics,
    val semantic: SceneSemantic,
    val cameraState: CameraState,
    val capabilities: CameraCapabilities,
    val nowEpochMs: Long,
    val userLocked: Boolean = false
)

class PolicyEngine(
    private val config: PolicyConfig = PolicyConfig()
) {
    private var proposalSequence = 0L

    fun propose(input: PolicyInput): PolicyProposal {
        val proposalId = "proposal-${++proposalSequence}"
        val source = if (
            input.metrics.source == FrameSource.MOCK ||
            input.semantic.available &&
            input.semantic.reason?.startsWith("mock", ignoreCase = true) == true
        ) {
            InputSource.MOCK
        } else {
            InputSource.REAL
        }

        if (input.nowEpochMs - input.metrics.capturedAtEpochMs > config.maxFrameAgeMs) {
            return hold(input, proposalId, source, "frame_stale")
        }
        if (!input.semantic.available) {
            return hold(input, proposalId, source, "scene_semantic_unavailable")
        }
        if (input.semantic.sourceFrameId != null &&
            input.semantic.sourceFrameId != input.metrics.frameId
        ) {
            return hold(input, proposalId, source, "scene_semantic_frame_mismatch")
        }
        if (input.semantic.expiresAtEpochMs != null &&
            input.nowEpochMs > input.semantic.expiresAtEpochMs
        ) {
            return hold(input, proposalId, source, "scene_semantic_expired")
        }
        if (input.semantic.receivedAtEpochMs > 0L &&
            input.nowEpochMs - input.semantic.receivedAtEpochMs > config.maxSemanticAgeMs
        ) {
            return hold(input, proposalId, source, "scene_semantic_stale")
        }
        if (input.cameraState.exposureProgram != ExposureProgram.AUTO) {
            return hold(input, proposalId, source, "p0_ev_policy_requires_auto_mode")
        }
        val currentEv = input.cameraState.currentEv
        if (currentEv == null ||
            input.capabilities.sortedSupportedEv.isEmpty() ||
            !supportsExposureBias(input.capabilities)
        ) {
            return hold(input, proposalId, source, "ev_capability_unavailable")
        }
        if (input.capabilities.sortedSupportedEv.none {
                abs(it - currentEv) < 1e-6
            }
        ) {
            return hold(input, proposalId, source, "current_ev_not_in_capability_list")
        }
        if (input.userLocked) {
            return hold(input, proposalId, source, "user_locked_parameters")
        }

        val subjectRisk = subjectRisk(input.metrics.subjectBrightness)
        val highlightRisk = highlightRisk(input.metrics.highlightRatio)
        val darkRisk = darkRisk(input.metrics.darkRatio)
        val semanticConfidence = (1f - (input.semantic.uncertainty ?: 0f))
            .coerceIn(0f, 1f)

        var upScore = input.intent.subjectDetail *
            (subjectRisk * 0.70f + darkRisk * 0.30f)
        var downScore = input.intent.highlightDetail * highlightRisk

        if (input.semantic.subjectType != null && subjectRisk > 0.2f) {
            upScore += input.intent.subjectDetail *
                config.semanticConfidenceBonus * semanticConfidence
        }
        if (input.semantic.brightRegionType != null && highlightRisk > 0.2f) {
            downScore += input.intent.highlightDetail *
                config.semanticConfidenceBonus * semanticConfidence
        }
        if (input.intent.atmospherePreservation > 0.7f && highlightRisk > 0.6f) {
            downScore += 0.05f * semanticConfidence
        }

        val diagnostics = PolicyDiagnostics(
            subjectRisk = subjectRisk,
            highlightRisk = highlightRisk,
            darkRisk = darkRisk,
            upScore = upScore,
            downScore = downScore,
            scoreMargin = abs(upScore - downScore),
            semanticUncertainty = input.semantic.uncertainty,
            semanticUsed = input.semantic.available
        )

        if (input.intent.exposureStability > 0.7f &&
            max(upScore, downScore) < 0.35f
        ) {
            return hold(
                input,
                proposalId,
                source,
                "exposure_stability_prefers_hold",
                diagnostics
            )
        }

        val strongest = max(upScore, downScore)
        val margin = abs(upScore - downScore)
        if (strongest < config.minActionScore || margin < config.minScoreMargin) {
            return hold(input, proposalId, source, "tradeoff_is_not_decisive", diagnostics)
        }

        val action = if (upScore > downScore) {
            PolicyAction.EV_ONE_STEP_UP
        } else {
            PolicyAction.EV_ONE_STEP_DOWN
        }
        val target = adjacentEv(
            currentEv = currentEv,
            supportedEv = input.capabilities.sortedSupportedEv,
            action = action
        )
        if (target == null) {
            return hold(input, proposalId, source, "no_legal_adjacent_ev")
        }

        val reason = if (action == PolicyAction.EV_ONE_STEP_UP) {
            "subject_priority_${formatScore(subjectRisk)}_highlight_risk_${formatScore(highlightRisk)}"
        } else {
            "highlight_priority_${formatScore(highlightRisk)}_subject_risk_${formatScore(subjectRisk)}"
        }

        return PolicyProposal(
            proposalId = proposalId,
            intentRevision = input.intent.revision,
            frameId = input.metrics.frameId,
            action = action,
            parameter = ParameterTarget.Ev(target),
            reason = reason,
            risk = riskLevel(subjectRisk, highlightRisk),
            cost = min(1f, strongest),
            validUntilEpochMs = proposalExpiry(input),
            connectionEpoch = input.cameraState.connectionEpoch,
            capabilityRevision = input.cameraState.capabilityRevision,
            inputSource = source,
            createdAtEpochMs = input.nowEpochMs,
            diagnostics = diagnostics
        )
    }

    private fun hold(
        input: PolicyInput,
        proposalId: String,
        source: InputSource,
        reason: String,
        diagnostics: PolicyDiagnostics? = null
    ): PolicyProposal {
        return PolicyProposal(
            proposalId = proposalId,
            intentRevision = input.intent.revision,
            frameId = input.metrics.frameId,
            action = PolicyAction.HOLD,
            parameter = null,
            reason = reason,
            risk = RiskLevel.LOW,
            cost = 0f,
            validUntilEpochMs = proposalExpiry(input),
            connectionEpoch = input.cameraState.connectionEpoch,
            capabilityRevision = input.cameraState.capabilityRevision,
            inputSource = source,
            createdAtEpochMs = input.nowEpochMs,
            diagnostics = diagnostics
        )
    }

    private fun proposalExpiry(input: PolicyInput): Long {
        val frameExpiry = input.metrics.expiresAtEpochMs
            ?: (input.nowEpochMs + config.maxFrameAgeMs)
        val semanticExpiry = input.semantic.expiresAtEpochMs
            ?: (input.nowEpochMs + config.maxSemanticAgeMs)
        return min(
            input.nowEpochMs + config.proposalValidityMs,
            min(frameExpiry, semanticExpiry)
        )
    }

    private fun adjacentEv(
        currentEv: Double,
        supportedEv: List<Double>,
        action: PolicyAction
    ): Double? {
        val currentIndex = supportedEv.indexOfFirst { abs(it - currentEv) < 1e-6 }
        if (currentIndex < 0) return null
        return when (action) {
            PolicyAction.EV_ONE_STEP_UP -> supportedEv.getOrNull(currentIndex + 1)
            PolicyAction.EV_ONE_STEP_DOWN -> supportedEv.getOrNull(currentIndex - 1)
            else -> null
        }
    }

    private fun supportsExposureBias(capabilities: CameraCapabilities): Boolean {
        return capabilities.supportParam.any {
            it.equals("exposureBias", ignoreCase = true) ||
                it.equals("EV", ignoreCase = true)
        }
    }

    private fun subjectRisk(brightness: Float?): Float {
        if (brightness == null) return 1f
        return ((config.subjectTargetBrightness - brightness) /
            config.subjectTargetBrightness).coerceIn(0f, 1f)
    }

    private fun highlightRisk(ratio: Float?): Float {
        if (ratio == null) return 1f
        return ((ratio - config.highlightWarningRatio) /
            (1f - config.highlightWarningRatio)).coerceIn(0f, 1f)
    }

    private fun darkRisk(ratio: Float?): Float {
        if (ratio == null) return 1f
        return ((ratio - config.darkWarningRatio) /
            (1f - config.darkWarningRatio)).coerceIn(0f, 1f)
    }

    private fun riskLevel(subjectRisk: Float, highlightRisk: Float): RiskLevel {
        val highest = max(subjectRisk, highlightRisk)
        return when {
            highest >= 0.75f -> RiskLevel.HIGH
            highest >= 0.4f -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    private fun formatScore(value: Float): String {
        return ((value * 100f).roundToInt()).toString()
    }
}
