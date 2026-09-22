package com.example.insta_auto_adjust.policy

import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExecutionMode
import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.GrayFrame
import com.lightpilot.core.model.InputSource
import com.lightpilot.core.model.PolicyProposal
import com.lightpilot.core.model.RecordingState
import com.lightpilot.core.model.Roi
import com.lightpilot.core.model.SafetyDecision
import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.model.ShutterSpeed
import com.lightpilot.core.model.UserIntent
import com.lightpilot.core.model.VisionMetrics
import com.lightpilot.core.policy.IntentPreset
import com.lightpilot.core.policy.PolicyCoordinator
import com.lightpilot.core.policy.PolicyEngine
import com.lightpilot.core.policy.PolicyInput
import com.lightpilot.core.policy.SafetyGuard
import com.lightpilot.core.policy.TemporalDecision
import com.lightpilot.core.policy.UserIntentPresets
import com.lightpilot.core.vision.FrameAnalyzer

enum class MockPolicyScenario(
    val title: String,
    val description: String,
    val preset: IntentPreset,
    val sourceText: String
) {
    SUBJECT_FIRST(
        title = "优先主体",
        description = "人物清楚优先，允许背景亮一点",
        preset = IntentPreset.SUBJECT_FIRST,
        sourceText = "优先看清人物，允许背景亮一点"
    ),
    HIGHLIGHT_FIRST(
        title = "优先亮部",
        description = "保留窗户和天空高光细节",
        preset = IntentPreset.HIGHLIGHT_FIRST,
        sourceText = "优先保留窗户和天空的亮部细节"
    ),
    BALANCED(
        title = "整体平衡",
        description = "主体和亮部都不明显偏向",
        preset = IntentPreset.BALANCED,
        sourceText = "主体和亮部整体平衡，变化不明显时保持不动"
    ),
    MOTION_FIRST(
        title = "运动清晰（模拟）",
        description = "优先减少拖影，模拟提高快门",
        preset = IntentPreset.MOTION_FIRST,
        sourceText = "运动中的主体不要拖影，优先拍清动作"
    ),
    LOW_NOISE(
        title = "低噪点（模拟）",
        description = "优先降低噪点，模拟降低 ISO",
        preset = IntentPreset.LOW_NOISE,
        sourceText = "夜景尽量干净，优先减少噪点"
    ),
    NATURAL_COLOR(
        title = "自然色彩（模拟）",
        description = "优先还原自然肤色，模拟调整白平衡",
        preset = IntentPreset.NATURAL_COLOR,
        sourceText = "人物肤色自然一点，不要偏色"
    ),
    ATMOSPHERE_FIRST(
        title = "保留氛围（模拟）",
        description = "保留现场暖色灯光氛围",
        preset = IntentPreset.ATMOSPHERE_FIRST,
        sourceText = "保留餐厅现场的暖色氛围"
    ),
    MULTI_INTENT_CONFLICT(
        title = "多意图冲突",
        description = "主体、亮部、运动和色彩偏好同时很强",
        preset = IntentPreset.BALANCED,
        sourceText = "人物要清楚，天空不能过曝，动作不能拖影，颜色还要自然"
    ),
    MODEL_UNAVAILABLE(
        title = "模型不可用",
        description = "场景语义不可用，必须安全保持",
        preset = IntentPreset.SUBJECT_FIRST,
        sourceText = "优先看清人物"
    )
}

data class PolicyDemoResult(
    val scenario: MockPolicyScenario?,
    val intent: UserIntent,
    val metrics: VisionMetrics,
    val proposal: PolicyProposal,
    val safetyDecision: SafetyDecision,
    val inputSource: InputSource = InputSource.MOCK,
    val executionMode: ExecutionMode = ExecutionMode.MOCK,
    val temporalDecision: TemporalDecision? = null,
    val canRequestConfirmation: Boolean = false
)

/**
 * Android-side Mock adapter for member C's local policy flow.
 *
 * This object intentionally does not call the camera SDK or a backend.
 * Replace its data sources later with A/D adapters that produce the shared
 * DTOs from the contract module.
 */
