package com.example.insta_auto_adjust.lightpilot

import kotlin.math.abs

data class PolicyConfig(
    val subjectDark: Double = 0.35,
    val subjectVeryDarkBalanced: Double = 0.30,
    val subjectBright: Double = 0.70,
    val highlightSubjectLimit: Double = 0.08,
    val highlightDetailLimit: Double = 0.03,
    val highlightBalancedRaiseLimit: Double = 0.03,
    val highlightBalancedLowerLimit: Double = 0.08,
    val proposalTtlMs: Long = 5_000,
)

class PolicyEngine(private val config: PolicyConfig = PolicyConfig()) {
    fun propose(
        intent: UserIntent,
        metrics: VisionMetrics,
        semantic: SceneSemantic?,
        cameraState: CameraState,
        capabilities: CameraCapabilities,
        nowMs: Long,
    ): PolicyProposal {
        val requiredFields = when (intent.exposurePriority) {
            ExposurePriority.SUBJECT_DETAIL -> setOf(SemanticField.SCENE, SemanticField.SUBJECT_ROI)
            ExposurePriority.HIGHLIGHT_DETAIL -> setOf(SemanticField.SCENE)
            ExposurePriority.BALANCED -> if (
                metrics.subjectBrightness != null &&
                metrics.subjectBrightness < config.subjectVeryDarkBalanced &&
                metrics.highlightClippingRatio < config.highlightBalancedRaiseLimit
            ) setOf(SemanticField.SCENE, SemanticField.SUBJECT_ROI)
            else setOf(SemanticField.SCENE)
        }
        if (semantic?.isUsableFor(requiredFields) != true) {
            return hold(intent, metrics, semantic, cameraState, capabilities, nowMs,
                ReasonCode.SEMANTIC_UNAVAILABLE,
                "曝光策略依赖的场景字段不可用，保持当前曝光", RiskLevel.HIGH)
        }
        val subject = metrics.subjectBrightness
        val subjectRoiUsable = semantic.isUsableFor(
            setOf(SemanticField.SCENE, SemanticField.SUBJECT_ROI))
        if (subjectRoiUsable && subject != null && subject < config.subjectDark &&
            metrics.highlightClippingRatio > config.highlightSubjectLimit) {
            return hold(intent, metrics, semantic, cameraState, capabilities, nowMs,
                ReasonCode.EXPOSURE_CONFLICT, "主体偏暗且高光已经溢出，等待用户调整构图或光线", RiskLevel.HIGH)
        }

        val desired = when (intent.exposurePriority) {
            ExposurePriority.SUBJECT_DETAIL -> when {
                subject == null -> null
                subject < config.subjectDark &&
                    metrics.highlightClippingRatio <= config.highlightSubjectLimit -> ExposureAction.EV_ONE_STEP_UP
                subject > config.subjectBright -> ExposureAction.EV_ONE_STEP_DOWN
                else -> ExposureAction.HOLD
            }
            ExposurePriority.HIGHLIGHT_DETAIL ->
                if (metrics.highlightClippingRatio > config.highlightDetailLimit)
                    ExposureAction.EV_ONE_STEP_DOWN else ExposureAction.HOLD
            ExposurePriority.BALANCED -> when {
                subject != null && subject < config.subjectVeryDarkBalanced &&
                    metrics.highlightClippingRatio < config.highlightBalancedRaiseLimit ->
                    ExposureAction.EV_ONE_STEP_UP
                metrics.highlightClippingRatio > config.highlightBalancedLowerLimit ->
                    ExposureAction.EV_ONE_STEP_DOWN
                else -> ExposureAction.HOLD
            }
        }

        if (intent.exposurePriority == ExposurePriority.SUBJECT_DETAIL && subject == null) {
            return hold(intent, metrics, semantic, cameraState, capabilities, nowMs,
                ReasonCode.SUBJECT_NOT_FOUND, "没有可靠的主体区域，保持当前曝光", RiskLevel.MEDIUM)
        }
        if (desired == null || desired == ExposureAction.HOLD) {
            return hold(intent, metrics, semantic, cameraState, capabilities, nowMs,
                ReasonCode.BALANCED_EXPOSURE, "当前曝光符合所选拍摄意图", RiskLevel.LOW)
        }

        val target = adjacentEv(cameraState.currentEv, capabilities.supportedEv, desired)
        if (target == null) {
            val currentSupported = capabilities.supportedEv.any { abs(it - cameraState.currentEv) < 1e-6 }
            val reason = if (currentSupported) ReasonCode.EV_LIMIT_REACHED else ReasonCode.CAMERA_CAPABILITY_INVALID
            val message = if (currentSupported) "曝光补偿已经到达相机支持边界" else "当前 EV 不在最新能力列表中"
            return hold(intent, metrics, semantic, cameraState, capabilities, nowMs, reason, message,
                RiskLevel.HIGH)
        }
        val reasonCode = when (desired) {
            ExposureAction.EV_ONE_STEP_UP -> ReasonCode.SUBJECT_TOO_DARK
            ExposureAction.EV_ONE_STEP_DOWN ->
                if (intent.exposurePriority == ExposurePriority.SUBJECT_DETAIL)
                    ReasonCode.SUBJECT_TOO_BRIGHT else ReasonCode.HIGHLIGHTS_CLIPPING
            ExposureAction.HOLD -> ReasonCode.BALANCED_EXPOSURE
        }
        val message = if (desired == ExposureAction.EV_ONE_STEP_UP) {
            "主体偏暗，建议提高相机支持列表中的一档 EV"
        } else {
            "主体过亮或高光溢出，建议降低相机支持列表中的一档 EV"
        }
        return PolicyProposal(
            intentRevision = intent.revision,
            cameraStateRevision = cameraState.cameraStateRevision,
            semanticFrameId = semantic.frameId,
            metricsFrameId = metrics.frameId,
            semanticStatus = semantic.status,
            executionMode = capabilities.executionMode,
            action = desired,
            targetEv = target,
            reasonCode = reasonCode,
            reason = message,
            risk = RiskLevel.MEDIUM,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + config.proposalTtlMs,
        )
    }

    private fun adjacentEv(current: Double, supported: List<Double>, action: ExposureAction): Double? {
        val sorted = supported.sorted()
        val currentIndex = sorted.indexOfFirst { abs(it - current) < 1e-6 }
        if (currentIndex < 0) return null
        val targetIndex = when (action) {
            ExposureAction.EV_ONE_STEP_UP -> currentIndex + 1
            ExposureAction.EV_ONE_STEP_DOWN -> currentIndex - 1
            ExposureAction.HOLD -> currentIndex
        }
        return sorted.getOrNull(targetIndex)
    }

    private fun hold(
        intent: UserIntent,
        metrics: VisionMetrics,
        semantic: SceneSemantic?,
        cameraState: CameraState,
        capabilities: CameraCapabilities,
        nowMs: Long,
        code: ReasonCode,
        reason: String,
        risk: RiskLevel,
    ) = PolicyProposal(
        intentRevision = intent.revision,
        cameraStateRevision = cameraState.cameraStateRevision,
        semanticFrameId = semantic?.frameId ?: -1,
        metricsFrameId = metrics.frameId,
        semanticStatus = semantic?.status ?: SemanticStatus.UNAVAILABLE,
        executionMode = capabilities.executionMode,
        action = ExposureAction.HOLD,
        targetEv = null,
        reasonCode = code,
        reason = reason,
        risk = risk,
        createdAtMs = nowMs,
        expiresAtMs = nowMs + config.proposalTtlMs,
    )
}
