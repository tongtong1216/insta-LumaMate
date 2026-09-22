package com.lightpilot.core.contract.v1

import com.lightpilot.core.contract.SceneSemanticDataSource
import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.model.VisionMetrics

/**
 * Kotlin representation of D's /api/v1/analyze-scene wire contract.
 *
 * The JSON adapter is responsible for translating these camelCase properties
 * to the exact snake_case keys documented by D.
 */
enum class SceneAnalysisStatus(val wireValue: String) {
    OK("ok"),
    MOCK("mock"),
    UNAVAILABLE("unavailable");

    companion object {
        fun fromWireValue(value: String): SceneAnalysisStatus {
            return entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported scene analysis status: $value")
        }
    }
}

data class AnalyzeSceneMetrics(
    val subjectBrightness: Float?,
    val highlightRatio: Float?,
    val darkRatio: Float?
) {
    init {
        listOf(subjectBrightness, highlightRatio, darkRatio).forEach { value ->
            value?.let { require(it in 0f..1f) }
        }
    }
}

data class AnalyzeSceneRequest(
    val frameId: Long,
    val intentRevision: Long,
    val intent: String,
    val imageBase64: String,
    val metrics: AnalyzeSceneMetrics?
) {
    init {
        require(frameId >= 0L) { "frame_id must be non-negative" }
        require(intentRevision >= 0L) { "intent_revision must be non-negative" }
        require(intent.trim().length in 1..1_000) {
            "intent must contain 1..1000 non-whitespace characters"
        }
        require(imageBase64.isNotBlank()) { "image_base64 must not be blank" }
    }

    companion object {
        /**
         * D v1 requires a numeric Int64 frame_id. C's internal frame ID is
         * kept as String, so this boundary conversion rejects non-numeric IDs
         * instead of silently sending a different identifier.
         */
        fun fromMetrics(
            metrics: VisionMetrics,
            intentRevision: Long,
            intentText: String,
            imageBase64: String
        ): AnalyzeSceneRequest {
            val frameId = metrics.frameId.toLongOrNull()
                ?: error("D v1 frame_id requires a numeric C frameId")
            return AnalyzeSceneRequest(
                frameId = frameId,
                intentRevision = intentRevision,
                intent = intentText,
                imageBase64 = imageBase64,
                metrics = AnalyzeSceneMetrics(
                    subjectBrightness = metrics.subjectBrightness,
                    highlightRatio = metrics.highlightRatio,
                    darkRatio = metrics.darkRatio
                )
            )
        }
    }
}

