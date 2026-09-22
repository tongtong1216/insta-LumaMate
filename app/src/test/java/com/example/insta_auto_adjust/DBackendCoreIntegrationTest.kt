package com.example.insta_auto_adjust

import com.example.insta_auto_adjust.network.DBackendSceneAnalysisClient
import com.lightpilot.core.contract.v1.AnalyzeSceneMetrics
import com.lightpilot.core.contract.v1.AnalyzeSceneRequest
import com.lightpilot.core.contract.v1.V1SceneSemanticDataSource
import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExecutionMode
import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.InputSource
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.RecordingState
import com.lightpilot.core.model.ShutterSpeed
import com.lightpilot.core.model.UserIntent
import com.lightpilot.core.model.VisionMetrics
import com.lightpilot.core.policy.PolicyEngine
import com.lightpilot.core.policy.PolicyInput
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DBackendCoreIntegrationTest {
    @Test
    fun liveDBackendResponseCanEnterCorePolicy() {
        val baseUrl = liveValue("LIGHTPILOT_BACKEND_URL", "lightpilot.backendUrl")
        val imagePath = liveValue("LIGHTPILOT_TEST_IMAGE", "lightpilot.testImage")
        assumeTrue(
            "Set LIGHTPILOT_BACKEND_URL and LIGHTPILOT_TEST_IMAGE to run D-C live integration",
            baseUrl != null && imagePath != null
        )

        val image = Path.of(imagePath!!)
        assumeTrue("Test image does not exist: $image", Files.exists(image))

        val now = System.currentTimeMillis()
        val request = AnalyzeSceneRequest(
            frameId = 1L,
            intentRevision = 1L,
            intent = "优先看清主体，同时保留天空亮部细节",
            imageBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(image)),
            metrics = AnalyzeSceneMetrics(
                subjectBrightness = 0.25f,
                highlightRatio = 0.22f,
                darkRatio = 0.35f
            )
        )
        val semantic = V1SceneSemanticDataSource(
            client = DBackendSceneAnalysisClient(baseUrl!!)
        ).readSemantic(
            request = request,
            nowEpochMs = now
        )

        val input = PolicyInput(
            intent = UserIntent(
                revision = 1L,
                subjectDetail = 0.9f,
                highlightDetail = 0.8f,
                sourceText = request.intent,
                createdAtEpochMs = now
            ),
            metrics = VisionMetrics(
                frameId = "1",
                source = FrameSource.SDK_DECODED,
                roiVersion = "live-d-c-test",
                subjectBrightness = request.metrics?.subjectBrightness,
                backgroundBrightness = 0.65f,
                highlightRatio = request.metrics?.highlightRatio,
                darkRatio = request.metrics?.darkRatio,
                motionScore = null,
                capturedAtEpochMs = now,
                expiresAtEpochMs = now + 1_500L
            ),
            semantic = semantic,
            cameraState = cameraState(),
            capabilities = capabilities(now),
            nowEpochMs = now,
            inputSource = InputSource.REAL,
            executionMode = ExecutionMode.REAL
        )

        val proposal = PolicyEngine().propose(input)

        assertEquals("1", semantic.sourceFrameId)
        assertEquals(1L, semantic.intentRevision)
        assertEquals(1L, proposal.intentRevision)
        assertEquals("1", proposal.frameId)
        assertTrue(PolicyAction.entries.contains(proposal.action))
        if (semantic.analysisStatus != "ok") {
            assertEquals(PolicyAction.HOLD, proposal.action)
        }
    }

    private fun liveValue(envName: String, propertyName: String): String? {
        return System.getenv(envName)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(propertyName)?.takeIf { it.isNotBlank() }
    }

    private fun cameraState(): CameraState {
        return CameraState(
            connectionEpoch = "live-d-c-test",
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
            frameSource = FrameSource.SDK_DECODED,
            capabilityRevision = 1L
        )
    }

    private fun capabilities(now: Long): CameraCapabilities {
        return CameraCapabilities(
            supportedEv = listOf(-2.0, -1.0, 0.0, 1.0, 2.0),
            supportedShutterSpeed = listOf(ShutterSpeed(1.0, 60.0)),
            supportedIso = listOf(100, 200, 400),
            supportedWhiteBalance = listOf(3200, 5000, 6500),
            supportedExposurePrograms = listOf(ExposureProgram.AUTO),
            supportParam = setOf("exposureBias", "exposureProgram"),
            capabilityRevision = 1L,
            capturedAtEpochMs = now
        )
    }
}