object PolicyDemo {
    fun newSession(): PolicyDemoSession {
        return PolicyDemoSession()
    }

    fun run(
        intent: UserIntent,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PolicyDemoResult {
        return runInternal(
            scenario = null,
            intent = intent,
            revision = intent.revision,
            nowEpochMs = nowEpochMs
        )
    }

    fun run(
        scenario: MockPolicyScenario,
        revision: Long = 1L,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PolicyDemoResult {
        val intent = createIntent(scenario, revision, nowEpochMs)
        return runInternal(
            scenario = scenario,
            intent = intent,
            revision = revision,
            nowEpochMs = nowEpochMs
        )
    }

    fun runCoordinated(
        coordinator: PolicyCoordinator,
        intent: UserIntent,
        scenario: MockPolicyScenario? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PolicyDemoResult {
        return runInternal(
            scenario = scenario,
            intent = intent,
            revision = intent.revision,
            nowEpochMs = nowEpochMs,
            coordinator = coordinator
        )
    }

    private fun runInternal(
        scenario: MockPolicyScenario?,
        intent: UserIntent,
        revision: Long,
        nowEpochMs: Long,
        coordinator: PolicyCoordinator? = null
    ): PolicyDemoResult {
        val metrics = analyzeMockFrame(nowEpochMs, scenario)
        val semantic = mockSemantic(metrics, nowEpochMs, scenario, intent)
        val cameraState = mockCameraState(scenario, intent)
        val capabilities = mockCapabilities(nowEpochMs)
        val input = PolicyInput(
            intent = intent,
            metrics = metrics,
            semantic = semantic,
            cameraState = cameraState,
            capabilities = capabilities,
            nowEpochMs = nowEpochMs,
            inputSource = InputSource.MOCK,
            executionMode = ExecutionMode.MOCK
        )
        val cycle = coordinator?.evaluate(
            input = input
        )
        val proposal = cycle?.candidateProposal ?: PolicyEngine().propose(input)
        val safetyDecision = cycle?.safetyDecision ?: SafetyGuard().evaluate(
            proposal = proposal,
            currentState = cameraState,
            capabilities = capabilities,
            currentMetrics = metrics,
            currentIntent = intent,
            nowEpochMs = nowEpochMs,
            commandId = "mock-command-${scenario?.name?.lowercase() ?: "natural-language"}-$revision"
        )
        return PolicyDemoResult(
            scenario = scenario,
            intent = intent,
            metrics = metrics,
            proposal = proposal,
            safetyDecision = safetyDecision,
            temporalDecision = cycle?.temporalDecision,
            canRequestConfirmation = cycle?.canRequestConfirmation ?: false
        )
    }

    private fun createIntent(
        scenario: MockPolicyScenario,
        revision: Long,
        nowEpochMs: Long
    ): UserIntent {
        if (scenario == MockPolicyScenario.MULTI_INTENT_CONFLICT) {
            return UserIntent(
                revision = revision,
                subjectDetail = 0.95f,
                highlightDetail = 0.90f,
                motionClarity = 0.95f,
                lowNoise = 0.90f,
                colorNeutrality = 0.90f,
                atmospherePreservation = 0.80f,
                exposureStability = 0.40f,
                sourceText = scenario.sourceText,
                createdAtEpochMs = nowEpochMs
            )
        }
        return UserIntentPresets.create(
            preset = scenario.preset,
            revision = revision,
            sourceText = scenario.sourceText,
            createdAtEpochMs = nowEpochMs
        )
    }

    fun runSubjectFirstBacklight(
        nowEpochMs: Long = System.currentTimeMillis()
    ): PolicyDemoResult {
        return run(MockPolicyScenario.SUBJECT_FIRST, nowEpochMs = nowEpochMs)
    }

    private fun analyzeMockFrame(
        nowEpochMs: Long,
        scenario: MockPolicyScenario? = null
    ): VisionMetrics {
        val frame = GrayFrame(
            frameId = "mock-frame-001",
            width = 4,
            height = 4,
            pixels = floatArrayOf(
                0.20f, 0.20f, 0.95f, 0.95f,
                0.20f, 0.20f, 0.95f, 0.95f,
                0.20f, 0.20f, 0.95f, 0.95f,
                0.20f, 0.20f, 0.95f, 0.95f
            ),
            source = FrameSource.MOCK,
            capturedAtEpochMs = nowEpochMs
        )
        val metrics = FrameAnalyzer().analyze(
            frame = frame,
            roi = Roi(0f, 0f, 0.5f, 1f, "subject-left"),
            nowEpochMs = nowEpochMs
        )
        return when (scenario) {
            MockPolicyScenario.MOTION_FIRST -> metrics.copy(motionScore = 0.85f)
            MockPolicyScenario.LOW_NOISE -> metrics.copy(motionScore = 0.10f)
            else -> metrics
        }
    }

    private fun mockSemantic(
        metrics: VisionMetrics,
        nowEpochMs: Long,
        scenario: MockPolicyScenario?,
        intent: UserIntent
    ): SceneSemantic {
        val unavailable = scenario == MockPolicyScenario.MODEL_UNAVAILABLE
        val atmosphereScene = scenario == MockPolicyScenario.ATMOSPHERE_FIRST ||
            intent.atmospherePreservation > intent.colorNeutrality
        return SceneSemantic(
            available = !unavailable,
            scene = if (atmosphereScene) "warm_restaurant" else "indoor_backlight",
            subjectType = "person",
            brightRegionType = "window",
            coloredLight = atmosphereScene,
            uncertainty = if (unavailable) 1.0f else 0.12f,
            reason = if (unavailable) "model_timeout" else "mock_semantic",
            sourceFrameId = metrics.frameId,
            receivedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = if (unavailable) null else nowEpochMs + 4_000L
        )
    }

    private fun mockCameraState(
        scenario: MockPolicyScenario?,
        intent: UserIntent
    ): CameraState {
        val atmospherePreferred = scenario == MockPolicyScenario.ATMOSPHERE_FIRST ||
            intent.atmospherePreservation > intent.colorNeutrality
        return CameraState(
            connectionEpoch = "mock-session-001",
            mode = "VIDEO",
            exposureProgram = ExposureProgram.AUTO,
            currentEv = 0.0,
            currentIso = 800,
            currentShutterSpeed = ShutterSpeed(1.0, 60.0),
            currentWhiteBalance = if (atmospherePreferred) 5000 else 3200,
            isWorking = false,
            isPreRecording = false,
            isBusy = false,
            recordingState = RecordingState.IDLE,
            frameSource = FrameSource.MOCK,
            capabilityRevision = 1L
        )
    }

    private fun mockCapabilities(nowEpochMs: Long): CameraCapabilities {
        return CameraCapabilities(
            supportedEv = listOf(-2.0, -1.0, 0.0, 1.0, 2.0),
            supportedShutterSpeed = listOf(
                ShutterSpeed(1.0, 30.0),
                ShutterSpeed(1.0, 60.0),
                ShutterSpeed(1.0, 120.0),
                ShutterSpeed(1.0, 240.0)
            ),
            supportedIso = listOf(100, 200, 400, 800),
            supportedWhiteBalance = listOf(3200, 5000, 6500),
            supportedExposurePrograms = listOf(ExposureProgram.AUTO),
            supportParam = setOf(
                "exposureBias",
                "exposureProgram",
                "exposureShutterSpeed",
                "ISO",
                "whiteBalance"
            ),
            capabilityRevision = 1L,
            capturedAtEpochMs = nowEpochMs
        )
    }
}

class PolicyDemoSession {
    private val coordinator = PolicyCoordinator()

    fun run(
        intent: UserIntent,
        scenario: MockPolicyScenario? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PolicyDemoResult {
        return PolicyDemo.runCoordinated(
            coordinator = coordinator,
            intent = intent,
            scenario = scenario,
            nowEpochMs = nowEpochMs
        )
    }

    fun reset() {
        coordinator.reset()
    }
}