data class AnalyzeSceneResponse(
    val frameId: Long,
    val intentRevision: Long,
    val status: SceneAnalysisStatus,
    val scene: String?,
    val subjectType: String?,
    val brightRegionType: String?,
    val coloredLight: Boolean?,
    val uncertainty: List<String>,
    val reason: String?
) {
    init {
        require(frameId >= 0L) { "frame_id must be non-negative" }
        require(intentRevision >= 0L) { "intent_revision must be non-negative" }
        uncertainty.forEach {
            require(it.isNotBlank()) { "uncertainty entries must not be blank" }
        }
        scene?.let { require(it in SCENES) { "Unsupported scene enum: $it" } }
        subjectType?.let {
            require(it in SUBJECT_TYPES) { "Unsupported subject_type enum: $it" }
        }
        brightRegionType?.let {
            require(it in BRIGHT_REGION_TYPES) {
                "Unsupported bright_region_type enum: $it"
            }
        }
        if (status == SceneAnalysisStatus.MOCK) {
            requireSemanticFieldsAreNull("mock")
        }
        if (status == SceneAnalysisStatus.UNAVAILABLE) {
            requireSemanticFieldsAreNull("unavailable")
            require(uncertainty.firstOrNull() in UNAVAILABLE_CODES) {
                "unavailable responses must start with a stable error code"
            }
        }
    }

    fun isBoundTo(request: AnalyzeSceneRequest): Boolean {
        return frameId == request.frameId &&
            intentRevision == request.intentRevision
    }

    /**
     * Converts D's protocol result into the normalized object consumed by C.
     *
     * D's uncertainty is explanatory text, not a numeric confidence. To avoid
     * inventing confidence from prose, any non-empty list maps conservatively
     * to uncertainty=1.0 and is preserved in uncertaintyNotes.
     */
    fun toSceneSemantic(
        request: AnalyzeSceneRequest,
        nowEpochMs: Long,
        semanticTtlMs: Long = DEFAULT_SEMANTIC_TTL_MS
    ): SceneSemantic {
        require(semanticTtlMs >= 0L) { "semanticTtlMs must be non-negative" }
        val bindingMatches = isBoundTo(request)
        val usable = bindingMatches && status == SceneAnalysisStatus.OK
        val mappedUncertainty = when {
            !usable -> 1.0f
            uncertainty.isEmpty() -> 0.0f
            else -> 1.0f
        }
        return SceneSemantic(
            available = usable,
            scene = if (usable) scene else null,
            subjectType = if (usable) subjectType else null,
            brightRegionType = if (usable) brightRegionType else null,
            coloredLight = if (usable) coloredLight else null,
            uncertainty = mappedUncertainty,
            reason = if (bindingMatches) {
                reason ?: uncertainty.firstOrNull()
            } else {
                "response_binding_mismatch"
            },
            sourceFrameId = frameId.toString(),
            receivedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = if (usable) {
                nowEpochMs + semanticTtlMs
            } else {
                null
            },
            intentRevision = intentRevision,
            uncertaintyNotes = uncertainty,
            analysisStatus = status.wireValue
        )
    }

    private fun requireSemanticFieldsAreNull(statusName: String) {
        require(scene == null) { "$statusName response scene must be null" }
        require(subjectType == null) {
            "$statusName response subject_type must be null"
        }
        require(brightRegionType == null) {
            "$statusName response bright_region_type must be null"
        }
        require(coloredLight == null) {
            "$statusName response colored_light must be null"
        }
    }

    companion object {
        const val DEFAULT_SEMANTIC_TTL_MS: Long = 4_000L

        val SCENES: Set<String> = setOf(
            "indoor_even_light",
            "indoor_mixed_light",
            "indoor_low_light",
            "indoor_backlit",
            "outdoor_daylight",
            "outdoor_backlit",
            "night_low_light",
            "stage_colored_light",
            "high_contrast_other",
            "other"
        )

        val SUBJECT_TYPES: Set<String> = setOf(
            "person",
            "group",
            "display",
            "document",
            "object",
            "landscape",
            "none",
            "other"
        )

        val BRIGHT_REGION_TYPES: Set<String> = setOf(
            "none",
            "sky",
            "window",
            "display",
            "lamp",
            "specular_reflection",
            "mixed",
            "other"
        )

        val UNAVAILABLE_CODES: Set<String> = setOf(
            "model_not_configured",
            "timeout",
            "rate_limited",
            "authentication_failed",
            "connection_failed",
            "invalid_model_response",
            "model_unavailable",
            "backend_busy",
            "internal_error"
        )
    }
}

/**
 * The HTTP implementation belongs to the Android/D adapter, not PolicyEngine.
 */
fun interface SceneAnalysisClient {
    fun analyzeScene(request: AnalyzeSceneRequest): AnalyzeSceneResponse
}

/**
 * Converts a V1 client result into the normalized C data source contract.
 */
class V1SceneSemanticDataSource(
    private val client: SceneAnalysisClient,
    private val semanticTtlMs: Long = AnalyzeSceneResponse.DEFAULT_SEMANTIC_TTL_MS
) : SceneSemanticDataSource {
    init {
        require(semanticTtlMs >= 0L)
    }

    override fun readSemantic(
        request: AnalyzeSceneRequest,
        nowEpochMs: Long
    ): SceneSemantic {
        return client.analyzeScene(request).toSceneSemantic(
            request = request,
            nowEpochMs = nowEpochMs,
            semanticTtlMs = semanticTtlMs
        )
    }
}
