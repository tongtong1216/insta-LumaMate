package com.lightpilot.core

import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.GrayFrame
import com.lightpilot.core.model.RecordingState
import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.model.UserIntent
import com.lightpilot.core.model.VisionMetrics

object TestFixtures {
    const val NOW = 1_790_035_200_000L

    fun cameraState(
        currentEv: Double? = 0.0,
        exposureProgram: ExposureProgram = ExposureProgram.AUTO,
        isBusy: Boolean = false,
        isWorking: Boolean? = false
    ): CameraState {
        return CameraState(
            connectionEpoch = "session-001",
            mode = "VIDEO",
            exposureProgram = exposureProgram,
            currentEv = currentEv,
            currentIso = 100,
            currentShutterSpeed = null,
            currentWhiteBalance = 5000,
            isWorking = isWorking,
            isPreRecording = false,
            isBusy = isBusy,
            recordingState = RecordingState.IDLE,
            frameSource = FrameSource.MOCK,
            capabilityRevision = 1L
        )
    }

    fun capabilities(
        supportedEv: List<Double> = listOf(-2.0, -1.0, 0.0, 1.0, 2.0)
    ): CameraCapabilities {
        return CameraCapabilities(
            supportedEv = supportedEv,
            supportedShutterSpeed = emptyList(),
            supportedIso = listOf(100, 200, 400, 800),
            supportedWhiteBalance = listOf(3200, 5000, 6500),
            supportedExposurePrograms = listOf(ExposureProgram.AUTO),
            supportParam = setOf("exposureBias", "exposureProgram"),
            capabilityRevision = 1L,
            capturedAtEpochMs = NOW
        )
    }

    fun intent(
        revision: Long = 1L,
        subjectDetail: Float = 1f,
        highlightDetail: Float = 0.1f,
        exposureStability: Float = 0.2f
    ): UserIntent {
        return UserIntent(
            revision = revision,
            subjectDetail = subjectDetail,
            highlightDetail = highlightDetail,
            exposureStability = exposureStability,
            sourceText = "test intent",
            createdAtEpochMs = NOW
        )
    }

    fun semantic(
        frameId: String = "frame-001",
        available: Boolean = true
    ): SceneSemantic {
        return SceneSemantic(
            available = available,
            scene = "indoor_backlight",
            subjectType = "person",
            brightRegionType = "window",
            coloredLight = false,
            uncertainty = 0.1f,
            reason = if (available) "mock semantic" else "model_timeout",
            sourceFrameId = frameId,
            receivedAtEpochMs = NOW,
            expiresAtEpochMs = NOW + 4_000L
        )
    }

    fun metrics(
        frameId: String = "frame-001",
        subjectBrightness: Float = 0.20f,
        highlightRatio: Float = 0.05f,
        darkRatio: Float = 0.45f,
        source: FrameSource = FrameSource.MOCK
    ): VisionMetrics {
        return VisionMetrics(
            frameId = frameId,
            source = source,
            roiVersion = "test-roi",
            subjectBrightness = subjectBrightness,
            backgroundBrightness = 0.75f,
            highlightRatio = highlightRatio,
            darkRatio = darkRatio,
            motionScore = 0.05f,
            capturedAtEpochMs = NOW,
            expiresAtEpochMs = NOW + 1_500L
        )
    }

    fun frame(
        frameId: String = "frame-001",
        width: Int = 4,
        height: Int = 4,
        value: Float = 0.5f
    ): GrayFrame {
        return GrayFrame(
            frameId = frameId,
            width = width,
            height = height,
            pixels = FloatArray(width * height) { value },
            source = FrameSource.MOCK,
            capturedAtEpochMs = NOW
        )
    }
}
