package com.lightpilot.core

import com.lightpilot.core.contract.v1.AnalyzeSceneRequest
import com.lightpilot.core.contract.v1.AnalyzeSceneResponse
import com.lightpilot.core.contract.v1.SceneAnalysisStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SceneAnalysisContractTest {
    @Test
    fun requestUsesDWireConstraintsAndMapsMetrics() {
        val metrics = TestFixtures.metrics(frameId = "152")
        val request = AnalyzeSceneRequest.fromMetrics(
            metrics = metrics,
            intentRevision = 3L,
            intentText = "优先拍清楚主体",
            imageBase64 = "base64-image"
        )

        assertEquals(152L, request.frameId)
        assertEquals(3L, request.intentRevision)
        assertEquals(0.20f, request.metrics?.subjectBrightness)
        assertEquals(0.05f, request.metrics?.highlightRatio)
        assertEquals(0.45f, request.metrics?.darkRatio)
    }

    @Test
    fun nonNumericInternalFrameIdIsRejectedAtDBoundary() {
        assertFailsWith<IllegalStateException> {
            AnalyzeSceneRequest.fromMetrics(
                metrics = TestFixtures.metrics(frameId = "mock-frame-001"),
                intentRevision = 1L,
                intentText = "测试",
                imageBase64 = "base64-image"
            )
        }
    }

    @Test
    fun okResponseMapsToUsableSceneSemanticAndPreservesNotes() {
        val request = request()
        val semantic = AnalyzeSceneResponse(
            frameId = 152L,
            intentRevision = 3L,
            status = SceneAnalysisStatus.OK,
            scene = "indoor_backlit",
            subjectType = "person",
            brightRegionType = "window",
            coloredLight = false,
            uncertainty = listOf("主体边界不完全确定"),
            reason = "主体较暗，背景更亮"
        ).toSceneSemantic(request, nowEpochMs = TestFixtures.NOW)

        assertTrue(semantic.available)
        assertEquals("152", semantic.sourceFrameId)
        assertEquals(3L, semantic.intentRevision)
        assertEquals(1.0f, semantic.uncertainty)
        assertEquals(listOf("主体边界不完全确定"), semantic.uncertaintyNotes)
        assertEquals("ok", semantic.analysisStatus)
    }

    @Test
    fun mockAndUnavailableResponsesBecomeHoldableUnavailableSemantics() {
        val request = request()
        val mock = AnalyzeSceneResponse(
            frameId = 152L,
            intentRevision = 3L,
            status = SceneAnalysisStatus.MOCK,
            scene = null,
            subjectType = null,
            brightRegionType = null,
            coloredLight = null,
            uncertainty = listOf("mock_result"),
            reason = "仅用于联调"
        ).toSceneSemantic(request, TestFixtures.NOW)
        val unavailable = AnalyzeSceneResponse(
            frameId = 152L,
            intentRevision = 3L,
            status = SceneAnalysisStatus.UNAVAILABLE,
            scene = null,
            subjectType = null,
            brightRegionType = null,
            coloredLight = null,
            uncertainty = listOf("timeout"),
            reason = "模型超时"
        ).toSceneSemantic(request, TestFixtures.NOW)

        assertFalse(mock.available)
        assertFalse(unavailable.available)
        assertEquals("timeout", unavailable.uncertaintyNotes.single())
        assertEquals("mock", mock.analysisStatus)
        assertEquals("unavailable", unavailable.analysisStatus)
    }

    @Test
    fun mismatchedBindingCannotBecomeUsableSemantic() {
        val request = request()
        val semantic = AnalyzeSceneResponse(
            frameId = 153L,
            intentRevision = 3L,
            status = SceneAnalysisStatus.OK,
            scene = "indoor_backlit",
            subjectType = "person",
            brightRegionType = "window",
            coloredLight = false,
            uncertainty = emptyList(),
            reason = "旧帧"
        ).toSceneSemantic(request, TestFixtures.NOW)

        assertFalse(semantic.available)
        assertEquals("response_binding_mismatch", semantic.reason)
        assertEquals("153", semantic.sourceFrameId)
    }

    @Test
    fun invalidUnavailableResponseIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AnalyzeSceneResponse(
                frameId = 152L,
                intentRevision = 3L,
                status = SceneAnalysisStatus.UNAVAILABLE,
                scene = null,
                subjectType = null,
                brightRegionType = null,
                coloredLight = null,
                uncertainty = listOf("not_a_stable_code"),
                reason = null
            )
        }
    }

    private fun request(): AnalyzeSceneRequest {
        return AnalyzeSceneRequest(
            frameId = 152L,
            intentRevision = 3L,
            intent = "优先拍清楚主体",
            imageBase64 = "base64-image",
            metrics = null
        )
    }
}
