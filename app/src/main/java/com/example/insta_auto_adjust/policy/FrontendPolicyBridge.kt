package com.example.insta_auto_adjust.policy

import com.example.insta_auto_adjust.intent.LocalKeywordIntentResolver
import com.example.insta_auto_adjust.presentation.PolicyProposalUi
import com.example.insta_auto_adjust.presentation.ShootingIntent
import com.example.insta_auto_adjust.presentation.ShootingUiState
import com.example.insta_auto_adjust.presentation.UserIntentUi
import com.example.insta_auto_adjust.presentation.VisionMetricsUi
import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExecutionMode
import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.InputSource
import com.lightpilot.core.model.ParameterTarget
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.PolicyProposal
import com.lightpilot.core.model.RecordingState
import com.lightpilot.core.model.RiskLevel
import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.model.ShutterSpeed
import com.lightpilot.core.model.UserIntent
import com.lightpilot.core.model.VisionMetrics
import com.lightpilot.core.policy.PolicyConfig
import com.lightpilot.core.policy.PolicyEngine
import com.lightpilot.core.policy.PolicyInput

data class FrontendPolicyResult(
    val userIntentUi: UserIntentUi,
    val visionMetricsUi: VisionMetricsUi,
    val sceneRisk: String,
    val proposalUi: PolicyProposalUi,
    val coreProposal: PolicyProposal
)

data class FrontendPolicyDraft(
    val userIntentUi: UserIntentUi,
    val visionMetricsUi: VisionMetricsUi,
    val userIntent: UserIntent,
    val metrics: VisionMetrics
)

