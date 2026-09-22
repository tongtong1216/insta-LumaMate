package com.example.insta_auto_adjust.policy

import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.GrayFrame
import com.lightpilot.core.model.PolicyProposal
import com.lightpilot.core.model.RecordingState
import com.lightpilot.core.model.Roi
import com.lightpilot.core.model.SafetyDecision
import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.model.ShutterSpeed
import com.lightpilot.core.model.UserIntent
import com.lightpilot.core.model.VisionMetrics
import com.lightpilot.core.policy.PolicyEngine
import com.lightpilot.core.policy.PolicyInput
import com.lightpilot.core.policy.SafetyGuard
import com.lightpilot.core.policy.UserIntentPresets
import com.lightpilot.core.policy.IntentPreset
import com.lightpilot.core.vision.FrameAnalyzer

data class PolicyDemoResult(
    val intent: UserIntent,
    val metrics: VisionMetrics,
    val proposal: PolicyProposal,
    val safetyDecision: SafetyDecision
)

object PolicyDemo {
    fun runSubjectFirstBacklight(nowEpochMs: Long = System.currentTimeMillis()): PolicyDemoResult {
        val intent = UserIntentPresets.create(
            preset = IntentPreset.SUBJECT_FIRST,
            revision = 1L,
            sourceText = "优先看清人物，允许背景亮一点",
            createdAtEpochMs = nowEpochMs
        )
        val frame = GrayFrame(
            frameId = "mock-frame-001",
            width = 4,
            height = 4,
            pixels = floatArrayOf(
                0.20f, 0.20f, 0.90f, 0.95f,
                0.20f, 0.20f, 0.90f, 0.95f,
                0.20f, 0.20f, 0.90f, 0.95f,
                0.20f, 0.20f, 0.90f, 0.95f
            ),
            source = FrameSource.MOCK,
            capturedAtEpochMs = nowEpochMs
        )
        val metrics = FrameAnalyzer().analyze(
            frame = frame,
            roi = Roi(0f, 0f, 0.5f, 1f, "subject-left"),
            nowEpochMs = nowEpochMs
        )
        val semantic = SceneSemantic(
            available = true,
            scene = "indoor_backlight",
            subjectType = "person",
            brightRegionType = "window",
            coloredLight = false,
            uncertainty = 0.12f,
            reason = "mock semantic",
            sourceFrameId = metrics.frameId,
            receivedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + 4_000L
        )
        val cameraState = CameraState(
            connectionEpoch = "mock-session-001",
            mode = "VIDEO",
            exposureProgram = ExposureProgram.AUTO,
            currentEv = 0.0,
            currentIso = 100,
            currentShutterSpeed = ShutterSpeed(1.0, 60.0),
            currentWhiteBalance = 5000,
            isWorking = false,
            isPreRecording = false,
            isBusy = false,
            recordingState = RecordingState.IDLE,
            frameSource = FrameSource.MOCK,
            capabilityRevision = 1L
        )
        val capabilities = CameraCapabilities(
            supportedEv = listOf(-2.0, -1.0, 0.0, 1.0, 2.0),
            supportedShutterSpeed = emptyList(),
            supportedIso = listOf(100, 200, 400, 800),
            supportedWhiteBalance = listOf(3200, 5000, 6500),
            supportedExposurePrograms = listOf(ExposureProgram.AUTO),
            supportParam = setOf("exposureBias", "exposureProgram"),
            capabilityRevision = 1L,
            capturedAtEpochMs = nowEpochMs
        )
        val proposal = PolicyEngine().propose(
            PolicyInput(
                intent = intent,
                metrics = metrics,
                semantic = semantic,
                cameraState = cameraState,
                capabilities = capabilities,
                nowEpochMs = nowEpochMs
            )
        )
        val safetyDecision = SafetyGuard().evaluate(
            proposal = proposal,
            currentState = cameraState,
            capabilities = capabilities,
            currentMetrics = metrics,
            currentIntent = intent,
            nowEpochMs = nowEpochMs,
            commandId = "mock-command-001"
        )
        return PolicyDemoResult(intent, metrics, proposal, safetyDecision)
    }
}
