package com.example.insta_auto_adjust.lightpilot

import kotlin.math.abs

enum class PolicyStage { EXPOSURE, MOTION_NOISE, COLOR_ATMOSPHERE }

enum class UnifiedAction {
    HOLD, EV_ONE_STEP_UP, EV_ONE_STEP_DOWN, SET_SHUTTER, SET_ISO, SET_WHITE_BALANCE,
}

enum class CandidateDisposition { ACTION, SATISFIED, BLOCKED }

enum class StageConstraintCode {
    EXPIRED, MODE_MISMATCH, CAPABILITY_UNAVAILABLE, SEMANTIC_DEPENDENCY_UNAVAILABLE,
    LOCAL_METRICS_UNAVAILABLE, TEMPORAL_CONFIRMATION_PENDING, PLANNING_ONLY,
    MOCK_INPUT, EXPOSURE_CONFLICT, ATMOSPHERE_PRESERVATION,
    COLORED_LIGHT_PRESERVATION,
}

data class StageConstraint(
    val code: StageConstraintCode,
    val message: String,
    val blocking: Boolean,
)

data class StageCandidate(
    val stage: PolicyStage,
    val stageWeight: Double,
    val action: UnifiedAction,
    val urgency: Double,
    val disposition: CandidateDisposition,
    val reason: String,
    val targetSignature: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val intentRevision: Long,
    val cameraStateRevision: Long,
    val temporalConfirmed: Boolean = false,
    val constraints: List<StageConstraint> = emptyList(),
) {
    init {
        require(stageWeight.isFinite() && stageWeight in 0.0..1.0)
        require(urgency.isFinite() && urgency in 0.0..1.0)
    }

    val score: Double get() = stageWeight * urgency
    val blocked: Boolean
        get() = disposition == CandidateDisposition.BLOCKED || constraints.any { it.blocking }
}

enum class ArbitrationReason {
    ACTION_SELECTED, NO_ACTIVE_STAGE, ALL_GOALS_SATISFIED, PRIMARY_OBJECTIVE_BLOCKED,
    MULTI_OBJECTIVE_CONFLICT, TEMPORAL_CONFIRMATION_PENDING, NO_VALID_CANDIDATE,
}

data class ArbitrationDecision(
    val selected: StageCandidate?,
    val reason: ArbitrationReason,
    val alternatives: List<StageCandidate>,
    val requiresUserChoice: Boolean,
)

class PolicyArbiter(private val tieThreshold: Double = 0.10) {
    init { require(tieThreshold in 0.0..1.0) }

    fun arbitrate(
        intent: MultiStageIntent,
        candidates: List<StageCandidate>,
        nowMs: Long,
    ): ArbitrationDecision {
        val active = intent.activeStages()
        if (active.isEmpty()) return hold(ArbitrationReason.NO_ACTIVE_STAGE)

        val current = candidates.filter {
            it.stage in active && it.intentRevision == intent.revision
        }.map { candidate ->
            if (nowMs <= candidate.expiresAtMs) candidate else candidate.copy(
                disposition = CandidateDisposition.BLOCKED,
                constraints = candidate.constraints + StageConstraint(
                    StageConstraintCode.EXPIRED, "候选已过期，需要重新分析", true),
            )
        }
        val highestWeight = active.maxOf { intent.weights.weight(it) }
        val primaryStages = active.filter { intent.weights.weight(it) == highestWeight }.toSet()
        val primaryCandidates = current.filter { it.stage in primaryStages }
        val primaryBlocked = primaryStages.any { stage ->
            primaryCandidates.none { it.stage == stage } ||
                primaryCandidates.filter { it.stage == stage }.all { it.blocked }
        }
        if (primaryBlocked) {
            return ArbitrationDecision(null, ArbitrationReason.PRIMARY_OBJECTIVE_BLOCKED,
                current.sortedByDescending(StageCandidate::score), false)
        }

        val selectable = current.filter {
            it.disposition == CandidateDisposition.ACTION && !it.blocked && it.action != UnifiedAction.HOLD
        }.filterNot { candidate ->
            candidate.action == UnifiedAction.SET_WHITE_BALANCE && current.any { constraintOwner ->
                constraintOwner.stage == PolicyStage.COLOR_ATMOSPHERE &&
                    constraintOwner.constraints.any { constraint ->
                        constraint.code == StageConstraintCode.ATMOSPHERE_PRESERVATION ||
                            constraint.code == StageConstraintCode.COLORED_LIGHT_PRESERVATION
                    }
            }
        }.sortedByDescending(StageCandidate::score)

        if (selectable.isEmpty()) {
            val satisfied = current.any { it.disposition == CandidateDisposition.SATISFIED }
            return ArbitrationDecision(null,
                if (satisfied) ArbitrationReason.ALL_GOALS_SATISFIED else ArbitrationReason.NO_VALID_CANDIDATE,
                current.sortedByDescending(StageCandidate::score), false)
        }
        val temporallyReady = selectable.filter { it.temporalConfirmed }
        if (temporallyReady.isEmpty()) {
            return ArbitrationDecision(null, ArbitrationReason.TEMPORAL_CONFIRMATION_PENDING,
                selectable, false)
        }
        val first = temporallyReady[0]
        val second = temporallyReady.getOrNull(1)
        if (second != null && first.score - second.score < tieThreshold) {
            return ArbitrationDecision(null, ArbitrationReason.MULTI_OBJECTIVE_CONFLICT,
                temporallyReady, true)
        }
        return ArbitrationDecision(first, ArbitrationReason.ACTION_SELECTED,
            temporallyReady.drop(1), false)
    }