class FrontendPolicyBridge(
    private val policyEngine: PolicyEngine = PolicyEngine(
        PolicyConfig(maxFrameAgeMs = REAL_ANALYSIS_WINDOW_MS)
    )
) {
    fun prepare(
        shootingState: ShootingUiState,
        revision: Long,
        frameId: String,
        nowEpochMs: Long,
        realMetrics: VisionMetrics? = null,
        realMetricsUi: VisionMetricsUi? = null
    ): FrontendPolicyDraft {
        val userIntentUi = LocalKeywordIntentResolver.resolve(
            rawText = shootingState.intentInputText,
            selectedIntent = shootingState.selectedIntent
        )
        val metricsUi = realMetricsUi
            ?: realMetrics?.toUi()
            ?: mockVisionMetrics(
                intent = shootingState.selectedIntent,
                frameId = frameId,
                nowEpochMs = nowEpochMs
            )
        return FrontendPolicyDraft(
            userIntentUi = userIntentUi,
            visionMetricsUi = metricsUi,
            userIntent = userIntentUi.toCoreIntent(
                selectedIntent = shootingState.selectedIntent,
                revision = revision,
                createdAtEpochMs = nowEpochMs
            ),
            metrics = realMetrics ?: metricsUi.toCoreMetrics()
        )
    }

    fun analyze(
        shootingState: ShootingUiState,
        revision: Long,
        frameId: String,
        nowEpochMs: Long,
        supportedEv: List<Double> = DEFAULT_SUPPORTED_EV
    ): FrontendPolicyResult {
        val draft = prepare(
            shootingState = shootingState,
            revision = revision,
            frameId = frameId,
            nowEpochMs = nowEpochMs
        )
        val semantic = mockSceneSemantic(
            intent = shootingState.selectedIntent,
            revision = revision,
            metrics = draft.metrics,
            nowEpochMs = nowEpochMs
        )
        return propose(
            shootingState = shootingState,
            draft = draft,
            semantic = semantic,
            nowEpochMs = nowEpochMs,
            supportedEv = supportedEv
        )
    }

    fun propose(
        shootingState: ShootingUiState,
        draft: FrontendPolicyDraft,
        semantic: SceneSemantic,
        nowEpochMs: Long,
        supportedEv: List<Double> = DEFAULT_SUPPORTED_EV,
        cameraState: CameraState? = null,
        capabilities: CameraCapabilities? = null,
        inputSource: InputSource = InputSource.MOCK,
        executionMode: ExecutionMode = ExecutionMode.MOCK
    ): FrontendPolicyResult {
        val proposal = policyEngine.propose(
            PolicyInput(
                intent = draft.userIntent,
                metrics = draft.metrics,
                semantic = semantic,
                cameraState = cameraState ?: mockCameraState(),
                capabilities = capabilities ?: mockCapabilities(
                    supportedEv = supportedEv,
                    nowEpochMs = nowEpochMs
                ),
                nowEpochMs = nowEpochMs,
                userLocked = shootingState.userLocked,
                inputSource = inputSource,
                executionMode = executionMode
            )
        )

        return FrontendPolicyResult(
            userIntentUi = draft.userIntentUi,
            visionMetricsUi = draft.visionMetricsUi,
            sceneRisk = sceneRiskText(semantic, proposal),
            proposalUi = proposal.toUi(),
            coreProposal = proposal
        )
    }

    private fun mockVisionMetrics(
        intent: ShootingIntent,
        frameId: String,
        nowEpochMs: Long
    ): VisionMetricsUi {
        return when (intent) {
            ShootingIntent.SUBJECT_PRIORITY -> VisionMetricsUi(
                frameId = frameId,
                subjectBrightness = 0.20,
                highlightRatio = 0.08,
                darkRatio = 0.45,
                roiVersion = "mock-subject-priority",
                timestamp = nowEpochMs
            )

            ShootingIntent.HIGHLIGHT_PRIORITY -> VisionMetricsUi(
                frameId = frameId,
                subjectBrightness = 0.62,
                highlightRatio = 0.65,
                darkRatio = 0.08,
                roiVersion = "mock-highlight-priority",
                timestamp = nowEpochMs
            )

            ShootingIntent.STABLE_EXPOSURE -> VisionMetricsUi(
                frameId = frameId,
                subjectBrightness = 0.44,
                highlightRatio = 0.16,
                darkRatio = 0.25,
                roiVersion = "mock-stable-exposure",
                timestamp = nowEpochMs
            )

            ShootingIntent.BALANCED -> VisionMetricsUi(
                frameId = frameId,
                subjectBrightness = 0.38,
                highlightRatio = 0.20,
                darkRatio = 0.30,
                roiVersion = "mock-balanced",
                timestamp = nowEpochMs
            )
        }
    }

    private fun mockSceneSemantic(
        intent: ShootingIntent,
        revision: Long,
        metrics: VisionMetrics,
        nowEpochMs: Long
    ): SceneSemantic {
        return SceneSemantic(
            available = true,
            scene = when (intent) {
                ShootingIntent.HIGHLIGHT_PRIORITY -> "outdoor_backlit"
                else -> "indoor_backlit"
            },
            subjectType = "person",
            brightRegionType = when (intent) {
                ShootingIntent.HIGHLIGHT_PRIORITY -> "sky"
                else -> "window"
            },
            coloredLight = false,
            uncertainty = 0.0f,
            reason = "front_end_mock_semantic",
            sourceFrameId = metrics.frameId,
            receivedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + 4_000L,
            intentRevision = revision,
            uncertaintyNotes = emptyList(),
            analysisStatus = "ok"
        )
    }

    private fun mockCameraState(): CameraState {
        return CameraState(
            connectionEpoch = "frontend-mock-session",
            mode = "VIDEO",
            exposureProgram = ExposureProgram.AUTO,
            currentEv = MOCK_BASE_EV,
            currentIso = 100,
            currentShutterSpeed = ShutterSpeed(1.0, 60.0),
            currentWhiteBalance = 5_000,
            isWorking = false,
            isPreRecording = false,
            isBusy = false,
            recordingState = RecordingState.IDLE,
            frameSource = FrameSource.MOCK,
            capabilityRevision = 1L
        )
    }

    private fun mockCapabilities(
        supportedEv: List<Double>,
        nowEpochMs: Long
    ): CameraCapabilities {
        return CameraCapabilities(
            supportedEv = supportedEv.ifEmpty { DEFAULT_SUPPORTED_EV },
            supportedShutterSpeed = listOf(ShutterSpeed(1.0, 60.0)),
            supportedIso = listOf(100, 200, 400),
            supportedWhiteBalance = listOf(3_200, 5_000, 6_500),
            supportedExposurePrograms = listOf(ExposureProgram.AUTO),
            supportParam = setOf("exposureBias", "exposureProgram"),
            capabilityRevision = 1L,
            capturedAtEpochMs = nowEpochMs
        )
    }

    private fun UserIntentUi.toCoreIntent(
        selectedIntent: ShootingIntent,
        revision: Long,
        createdAtEpochMs: Long
    ): UserIntent {
        return UserIntent(
            revision = revision,
            subjectDetail = subjectPriority.toFloat().coerceIn(0f, 1f),
            highlightDetail = highlightProtection.toFloat().coerceIn(0f, 1f),
            exposureStability = stabilityPreference.toFloat().coerceIn(0f, 1f),
            sourceText = rawText.ifBlank { selectedIntent.displayText() },
            createdAtEpochMs = createdAtEpochMs
        )
    }

    private fun VisionMetricsUi.toCoreMetrics(): VisionMetrics {
        return toCoreMetrics(FrameSource.MOCK)
    }

    private fun VisionMetricsUi.toCoreMetrics(source: FrameSource): VisionMetrics {
        return VisionMetrics(
            frameId = frameId,
            source = source,
            roiVersion = roiVersion,
            subjectBrightness = subjectBrightness.toFloat(),
            backgroundBrightness = 0.65f,
            highlightRatio = highlightRatio.toFloat(),
            darkRatio = darkRatio.toFloat(),
            motionScore = null,
            capturedAtEpochMs = timestamp,
            expiresAtEpochMs = timestamp + 1_500L
        )
    }

    private fun VisionMetrics.toUi(): VisionMetricsUi {
        return VisionMetricsUi(
            frameId = frameId,
            subjectBrightness = (subjectBrightness ?: 0.5f).toDouble(),
            highlightRatio = (highlightRatio ?: 0.0f).toDouble(),
            darkRatio = (darkRatio ?: 0.0f).toDouble(),
            roiVersion = roiVersion,
            timestamp = capturedAtEpochMs
        )
    }

    private fun PolicyProposal.toUi(): PolicyProposalUi {
        return PolicyProposalUi(
            proposalId = proposalId,
            action = actionText(),
            reason = reason,
            cost = "risk=${risk.displayName()}, cost=${"%.2f".format(cost)}, " +
                "source=${inputSource.name.lowercase()}, mode=${executionMode.name.lowercase()}",
            validUntil = validUntilEpochMs
        )
    }

    private fun PolicyProposal.actionText(): String {
        return when (action) {
            PolicyAction.EV_ONE_STEP_UP -> "建议提高 EV 到 ${parameter.describe()}"
            PolicyAction.EV_ONE_STEP_DOWN -> "建议降低 EV 到 ${parameter.describe()}"
            PolicyAction.HOLD -> "建议保持当前参数"
            PolicyAction.SET_SHUTTER -> "模拟建议：${parameter.describe()}"
            PolicyAction.SET_ISO -> "模拟建议：${parameter.describe()}"
            PolicyAction.SET_WHITE_BALANCE -> "模拟建议：${parameter.describe()}"
        }
    }

    private fun ParameterTarget?.describe(): String {
        return when (this) {
            null -> "--"
            is ParameterTarget.Ev -> formatEv(value)
            is ParameterTarget.Shutter -> "${value.numerator}/${value.denominator}s"
            is ParameterTarget.Iso -> "ISO $value"
            is ParameterTarget.WhiteBalance -> "${value}K"
        }
    }

    private fun RiskLevel.displayName(): String {
        return when (this) {
            RiskLevel.LOW -> "低"
            RiskLevel.MEDIUM -> "中"
            RiskLevel.HIGH -> "高"
            RiskLevel.UNKNOWN -> "未知"
        }
    }

    private fun sceneRiskText(
        semantic: SceneSemantic,
        proposal: PolicyProposal
    ): String {
        return "D状态=${semantic.analysisStatus ?: "mock"}，" +
            "场景=${semantic.scene ?: "--"}，主体=${semantic.subjectType ?: "--"}，" +
            "亮区=${semantic.brightRegionType ?: "--"}，C 策略动作=${proposal.action}。"
    }

    private fun ShootingIntent.displayText(): String {
        return when (this) {
            ShootingIntent.SUBJECT_PRIORITY -> "主体优先"
            ShootingIntent.BALANCED -> "整体平衡"
            ShootingIntent.HIGHLIGHT_PRIORITY -> "高光优先"
            ShootingIntent.STABLE_EXPOSURE -> "稳定曝光"
        }
    }

    private fun formatEv(value: Double): String {
        return if (value > 0.0) "+$value" else value.toString()
    }

    companion object {
        const val MOCK_BASE_EV = 0.0
        const val REAL_ANALYSIS_WINDOW_MS = 35_000L
        val DEFAULT_SUPPORTED_EV = listOf(-2.0, -1.0, 0.0, 1.0, 2.0)
    }
}
