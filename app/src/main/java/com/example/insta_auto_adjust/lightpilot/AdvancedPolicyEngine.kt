package com.example.insta_auto_adjust.lightpilot

import kotlin.math.abs

data class AdvancedPolicyConfig(
    val motionThreshold: Double = 0.35,
    val balancedMotionThreshold: Double = 0.55,
    val darkThreshold: Double = 0.30,
    val balancedDarkThreshold: Double = 0.35,
    val naturalSkinTargetKelvin: Int = 5_200,
    val proposalTtlMs: Long = 5_000,
)

/**
 * Generates Stage 2/3 planning proposals. Every proposal is deliberately MOCK until an
 * independent real adapter and executor contract has been implemented and accepted.
 */
class AdvancedPolicyEngine(
    private val config: AdvancedPolicyConfig = AdvancedPolicyConfig(),
) {
    fun proposeMotion(
        intent: MotionIntent,
        metrics: MotionMetrics,
        semantic: SceneSemantic?,
        cameraState: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
        nowMs: Long,
    ): AdvancedPolicyProposal {
        val invalid = commonHold(
            AdvancedStage.MOTION_NOISE, intent.revision, metrics.frameId, semantic,
            cameraState, capabilities, nowMs,
            requiredFields = setOf(SemanticField.SCENE),
        )
        if (invalid != null) return invalid
        val usableSemantic = semantic!!
        if (cameraState.exposureProgram != ExposureProgram.MANUAL) {
            return motionHold(intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                AdvancedReasonCode.MANUAL_MODE_REQUIRED,
                "阶段二需要 Manual 曝光模式；切换后必须重新同步能力并重新生成建议")
        }
        val motion = metrics.motionScore ?: return motionHold(
            intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
            AdvancedReasonCode.METRICS_UNAVAILABLE, "本地运动指标不可用，保持当前参数",
        )

        return when (intent.priority) {
            MotionPriority.MOTION_CLARITY -> {
                if (motion < config.motionThreshold) {
                    motionHold(intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                        AdvancedReasonCode.PARAMETER_ALREADY_SUITABLE, "当前运动幅度不需要提高快门")
                } else {
                    shutterProposal(intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                        faster = true, AdvancedReasonCode.MOTION_DETECTED,
                        "检测到明显运动，建议使用能力列表中的相邻更快快门")
                }
            }
            MotionPriority.LOW_NOISE -> isoProposal(
                intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                increase = false, AdvancedReasonCode.NOISE_REDUCTION,
                "低噪点优先，建议使用能力列表中的相邻更低 ISO",
            )
            MotionPriority.BRIGHTNESS_PRIORITY -> {
                if (metrics.darkRatio <= config.darkThreshold) {
                    motionHold(intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                        AdvancedReasonCode.PARAMETER_ALREADY_SUITABLE, "当前暗部比例无需提高感光度")
                } else {
                    isoProposal(intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                        increase = true, AdvancedReasonCode.SCENE_TOO_DARK,
                        "画面暗部比例较高，建议使用能力列表中的相邻更高 ISO")
                }
            }
            MotionPriority.MOTION_BALANCED -> when {
                motion >= config.balancedMotionThreshold -> shutterProposal(
                    intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                    faster = true, AdvancedReasonCode.MOTION_BALANCED,
                    "运动较强，平衡策略优先提高一档快门",
                )
                metrics.darkRatio >= config.balancedDarkThreshold -> isoProposal(
                    intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                    increase = true, AdvancedReasonCode.MOTION_BALANCED,
                    "运动可控但画面偏暗，平衡策略建议提高一档 ISO",
                )
                else -> motionHold(intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                    AdvancedReasonCode.PARAMETER_ALREADY_SUITABLE,
                    "运动、亮度和噪点处于平衡范围")
            }
        }
    }

    fun proposeColor(
        intent: ColorIntent,
        metrics: ColorMetrics,
        semantic: SceneSemantic?,
        cameraState: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
        nowMs: Long,
    ): AdvancedPolicyProposal {
        val invalid = commonHold(
            AdvancedStage.COLOR_ATMOSPHERE, intent.revision, metrics.frameId, semantic,
            cameraState, capabilities, nowMs,
            requiredFields = when (intent.priority) {
                ColorPriority.NATURAL_SKIN -> setOf(SemanticField.SCENE, SemanticField.SUBJECT_TYPE)
                ColorPriority.ATMOSPHERE_PRESERVATION,
                ColorPriority.COLORED_LIGHT_PRESERVATION ->
                    setOf(SemanticField.SCENE, SemanticField.COLORED_LIGHT)
                ColorPriority.COLOR_ACCURACY, ColorPriority.COLOR_STABILITY ->
                    setOf(SemanticField.SCENE)
            },
        )
        if (invalid != null) return invalid
        val usableSemantic = semantic!!
        return when (intent.priority) {
            ColorPriority.COLOR_ACCURACY -> {
                val estimate = metrics.estimatedColorTemperatureKelvin ?: return colorHold(
                    intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                    AdvancedReasonCode.METRICS_UNAVAILABLE,
                    "本地色温估计不可用，无法生成颜色还原建议",
                )
                whiteBalanceProposal(intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                    estimate, AdvancedReasonCode.COLOR_ACCURACY,
                    "选择能力列表中最接近本地估计色温的白平衡")
            }
            ColorPriority.NATURAL_SKIN -> {
                if (usableSemantic.subjectType !in setOf(SubjectType.PERSON, SubjectType.GROUP)) {
                    colorHold(intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                        AdvancedReasonCode.SUBJECT_NOT_FOUND, "未检测到可靠人物主体，保持当前白平衡")
                } else {
                    whiteBalanceProposal(intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                        config.naturalSkinTargetKelvin, AdvancedReasonCode.NATURAL_SKIN,
                        "人物肤色优先，选择 Mock 能力列表中最接近中性色温的白平衡")
                }
            }
            ColorPriority.ATMOSPHERE_PRESERVATION -> colorHold(
                intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                AdvancedReasonCode.ATMOSPHERE_PRESERVED,
                "氛围保留意图下不主动中和现场冷暖色",
            )
            ColorPriority.COLORED_LIGHT_PRESERVATION -> colorHold(
                intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                AdvancedReasonCode.COLORED_LIGHT_PRESERVED,
                if (usableSemantic.coloredLight == true) "检测到彩色光，保持当前白平衡以保留现场色彩"
                else "未确认彩色光，保持当前白平衡并等待稳定场景",
            )
            ColorPriority.COLOR_STABILITY -> colorHold(
                intent, metrics, usableSemantic, cameraState, capabilities, nowMs,
                AdvancedReasonCode.WHITE_BALANCE_LOCK_UNVERIFIED,
                "SDK 白平衡锁定能力尚未验证，暂不生成可执行动作",
            )
        }
    }

    private fun shutterProposal(
        intent: MotionIntent,
        metrics: MotionMetrics,
        semantic: SceneSemantic,
        state: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
        nowMs: Long,
        faster: Boolean,
        code: AdvancedReasonCode,
        reason: String,
    ): AdvancedPolicyProposal {
        if (!capabilities.shutterReadable || !capabilities.shutterWritable ||
            state.currentShutterSeconds == null || capabilities.supportedShutterSeconds.isEmpty()) {
            return motionHold(intent, metrics, semantic, state, capabilities, nowMs,
                AdvancedReasonCode.CAMERA_CAPABILITY_UNVERIFIED, "快门读写能力或合法值列表尚未确认")
        }
        val sorted = capabilities.supportedShutterSeconds.sorted()
        val index = sorted.indexOfFirst { abs(it - state.currentShutterSeconds) < 1e-9 }
        if (index < 0) return motionHold(intent, metrics, semantic, state, capabilities, nowMs,
            AdvancedReasonCode.CAMERA_CAPABILITY_UNVERIFIED, "当前快门不在最新能力列表中")
        val target = sorted.getOrNull(if (faster) index - 1 else index + 1)
            ?: return motionHold(intent, metrics, semantic, state, capabilities, nowMs,
                AdvancedReasonCode.PARAMETER_LIMIT_REACHED, "快门已到能力列表边界")
        return proposal(AdvancedStage.MOTION_NOISE, intent.revision, metrics.frameId, semantic,
            state, capabilities, nowMs, AdvancedAction.SET_SHUTTER, code, reason,
            targetShutterSeconds = target)
    }

    private fun isoProposal(
        intent: MotionIntent,
        metrics: MotionMetrics,
        semantic: SceneSemantic,
        state: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
        nowMs: Long,
        increase: Boolean,
        code: AdvancedReasonCode,
        reason: String,
    ): AdvancedPolicyProposal {
        if (!capabilities.isoReadable || !capabilities.isoWritable || state.currentIso == null ||
            capabilities.supportedIso.isEmpty()) {
            return motionHold(intent, metrics, semantic, state, capabilities, nowMs,
                AdvancedReasonCode.CAMERA_CAPABILITY_UNVERIFIED, "ISO 读写能力或合法值列表尚未确认")
        }
        val sorted = capabilities.supportedIso.sorted()
        val index = sorted.indexOf(state.currentIso)
        if (index < 0) return motionHold(intent, metrics, semantic, state, capabilities, nowMs,
            AdvancedReasonCode.CAMERA_CAPABILITY_UNVERIFIED, "当前 ISO 不在最新能力列表中")
        val target = sorted.getOrNull(if (increase) index + 1 else index - 1)
            ?: return motionHold(intent, metrics, semantic, state, capabilities, nowMs,
                AdvancedReasonCode.PARAMETER_LIMIT_REACHED, "ISO 已到能力列表边界")
        return proposal(AdvancedStage.MOTION_NOISE, intent.revision, metrics.frameId, semantic,
            state, capabilities, nowMs, AdvancedAction.SET_ISO, code, reason, targetIso = target)
    }

    private fun whiteBalanceProposal(
        intent: ColorIntent,
        metrics: ColorMetrics,
        semantic: SceneSemantic,
        state: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
        nowMs: Long,
        desiredKelvin: Int,
        code: AdvancedReasonCode,
        reason: String,
    ): AdvancedPolicyProposal {
        if (!capabilities.whiteBalanceReadable || !capabilities.whiteBalanceWritable ||
            state.currentWhiteBalanceKelvin == null || capabilities.supportedWhiteBalanceKelvin.isEmpty()) {
            return colorHold(intent, metrics, semantic, state, capabilities, nowMs,
                AdvancedReasonCode.CAMERA_CAPABILITY_UNVERIFIED,
                "白平衡读写能力或合法值列表尚未确认")
        }
        if (state.currentWhiteBalanceKelvin !in capabilities.supportedWhiteBalanceKelvin) {
            return colorHold(intent, metrics, semantic, state, capabilities, nowMs,
                AdvancedReasonCode.CAMERA_CAPABILITY_UNVERIFIED,
                "当前白平衡不在最新能力列表中")
        }
        val target = capabilities.supportedWhiteBalanceKelvin.minBy { abs(it - desiredKelvin) }
        if (target == state.currentWhiteBalanceKelvin) {
            return colorHold(intent, metrics, semantic, state, capabilities, nowMs,
                AdvancedReasonCode.PARAMETER_ALREADY_SUITABLE, "当前白平衡已经符合所选意图")
        }
        return proposal(AdvancedStage.COLOR_ATMOSPHERE, intent.revision, metrics.frameId, semantic,
            state, capabilities, nowMs, AdvancedAction.SET_WHITE_BALANCE, code, reason,
            targetWhiteBalanceKelvin = target)
    }

    private fun commonHold(
        stage: AdvancedStage,
        revision: Long,
        metricsFrameId: Long,
        semantic: SceneSemantic?,
        state: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
        nowMs: Long,
        requiredFields: Set<SemanticField>,
    ): AdvancedPolicyProposal? = when {
        semantic?.isUsableFor(requiredFields) != true -> proposal(stage, revision, metricsFrameId, semantic,
            state, capabilities, nowMs, AdvancedAction.HOLD,
            AdvancedReasonCode.SEMANTIC_UNAVAILABLE,
            "当前阶段依赖的场景字段不可用，保持当前参数", RiskLevel.HIGH)
        !state.stateKnown || state.isBusy || state.recordingState == RecordingState.UNKNOWN ->
            proposal(stage, revision, metricsFrameId, semantic, state, capabilities, nowMs,
                AdvancedAction.HOLD, AdvancedReasonCode.CAMERA_STATE_UNAVAILABLE,
                "相机状态未知或正忙，保持当前参数", RiskLevel.HIGH)
        capabilities.executionMode != ExecutionMode.MOCK || capabilities.inputSource != InputSource.MOCK ->
            proposal(stage, revision, metricsFrameId, semantic, state, capabilities, nowMs,
                AdvancedAction.HOLD, AdvancedReasonCode.CAMERA_CAPABILITY_UNVERIFIED,
                "阶段二/三当前仅接受明确标记的 Mock 能力快照", RiskLevel.HIGH)
        else -> null
    }

    private fun motionHold(
        intent: MotionIntent,
        metrics: MotionMetrics,
        semantic: SceneSemantic,
        state: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
        nowMs: Long,
        code: AdvancedReasonCode,
        reason: String,
    ) = proposal(AdvancedStage.MOTION_NOISE, intent.revision, metrics.frameId, semantic, state,
        capabilities, nowMs, AdvancedAction.HOLD, code, reason)

    private fun colorHold(
        intent: ColorIntent,
        metrics: ColorMetrics,
        semantic: SceneSemantic,
        state: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
        nowMs: Long,
        code: AdvancedReasonCode,
        reason: String,
    ) = proposal(AdvancedStage.COLOR_ATMOSPHERE, intent.revision, metrics.frameId, semantic, state,
        capabilities, nowMs, AdvancedAction.HOLD, code, reason)

    private fun proposal(
        stage: AdvancedStage,
        revision: Long,
        metricsFrameId: Long,
        semantic: SceneSemantic?,
        state: AdvancedCameraState,
        capabilities: AdvancedCameraCapabilities,
        nowMs: Long,
        action: AdvancedAction,
        code: AdvancedReasonCode,
        reason: String,
        risk: RiskLevel = if (action == AdvancedAction.HOLD) RiskLevel.LOW else RiskLevel.MEDIUM,
        targetShutterSeconds: Double? = null,
        targetIso: Int? = null,
        targetWhiteBalanceKelvin: Int? = null,
    ) = AdvancedPolicyProposal(
        stage = stage,
        intentRevision = revision,
        cameraStateRevision = state.cameraStateRevision,
        semanticFrameId = semantic?.frameId ?: -1,
        metricsFrameId = metricsFrameId,
        semanticStatus = semantic?.status ?: SemanticStatus.UNAVAILABLE,
        // Even when mock values produce a useful plan, the proposal cannot enter a real executor.
        executionMode = ExecutionMode.MOCK,
        inputSource = InputSource.MOCK,
        action = action,
        targetShutterSeconds = targetShutterSeconds,
        targetIso = targetIso,
        targetWhiteBalanceKelvin = targetWhiteBalanceKelvin,
        reasonCode = code,
        reason = reason,
        risk = risk,
        createdAtMs = nowMs,
        expiresAtMs = nowMs + config.proposalTtlMs,
    )
}