    private fun hold(reason: ArbitrationReason) =
        ArbitrationDecision(null, reason, emptyList(), false)
}

object CandidateUrgency {
    fun above(value: Double, threshold: Double): Double {
        require(value in 0.0..1.0 && threshold in 0.0..<1.0)
        return ((value - threshold) / (1.0 - threshold)).coerceIn(0.0, 1.0)
    }

    fun below(value: Double, threshold: Double): Double {
        require(value in 0.0..1.0 && threshold in 0.0..1.0 && threshold > 0.0)
        return ((threshold - value) / threshold).coerceIn(0.0, 1.0)
    }

    fun relativePosition(current: Int, supported: List<Int>): Double {
        val sorted = supported.sorted()
        val index = sorted.indexOf(current)
        if (index < 0 || sorted.size < 2) return 0.0
        return index.toDouble() / (sorted.size - 1)
    }

    fun whiteBalanceDelta(current: Int, target: Int, supported: List<Int>): Double {
        val range = (supported.maxOrNull() ?: return 0.0) - (supported.minOrNull() ?: return 0.0)
        if (range <= 0) return 0.0
        return (abs(current - target).toDouble() / range).coerceIn(0.0, 1.0)
    }
}

object StageCandidateFactory {
    fun exposure(
        intent: MultiStageIntent,
        metrics: VisionMetrics,
        proposal: PolicyProposal,
    ): StageCandidate {
        val urgency = when (proposal.action) {
            ExposureAction.EV_ONE_STEP_UP -> metrics.subjectBrightness?.let {
                CandidateUrgency.below(it, if (intent.exposurePriority == ExposurePriority.BALANCED) 0.30 else 0.35)
            } ?: 0.0
            ExposureAction.EV_ONE_STEP_DOWN -> if (
                proposal.reasonCode == ReasonCode.SUBJECT_TOO_BRIGHT
            ) metrics.subjectBrightness?.let { CandidateUrgency.above(it, 0.70) } ?: 0.0
            else CandidateUrgency.above(
                metrics.highlightClippingRatio,
                if (intent.exposurePriority == ExposurePriority.HIGHLIGHT_DETAIL) 0.03 else 0.08,
            )
            ExposureAction.HOLD -> 0.0
        }
        val disposition = if (proposal.action == ExposureAction.HOLD) {
            if (proposal.reasonCode == ReasonCode.BALANCED_EXPOSURE) CandidateDisposition.SATISFIED
            else CandidateDisposition.BLOCKED
        } else CandidateDisposition.ACTION
        return StageCandidate(
            stage = PolicyStage.EXPOSURE,
            stageWeight = intent.weights.exposure,
            action = when (proposal.action) {
                ExposureAction.HOLD -> UnifiedAction.HOLD
                ExposureAction.EV_ONE_STEP_UP -> UnifiedAction.EV_ONE_STEP_UP
                ExposureAction.EV_ONE_STEP_DOWN -> UnifiedAction.EV_ONE_STEP_DOWN
            },
            urgency = urgency,
            disposition = disposition,
            reason = proposal.reason,
            targetSignature = "${proposal.action}/${proposal.targetEv}",
            createdAtMs = proposal.createdAtMs,
            expiresAtMs = proposal.expiresAtMs,
            intentRevision = proposal.intentRevision,
            cameraStateRevision = proposal.cameraStateRevision,
            constraints = buildList {
                if (proposal.reasonCode == ReasonCode.EXPOSURE_CONFLICT) add(StageConstraint(
                    StageConstraintCode.EXPOSURE_CONFLICT, proposal.reason, true))
                if (proposal.reasonCode == ReasonCode.SEMANTIC_UNAVAILABLE) add(StageConstraint(
                    StageConstraintCode.SEMANTIC_DEPENDENCY_UNAVAILABLE, proposal.reason, true))
            },
        )
    }

