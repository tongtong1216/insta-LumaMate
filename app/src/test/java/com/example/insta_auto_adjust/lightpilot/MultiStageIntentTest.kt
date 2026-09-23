package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiStageIntentTest {
    private val requestId = "24d45a9a-2f84-4c80-93c3-62e3c097ea5b"

    @Test
    fun `three independent high weights parse and increment only on confirmation`() {
        val result = IntentResponseMapper.parse(validResponse(), requestId)
        assertEquals(setOf(PolicyStage.EXPOSURE, PolicyStage.MOTION_NOISE,
            PolicyStage.COLOR_ATMOSPHERE), result.draft!!.let {
            MultiStageIntent(1, "夜跑保留霓虹", it.weights, it.exposurePriority,
                it.motionPriority, it.colorPriority, it.stabilityPreference).activeStages()
        })
        val tracker = IntentRevisionTracker(6)
        assertEquals(6, tracker.currentRevision())
        val confirmed = tracker.confirm(result, "夜跑保留霓虹")
        assertEquals(7, confirmed.revision)
        assertEquals(7, tracker.currentRevision())
    }

    @Test
    fun `active stage with null priority stays unconfirmed`() {
        val intent = validIntent().toMutableMap().apply { this["motion_priority"] = null }
        val response = validResponse(intent = intent, ambiguities = listOf("请选择运动优先级"))
        val parsed = IntentResponseMapper.parse(response, requestId)
        assertEquals(setOf(PolicyStage.MOTION_NOISE), parsed.draft!!.missingActivePriorities)
        assertThrows(IllegalArgumentException::class.java) {
            IntentRevisionTracker().confirm(parsed, "夜跑")
        }
    }

    @Test
    fun `strict mapper rejects bool range missing and extra fields`() {
        val invalidWeights = listOf<Any?>(true, "0.8", -0.1, 1.1, Double.NaN)
        invalidWeights.forEach { invalid ->
            val weights = validWeights().toMutableMap().apply { this["exposure"] = invalid }
            assertThrows(BackendException::class.java) {
                IntentResponseMapper.parse(validResponse(intent = validIntent(weights)), requestId)
            }
        }
        val missing = validWeights().toMutableMap().apply { remove("exposure") }
        assertThrows(BackendException::class.java) {
            IntentResponseMapper.parse(validResponse(intent = validIntent(missing)), requestId)
        }
        val extra = validWeights().toMutableMap().apply { this["ev"] = 1.0 }
        assertThrows(BackendException::class.java) {
            IntentResponseMapper.parse(validResponse(intent = validIntent(extra)), requestId)
        }
    }

    @Test
    fun `request id and model action injection are rejected`() {
        val wrongId = validResponse().toMutableMap().apply { this["request_id"] = "other" }
        assertThrows(BackendException::class.java) {
            IntentResponseMapper.parse(wrongId, requestId)
        }
        val injected = validResponse().toMutableMap().apply { this["ev"] = 1.0 }
        assertThrows(BackendException::class.java) {
            IntentResponseMapper.parse(injected, requestId)
        }
    }

    @Test
    fun `failed parse cannot carry automatic fallback intent`() {
        val response = validResponse().toMutableMap().apply { this["status"] = "unavailable" }
        val error = assertThrows(BackendException::class.java) {
            IntentResponseMapper.parse(response, requestId)
        }
        assertTrue(error.message!!.contains("UNSAFE"))
    }

    private fun validResponse(
        intent: Map<String, Any?> = validIntent(),
        ambiguities: List<String> = emptyList(),
    ): Map<String, Any?> = mapOf(
        "request_id" to requestId,
        "status" to "ok",
        "intent" to intent,
        "ambiguities" to ambiguities,
        "reason" to "三阶段同时激活",
    )

    private fun validIntent(weights: Map<String, Any?> = validWeights()): Map<String, Any?> = mapOf(
        "weights" to weights,
        "exposure_priority" to "subject_detail",
        "motion_priority" to "motion_clarity",
        "color_priority" to "colored_light_preservation",
        "stability_preference" to "high",
    )

    private fun validWeights(): Map<String, Any?> = mapOf(
        "exposure" to 0.8,
        "motion_noise" to 0.95,
        "color_atmosphere" to 0.85,
    )
}

