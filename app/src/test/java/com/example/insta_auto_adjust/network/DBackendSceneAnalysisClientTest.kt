package com.example.insta_auto_adjust.network

import com.lightpilot.core.contract.v1.AnalyzeSceneIntent
import com.lightpilot.core.contract.v1.AnalyzeSceneMetrics
import com.lightpilot.core.contract.v1.AnalyzeSceneRequest
import com.lightpilot.core.contract.v1.ExposurePriority
import com.lightpilot.core.contract.v1.SceneAnalysisStatus
import com.lightpilot.core.contract.v1.StabilityPreference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DBackendSceneAnalysisClientTest {
    @Test
    fun serializesExactRc3RequestKeys() {
        val json = AnalyzeSceneRequest(
            frameId = 152L,
            intentRevision = 3L,
            intent = AnalyzeSceneIntent(
                ExposurePriority.SUBJECT_DETAIL,
                StabilityPreference.NORMAL,
                "优先拍清楚人物"
            ),
            imageBase64 = "abc",
            metrics = AnalyzeSceneMetrics(0.31f, 0.68f, 0.08f, 0.42f)
        ).toJsonObject()

        assertEquals(setOf("frame_id", "intent_revision", "intent", "image_base64", "metrics"), json.keys().asSequence().toSet())
        val intent = json.getJSONObject("intent")
        assertEquals("subject_detail", intent.getString("exposure_priority"))
        assertEquals("normal", intent.getString("stability_preference"))
        val metrics = json.getJSONObject("metrics")
        assertTrue(metrics.has("background_brightness"))
        assertTrue(metrics.has("highlight_clipping_ratio"))
        assertFalse(metrics.has("highlight_ratio"))
    }

    @Test
    fun parsesStructuredWarningDetails() {
        val response = JSONObject(
            """
            {
              "frame_id": 152,
              "intent_revision": 3,
              "status": "ok",
              "scene": "outdoor_daylight",
              "subject_type": "object",
              "bright_region_type": "none",
              "colored_light": false,
              "uncertainty": ["主体被遮挡"],
              "uncertainty_details": [{
                "code": "subject_occluded",
                "severity": "warning",
                "affects": ["subject_type", "subject_roi"],
                "message": "主体区域不可靠"
              }],
              "reason": "仅供显示"
            }
            """.trimIndent()
        ).toAnalyzeSceneResponse()

        assertEquals(SceneAnalysisStatus.OK, response.status)
        assertEquals("subject_occluded", response.uncertaintyDetails.single().code)
        assertTrue("subject_roi" in response.uncertaintyDetails.single().affects)
    }
}