    fun advanced(
        intent: MultiStageIntent,
        proposal: AdvancedPolicyProposal,
        motionMetrics: MotionMetrics? = null,
        state: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
    ): StageCandidate {
        val stage = when (proposal.stage) {
            AdvancedStage.MOTION_NOISE -> PolicyStage.MOTION_NOISE
            AdvancedStage.COLOR_ATMOSPHERE -> PolicyStage.COLOR_ATMOSPHERE
        }
        val urgency = when (proposal.action) {
            AdvancedAction.SET_SHUTTER -> motionMetrics?.motionScore?.let {
                CandidateUrgency.above(it, 0.35)
            } ?: 0.0
            AdvancedAction.SET_ISO -> when {
                proposal.targetIso == null || state.currentIso == null -> 0.0
                proposal.targetIso < state.currentIso ->
                    CandidateUrgency.relativePosition(state.currentIso, capabilities.supportedIso)
                else -> motionMetrics?.let { CandidateUrgency.above(it.darkRatio, 0.30) } ?: 0.0
            }
            AdvancedAction.SET_WHITE_BALANCE -> if (
                proposal.targetWhiteBalanceKelvin != null && state.currentWhiteBalanceKelvin != null
            ) CandidateUrgency.whiteBalanceDelta(
                state.currentWhiteBalanceKelvin, proposal.targetWhiteBalanceKelvin,
                capabilities.supportedWhiteBalanceKelvin,
            ) else 0.0
            AdvancedAction.HOLD -> 0.0
        }
        val satisfiedCodes = setOf(
            AdvancedReasonCode.PARAMETER_ALREADY_SUITABLE,
            AdvancedReasonCode.ATMOSPHERE_PRESERVED,
            AdvancedReasonCode.COLORED_LIGHT_PRESERVED,
        )
        val disposition = when {
            proposal.action != AdvancedAction.HOLD -> CandidateDisposition.ACTION
            proposal.reasonCode in satisfiedCodes -> CandidateDisposition.SATISFIED
            else -> CandidateDisposition.BLOCKED
        }
        return StageCandidate(
            stage = stage,
            stageWeight = intent.weights.weight(stage),
            action = when (proposal.action) {
                AdvancedAction.HOLD -> UnifiedAction.HOLD
                AdvancedAction.SET_SHUTTER -> UnifiedAction.SET_SHUTTER
                AdvancedAction.SET_ISO -> UnifiedAction.SET_ISO
                AdvancedAction.SET_WHITE_BALANCE -> UnifiedAction.SET_WHITE_BALANCE
            },
            urgency = urgency,
            disposition = disposition,
            reason = proposal.reason,
            targetSignature = proposal.targetSignature,
            createdAtMs = proposal.createdAtMs,
            expiresAtMs = proposal.expiresAtMs,
            intentRevision = proposal.intentRevision,
            cameraStateRevision = proposal.cameraStateRevision,
            constraints = buildList {
                when (proposal.reasonCode) {
                    AdvancedReasonCode.MANUAL_MODE_REQUIRED -> add(StageConstraint(
                        StageConstraintCode.MODE_MISMATCH, proposal.reason, true))
                    AdvancedReasonCode.CAMERA_CAPABILITY_UNVERIFIED,
                    AdvancedReasonCode.WHITE_BALANCE_LOCK_UNVERIFIED -> add(StageConstraint(
                        StageConstraintCode.CAPABILITY_UNAVAILABLE, proposal.reason, true))
                    AdvancedReasonCode.SEMANTIC_UNAVAILABLE,
                    AdvancedReasonCode.SUBJECT_NOT_FOUND -> add(StageConstraint(
                        StageConstraintCode.SEMANTIC_DEPENDENCY_UNAVAILABLE, proposal.reason, true))
                    AdvancedReasonCode.METRICS_UNAVAILABLE -> add(StageConstraint(
                        StageConstraintCode.LOCAL_METRICS_UNAVAILABLE, proposal.reason, true))
                    AdvancedReasonCode.ATMOSPHERE_PRESERVED -> add(StageConstraint(
                        StageConstraintCode.ATMOSPHERE_PRESERVATION, proposal.reason, false))
                    AdvancedReasonCode.COLORED_LIGHT_PRESERVED -> add(StageConstraint(
                        StageConstraintCode.COLORED_LIGHT_PRESERVATION, proposal.reason, false))
                    else -> Unit
                }
                // Stage 2/3 remain visible planning candidates but cannot be selected for execution.
                if (proposal.action != AdvancedAction.HOLD) add(StageConstraint(
                    StageConstraintCode.PLANNING_ONLY, "阶段二/三尚未通过真实能力、执行器和回读验收", true))
                if (proposal.inputSource == InputSource.MOCK || proposal.executionMode == ExecutionMode.MOCK) {
                    add(StageConstraint(StageConstraintCode.MOCK_INPUT, "Mock 输入禁止执行", true))
                }
            },
        )
    }
}

