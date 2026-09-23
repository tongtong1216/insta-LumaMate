package com.lightpilot.core

import com.lightpilot.core.contract.v1.AnalyzeSceneIntent
import com.lightpilot.core.contract.v1.AnalyzeSceneRequest
import com.lightpilot.core.contract.v1.AnalyzeSceneResponse
import com.lightpilot.core.contract.v1.AnalyzeSceneUncertaintyDetail
import com.lightpilot.core.contract.v1.ExposurePriority
import com.lightpilot.core.contract.v1.SceneAnalysisStatus
import com.lightpilot.core.contract.v1.StabilityPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneAnalysisContractTest {
    @Test
    fun requestUsesRc3IntentAndMetricFields() {
        val request = AnalyzeSceneRequest.fromMetrics(
            metrics = TestFixtures.metrics(frameId = "152"),
            intentRevision = 3L,
            intent = intent(),
            imageBase64 = "base64-image"
        )

        assertEquals(152L, request.frameId)
        assertEquals(ExposurePriority.SUBJECT_DETAIL, request.intent.exposurePriority)
        assertEquals(0.20f, request.metrics?.subjectBrightness)
        assertEquals(0.75f, request.metrics?.backgroundBrightness)
        assertEquals(0.05f, request.metrics?.highlightClippingRatio)
        assertEquals(0.45f, request.metrics?.darkRatio)
    }

    @Test
    fun nonNumericInternalFrameIdIsRejectedAtDBoundary() {
        assertFailsWith<IllegalStateException> {
            AnalyzeSceneRequest.fromMetrics(
                metrics = TestFixtures.metrics(frameId = "mock-frame-001"),
                intentRevision = 1L,
                intent = intent(),
                imageBase64 = "base64-image"
            )
        }
    }

    @Test
    fun structuredWarningRemainsUsableAndIsPreservedForFieldDegradation() {
        val semantic = response(
            uncertainty = listOf("主体被部分遮挡"),
            details = listOf(
                AnalyzeSceneUncertaintyDetail(
                    code = "subject_occluded",
                    severity = "warning",
                    affects = setOf("subject_type", "subject_roi"),
                    message = "主体边界不完全确定"
                )
            )
        ).toSceneSemantic(request(), TestFixtures.NOW)

        assertTrue(semantic.available)
        assertNull(semantic.uncertainty)
        assertTrue(semantic.hasUncertaintyAffecting("subject_roi"))
        assertFalse(semantic.hasUncertaintyAffecting("bright_region_type"))
        assertEquals(TestFixtures.NOW + 60_000L, semantic.expiresAtEpochMs)
    }

    @Test
    fun blockingOrAffectsAllCannotEnterSemanticCache() {
        val blocking = response(
            details = listOf(
                AnalyzeSceneUncertaintyDetail(
                    code = "image_unusable",
                    severity = "blocking",
                    affects = setOf("scene"),
                    message = null
                )
            )
        ).toSceneSemantic(request(), TestFixtures.NOW)
        val affectsAll = response(
            details = listOf(
                AnalyzeSceneUncertaintyDetail(
                    code = "global_uncertainty",
                    severity = "warning",
                    affects = setOf("all"),
                    message = null
                )
            )
        ).toSceneSemantic(request(), TestFixtures.NOW)

        assertFalse(blocking.available)
        assertFalse(affectsAll.available)
    }

    @Test
    fun rc2UnstructuredUncertaintyStillHoldsWholeFrame() {
        val semantic = response(uncertainty = listOf("主体不确定"))
            .toSceneSemantic(request(), TestFixtures.NOW)
        assertFalse(semantic.available)
    }

    @Test
    fun mockUnavailableAndMismatchedResponsesAreNotUsable() {
        val mock = AnalyzeSceneResponse(
            152L, 3L, SceneAnalysisStatus.MOCK,
            null, null, null, null, listOf("mock_result"), reason = "联调"
        ).toSceneSemantic(request(), TestFixtures.NOW)
        val unavailable = AnalyzeSceneResponse(
            152L, 3L, SceneAnalysisStatus.UNAVAILABLE,
            null, null, null, null, listOf("timeout"), reason = "超时"
        ).toSceneSemantic(request(), TestFixtures.NOW)
        val mismatch = response(frameId = 153L).toSceneSemantic(request(), TestFixtures.NOW)

        assertFalse(mock.available)
        assertFalse(unavailable.available)
        assertFalse(mismatch.available)
        assertEquals("response_binding_mismatch", mismatch.reason)
    }

    @Test
    fun invalidUnavailableResponseIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AnalyzeSceneResponse(
                152L, 3L, SceneAnalysisStatus.UNAVAILABLE,
                null, null, null, null, listOf("not_a_stable_code"), reason = null
            )
        }
    }

    private fun response(
        frameId: Long = 152L,
        uncertainty: List<String> = emptyList(),
        details: List<AnalyzeSceneUncertaintyDetail> = emptyList()
    ) = AnalyzeSceneResponse(
        frameId = frameId,
        intentRevision = 3L,
        status = SceneAnalysisStatus.OK,
        scene = "indoor_backlit",
        subjectType = "person",
        brightRegionType = "window",
        coloredLight = false,
        uncertainty = uncertainty,
        uncertaintyDetails = details,
        reason = "主体较暗，背景更亮"
    )

    private fun request() = AnalyzeSceneRequest(
        frameId = 152L,
        intentRevision = 3L,
        intent = intent(),
        imageBase64 = "base64-image",
        metrics = null
    )

    private fun intent() = AnalyzeSceneIntent(
        exposurePriority = ExposurePriority.SUBJECT_DETAIL,
        stabilityPreference = StabilityPreference.NORMAL,
        sourceText = "优先拍清楚主体"
    )
}
