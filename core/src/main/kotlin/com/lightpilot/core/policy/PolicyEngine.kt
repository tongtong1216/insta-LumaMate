package com.lightpilot.core.policy

import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.ExecutionMode
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
    val semanticConfidenceBonus: Float = 0.10f,
    val advancedIntentMinScore: Float = 0.70f,
    val advancedIntentMargin: Float = 0.10f,
    val advancedExposurePriorityMargin: Float = 0.15f
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
        require(advancedIntentMinScore in 0f..1f)
        require(advancedIntentMargin in 0f..1f)
        require(advancedExposurePriorityMargin in 0f..1f)
    }
}

data class PolicyInput(
    val intent: UserIntent,
    val metrics: VisionMetrics,
    val semantic: SceneSemantic,
    val cameraState: CameraState,
    val capabilities: CameraCapabilities,
    val nowEpochMs: Long,
    val userLocked: Boolean = false,
    val inputSource: InputSource = InputSource.REAL,
    val executionMode: ExecutionMode = ExecutionMode.REAL
)

class PolicyEngine(
    private val config: PolicyConfig = PolicyConfig()
) {
    private var proposalSequence = 0L

    fun propose(input: PolicyInput): PolicyProposal {
        val proposalId = "proposal-${++proposalSequence}"
        val source = if (
            input.inputSource == InputSource.MOCK ||
            input.executionMode == ExecutionMode.MOCK ||
            input.metrics.source == FrameSource.MOCK
        ) {
            InputSource.MOCK
        } else {
            InputSource.REAL
        }

        if (input.nowEpochMs < input.metrics.capturedAtEpochMs ||
            input.nowEpochMs - input.metrics.capturedAtEpochMs > config.maxFrameAgeMs
        ) {
            return hold(input, proposalId, source, "frame_stale")
        }
        if (!input.semantic.available) {
            return hold(input, proposalId, source, "scene_semantic_unavailable")
        }
        if (input.semantic.intentRevision != null &&
            input.semantic.intentRevision != input.intent.revision
        ) {
            return hold(input, proposalId, source, "scene_semantic_intent_mismatch")
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

        val exposurePreference = max(
            input.intent.subjectDetail,
            input.intent.highlightDetail
        )
        val advancedDecision = advancedIntentDecision(input)
        val exposureScores = exposureScores(input, subjectRisk, highlightRisk, darkRisk, semanticConfidence)
        val exposureStrong = isDecisive(exposureScores.up, exposureScores.down)

        if (advancedDecision.ambiguous &&
            advancedDecision.score >= config.advancedIntentMinScore &&
            !exposureStrong
        ) {
            return hold(
                input,
                proposalId,
                source,
                "advanced_tradeoff_not_decisive",
                diagnostics
            )
        }

        val advancedPreferred = advancedDecision.kind != null &&
            advancedDecision.score >= config.advancedIntentMinScore &&
            advancedDecision.score >= exposurePreference + config.advancedExposurePriorityMargin

        if (advancedPreferred) {
            return proposeAdvanced(
                input = input,
                proposalId = proposalId,
                source = source,
                diagnostics = diagnostics,
                decision = advancedDecision
            )
        }

        if (input.cameraState.exposureProgram != ExposureProgram.AUTO) {
            return hold(input, proposalId, source, "p0_ev_policy_requires_auto_mode", diagnostics)
        }

        val currentEv = input.cameraState.currentEv
        if (currentEv == null ||
            input.capabilities.sortedSupportedEv.isEmpty() ||
            !supportsExposureBias(input.capabilities)
        ) {
            return hold(input, proposalId, source, "ev_capability_unavailable", diagnostics)
        }
        if (input.capabilities.sortedSupportedEv.none {
                abs(it - currentEv) < 1e-6
            }
        ) {
            return hold(input, proposalId, source, "current_ev_not_in_capability_list", diagnostics)
        }

        if (input.intent.exposureStability > 0.7f &&
            max(exposureScores.up, exposureScores.down) < 0.35f
        ) {
            return hold(
                input,
                proposalId,
                source,
                "exposure_stability_prefers_hold",
                diagnostics
            )
        }

        val strongest = max(exposureScores.up, exposureScores.down)
        if (!exposureStrong) {
            return hold(input, proposalId, source, "tradeoff_is_not_decisive", diagnostics)
        }

        val action = if (exposureScores.up > exposureScores.down) {
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
            diagnostics = diagnostics,
            executionMode = executionMode(input, source)
        )
    }

    private fun proposeAdvanced(
        input: PolicyInput,
        proposalId: String,
        source: InputSource,
        diagnostics: PolicyDiagnostics,
        decision: AdvancedIntentDecision
    ): PolicyProposal {
        if (executionMode(input, source) != ExecutionMode.MOCK) {
            return hold(
                input,
                proposalId,
                source,
                "advanced_parameter_requires_verified_execution",
                diagnostics
            )
        }

        val target = when (decision.kind) {
            AdvancedIntentKind.MOTION_CLARITY ->
                nextFasterShutter(input.cameraState.currentShutterSpeed, input.capabilities)
                    ?.let { PolicyAction.SET_SHUTTER to ParameterTarget.Shutter(it) }
            AdvancedIntentKind.LOW_NOISE ->
                nextLowerIso(input.cameraState.currentIso, input.capabilities)
                    ?.let { PolicyAction.SET_ISO to ParameterTarget.Iso(it) }
            AdvancedIntentKind.COLOR_NEUTRALITY ->
                chooseWhiteBalanceTarget(
                    current = input.cameraState.currentWhiteBalance,
                    capabilities = input.capabilities,
                    desired = 5_000
                )?.let { PolicyAction.SET_WHITE_BALANCE to ParameterTarget.WhiteBalance(it) }
            AdvancedIntentKind.ATMOSPHERE_PRESERVATION -> {
                if (input.semantic.coloredLight != true) {
                    null
                } else {
                    chooseWhiteBalanceTarget(
                        current = input.cameraState.currentWhiteBalance,
                        capabilities = input.capabilities,
                        desired = 3_200
                    )?.let { PolicyAction.SET_WHITE_BALANCE to ParameterTarget.WhiteBalance(it) }
                }
            }
            null -> null
        }

        if (target == null) {
            val reason = when (decision.kind) {
                AdvancedIntentKind.ATMOSPHERE_PRESERVATION ->
                    "atmosphere_semantic_or_white_balance_capability_unavailable"
                AdvancedIntentKind.MOTION_CLARITY ->
                    "shutter_capability_unavailable"
                AdvancedIntentKind.LOW_NOISE ->
                    "iso_capability_unavailable"
                AdvancedIntentKind.COLOR_NEUTRALITY ->
                    "white_balance_capability_unavailable"
                null -> "advanced_parameter_unavailable"
            }
            return hold(input, proposalId, source, reason, diagnostics)
        }

        val (action, parameter) = target
        val advancedKind = decision.kind
            ?: return hold(input, proposalId, source, "advanced_parameter_unavailable", diagnostics)
        return PolicyProposal(
            proposalId = proposalId,
            intentRevision = input.intent.revision,
            frameId = input.metrics.frameId,
            action = action,
            parameter = parameter,
            reason = "mock_${advancedKind.reasonKey}",
            risk = RiskLevel.MEDIUM,
            cost = decision.score.coerceIn(0f, 1f),
            validUntilEpochMs = proposalExpiry(input),
            connectionEpoch = input.cameraState.connectionEpoch,
            capabilityRevision = input.cameraState.capabilityRevision,
            inputSource = source,
            createdAtEpochMs = input.nowEpochMs,
            diagnostics = diagnostics,
            executionMode = ExecutionMode.MOCK
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
            diagnostics = diagnostics,
            executionMode = executionMode(input, source)
        )
    }

    private fun executionMode(input: PolicyInput, source: InputSource): ExecutionMode {
        return if (source == InputSource.MOCK || input.executionMode == ExecutionMode.MOCK) {
            ExecutionMode.MOCK
        } else {
            ExecutionMode.REAL
        }
    }

    private fun exposureScores(
        input: PolicyInput,
        subjectRisk: Float,
        highlightRisk: Float,
        darkRisk: Float,
        semanticConfidence: Float
    ): ExposureScores {
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

        return ExposureScores(up = upScore, down = downScore)
    }

    private fun isDecisive(upScore: Float, downScore: Float): Boolean {
        val strongest = max(upScore, downScore)
        val margin = abs(upScore - downScore)
        return strongest >= config.minActionScore && margin >= config.minScoreMargin
    }

    private fun advancedIntentDecision(input: PolicyInput): AdvancedIntentDecision {
        val candidates = listOf(
            AdvancedIntentKind.MOTION_CLARITY to input.intent.motionClarity,
            AdvancedIntentKind.LOW_NOISE to input.intent.lowNoise,
            AdvancedIntentKind.COLOR_NEUTRALITY to input.intent.colorNeutrality,
            AdvancedIntentKind.ATMOSPHERE_PRESERVATION to input.intent.atmospherePreservation
        ).sortedByDescending { it.second }
        val strongest = candidates.first()
        val second = candidates.getOrNull(1)?.second ?: 0f
        val ambiguous = strongest.second - second < config.advancedIntentMargin
        return AdvancedIntentDecision(
            kind = if (strongest.second >= config.advancedIntentMinScore) {
                strongest.first
            } else {
                null
            },
            score = strongest.second,
            ambiguous = ambiguous
        )
    }

    private fun nextFasterShutter(
        current: com.lightpilot.core.model.ShutterSpeed?,
        capabilities: CameraCapabilities
    ): com.lightpilot.core.model.ShutterSpeed? {
        if (current == null) return null
        val sorted = capabilities.supportedShutterSpeed
            .distinct()
            .sortedBy { it.numerator / it.denominator }
        val currentIndex = sorted.indexOfFirst { sameShutter(it, current) }
        if (currentIndex <= 0) return null
        return sorted[currentIndex - 1]
    }

    private fun nextLowerIso(
        current: Int?,
        capabilities: CameraCapabilities
    ): Int? {
        if (current == null) return null
        val sorted = capabilities.supportedIso.distinct().sorted()
        val currentIndex = sorted.indexOf(current)
        if (currentIndex <= 0) return null
        return sorted[currentIndex - 1]
    }

    private fun chooseWhiteBalanceTarget(
        current: Int?,
        capabilities: CameraCapabilities,
        desired: Int
    ): Int? {
        if (current == null || capabilities.supportedWhiteBalance.isEmpty()) return null
        val target = capabilities.supportedWhiteBalance
            .distinct()
            .minByOrNull { abs(it - desired) }
            ?: return null
        return target.takeIf { it != current }
    }

    private fun sameShutter(
        first: com.lightpilot.core.model.ShutterSpeed,
        second: com.lightpilot.core.model.ShutterSpeed
    ): Boolean {
        return abs(first.numerator / first.denominator - second.numerator / second.denominator) < 1e-9
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
                it.equals("exposure_bias", ignoreCase = true) ||
                it.equals("exposurebias", ignoreCase = true) ||
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

    private data class ExposureScores(
        val up: Float,
        val down: Float
    )

    private data class AdvancedIntentDecision(
        val kind: AdvancedIntentKind?,
        val score: Float,
        val ambiguous: Boolean
    )

    private enum class AdvancedIntentKind(
        val reasonKey: String
    ) {
        MOTION_CLARITY("motion_clarity_shutter"),
        LOW_NOISE("low_noise_iso"),
        COLOR_NEUTRALITY("color_neutrality_white_balance"),
        ATMOSPHERE_PRESERVATION("atmosphere_preservation_white_balance")
    }
}