class FusionTemporalController {
    private var intentRevision: Long? = null
    private var cameraRevision: Long? = null
    private val signatures = mutableMapOf<PolicyStage, String>()
    private val counts = mutableMapOf<PolicyStage, Int>()

    @Synchronized
    fun observe(
        candidates: List<StageCandidate>,
        preference: StabilityPreference,
    ): List<StageCandidate> {
        val revisionChanged = candidates.any {
            intentRevision != null && it.intentRevision != intentRevision ||
                cameraRevision != null && it.cameraStateRevision != cameraRevision
        }
        val directionChanged = candidates.any { candidate ->
            val previous = signatures[candidate.stage]
            previous != null && (candidate.disposition != CandidateDisposition.ACTION ||
                candidate.targetSignature != previous)
        }
        if (revisionChanged || directionChanged) reset()
        candidates.firstOrNull()?.let {
            intentRevision = it.intentRevision
            cameraRevision = it.cameraStateRevision
        }
        val required = if (preference == StabilityPreference.HIGH) 5 else 3
        return candidates.map { candidate ->
            if (candidate.disposition != CandidateDisposition.ACTION || candidate.blocked) {
                signatures.remove(candidate.stage)
                counts.remove(candidate.stage)
                candidate
            } else {
                val same = signatures[candidate.stage] == candidate.targetSignature
                val count = if (same) (counts[candidate.stage] ?: 0) + 1 else 1
                signatures[candidate.stage] = candidate.targetSignature
                counts[candidate.stage] = count
                candidate.copy(temporalConfirmed = count >= required)
            }
        }
    }

    @Synchronized
    fun onExecutionAcknowledged() = reset()

    @Synchronized
    fun reset() {
        intentRevision = null
        cameraRevision = null
        signatures.clear()
        counts.clear()
    }
}

data class MultiStageEvaluation(
    val rawCandidates: List<StageCandidate>,
    val confirmedCandidates: List<StageCandidate>,
    val decision: ArbitrationDecision,
)

/**
 * Evaluates each active objective independently, then applies one shared temporal gate and arbiter.
 * The model is never called here: decisions use cached semantics and local frame metrics only.
 */
class MultiStagePolicyCoordinator(
    private val exposureEngine: PolicyEngine = PolicyEngine(),
    private val advancedEngine: AdvancedPolicyEngine = AdvancedPolicyEngine(),
    private val temporal: FusionTemporalController = FusionTemporalController(),
    private val arbiter: PolicyArbiter = PolicyArbiter(),
) {
    fun evaluate(
        intent: MultiStageIntent,
        exposureMetrics: VisionMetrics?,
        motionMetrics: MotionMetrics?,
        colorMetrics: ColorMetrics?,
        semantic: SceneSemantic?,
        exposureState: CameraState,
        exposureCapabilities: CameraCapabilities,
        advancedState: AdvancedCameraState,
        advancedCapabilities: AdvancedCameraCapabilities,
        nowMs: Long,
    ): MultiStageEvaluation {
        val currentSemantic = semantic?.takeIf { it.intentRevision == intent.revision }
        val raw = buildList {
            if (PolicyStage.EXPOSURE in intent.activeStages()) {
                val metrics = exposureMetrics
                val priority = intent.exposurePriority
                if (metrics == null || priority == null) {
                    add(blocked(intent, PolicyStage.EXPOSURE, exposureState.cameraStateRevision,
                        nowMs, "曝光指标或优先级不可用"))
                } else {
                    val proposal = exposureEngine.propose(
                        UserIntent(intent.revision, priority, intent.stabilityPreference, intent.sourceText),
                        metrics, currentSemantic, exposureState, exposureCapabilities, nowMs,
                    )
                    add(StageCandidateFactory.exposure(intent, metrics, proposal))
                }
            }
            if (PolicyStage.MOTION_NOISE in intent.activeStages()) {
                val metrics = motionMetrics
                val priority = intent.motionPriority
                if (metrics == null || priority == null) {
                    add(blocked(intent, PolicyStage.MOTION_NOISE, advancedState.cameraStateRevision,
                        nowMs, "运动指标或优先级不可用"))
                } else {
                    val proposal = advancedEngine.proposeMotion(
                        MotionIntent(intent.revision, priority, intent.stabilityPreference,
                            intent.sourceText),
                        metrics, currentSemantic, advancedState, advancedCapabilities, nowMs,
                    )
                    add(StageCandidateFactory.advanced(intent, proposal, metrics,
                        advancedState, advancedCapabilities))
                }
            }
            if (PolicyStage.COLOR_ATMOSPHERE in intent.activeStages()) {
                val metrics = colorMetrics
                val priority = intent.colorPriority
                if (metrics == null || priority == null) {
                    add(blocked(intent, PolicyStage.COLOR_ATMOSPHERE,
                        advancedState.cameraStateRevision, nowMs, "色彩指标或优先级不可用"))
                } else {
                    val proposal = advancedEngine.proposeColor(
                        ColorIntent(intent.revision, priority, intent.stabilityPreference,
                            intent.sourceText),
                        metrics, currentSemantic, advancedState, advancedCapabilities, nowMs,
                    )
                    add(StageCandidateFactory.advanced(intent, proposal, null,
                        advancedState, advancedCapabilities))
                }
            }
        }
        val confirmed = temporal.observe(raw, intent.stabilityPreference)
        return MultiStageEvaluation(raw, confirmed, arbiter.arbitrate(intent, confirmed, nowMs))
    }

    fun onExecutionAcknowledged() = temporal.onExecutionAcknowledged()

    fun reset() = temporal.reset()

    private fun blocked(
        intent: MultiStageIntent,
        stage: PolicyStage,
        cameraRevision: Long,
        nowMs: Long,
        message: String,
    ) = StageCandidate(
        stage = stage,
        stageWeight = intent.weights.weight(stage),
        action = UnifiedAction.HOLD,
        urgency = 0.0,
        disposition = CandidateDisposition.BLOCKED,
        reason = message,
        targetSignature = "$stage/HOLD",
        createdAtMs = nowMs,
        expiresAtMs = nowMs + 5_000,
        intentRevision = intent.revision,
        cameraStateRevision = cameraRevision,
        constraints = listOf(StageConstraint(
            StageConstraintCode.LOCAL_METRICS_UNAVAILABLE, message, true)),
    )
}
