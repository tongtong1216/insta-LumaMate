package com.lightpilot.core.contract.v1

import com.lightpilot.core.contract.SceneSemanticDataSource
import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.model.SemanticUncertaintyDetail
import com.lightpilot.core.model.VisionMetrics

/** Kotlin representation of D's 1.0.0-rc3 /api/v1/analyze-scene contract. */
enum class SceneAnalysisStatus(val wireValue: String) {
    OK("ok"), MOCK("mock"), UNAVAILABLE("unavailable");

    companion object {
        fun fromWireValue(value: String): SceneAnalysisStatus =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported scene analysis status: $value")
    }
}

enum class ExposurePriority(val wireValue: String) {
    SUBJECT_DETAIL("subject_detail"),
    HIGHLIGHT_DETAIL("highlight_detail"),
    BALANCED("balanced")
}

enum class StabilityPreference(val wireValue: String) {
    NORMAL("normal"), HIGH("high")
}

data class AnalyzeSceneIntent(
    val exposurePriority: ExposurePriority,
    val stabilityPreference: StabilityPreference,
    val sourceText: String
) {
    init {
        require(sourceText.trim().length in 1..1_000) {
            "source_text must contain 1..1000 non-whitespace characters"
        }
    }
}

data class AnalyzeSceneMetrics(
    val subjectBrightness: Float?,
    val backgroundBrightness: Float?,
    val highlightClippingRatio: Float?,
    val darkRatio: Float?
) {
    init {
        listOf(subjectBrightness, backgroundBrightness, highlightClippingRatio, darkRatio)
            .forEach { value -> value?.let { require(it in 0f..1f) } }
    }
}

data class AnalyzeSceneRequest(
    val frameId: Long,
    val intentRevision: Long,
    val intent: AnalyzeSceneIntent,
    val imageBase64: String,
    val metrics: AnalyzeSceneMetrics?
) {
    init {
        require(frameId >= 0L) { "frame_id must be non-negative" }
        require(intentRevision >= 0L) { "intent_revision must be non-negative" }
        require(imageBase64.isNotBlank()) { "image_base64 must not be blank" }
    }

    companion object {
        fun fromMetrics(
            metrics: VisionMetrics,
            intentRevision: Long,
            intent: AnalyzeSceneIntent,
            imageBase64: String
        ): AnalyzeSceneRequest {
            val frameId = metrics.frameId.toLongOrNull()
                ?: error("D rc3 frame_id requires a numeric C frameId")
            return AnalyzeSceneRequest(
                frameId = frameId,
                intentRevision = intentRevision,
                intent = intent,
                imageBase64 = imageBase64,
                metrics = AnalyzeSceneMetrics(
                    subjectBrightness = metrics.subjectBrightness,
                    backgroundBrightness = metrics.backgroundBrightness,
                    highlightClippingRatio = metrics.highlightRatio,
                    darkRatio = metrics.darkRatio
                )
            )
        }
    }
}

data class AnalyzeSceneUncertaintyDetail(
    val code: String,
    val severity: String,
    val affects: Set<String>,
    val message: String?
) {
    init {
        require(code.isNotBlank())
        require(severity in SEVERITIES) { "Unsupported uncertainty severity: $severity" }
        require(affects.isNotEmpty())
        require(affects.none { it.isBlank() })
    }

    val isBlocking: Boolean get() = severity == "blocking"
    val affectsAll: Boolean get() = "all" in affects

    companion object {
        val SEVERITIES = setOf("warning", "blocking")
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
    val uncertaintyDetails: List<AnalyzeSceneUncertaintyDetail> = emptyList(),
    val reason: String?
) {
    init {
        require(frameId >= 0L)
        require(intentRevision >= 0L)
        uncertainty.forEach { require(it.isNotBlank()) }
        scene?.let { require(it in SCENES) { "Unsupported scene enum: $it" } }
        subjectType?.let { require(it in SUBJECT_TYPES) { "Unsupported subject_type enum: $it" } }
        brightRegionType?.let {
            require(it in BRIGHT_REGION_TYPES) { "Unsupported bright_region_type enum: $it" }
        }
        if (status == SceneAnalysisStatus.MOCK) requireSemanticFieldsAreNull("mock")
        if (status == SceneAnalysisStatus.UNAVAILABLE) {
            requireSemanticFieldsAreNull("unavailable")
            require(uncertainty.firstOrNull() in UNAVAILABLE_CODES) {
                "unavailable responses must start with a stable error code"
            }
        }
    }

    fun isBoundTo(request: AnalyzeSceneRequest): Boolean =
        frameId == request.frameId && intentRevision == request.intentRevision

    fun toSceneSemantic(
        request: AnalyzeSceneRequest,
        nowEpochMs: Long,
        semanticTtlMs: Long = DEFAULT_SEMANTIC_TTL_MS
    ): SceneSemantic {
        require(semanticTtlMs in 0L..MAX_SEMANTIC_TTL_MS)
        val bindingMatches = isBoundTo(request)
        val hasCommonBlocker = uncertaintyDetails.any { it.isBlocking || it.affectsAll }
        val legacyUnstructuredUncertainty = uncertainty.isNotEmpty() && uncertaintyDetails.isEmpty()
        val usable = bindingMatches && status == SceneAnalysisStatus.OK &&
            !hasCommonBlocker && !legacyUnstructuredUncertainty
        return SceneSemantic(
            available = usable,
            scene = if (usable) scene else null,
            subjectType = if (usable) subjectType else null,
            brightRegionType = if (usable) brightRegionType else null,
            coloredLight = if (usable) coloredLight else null,
            uncertainty = when {
                !usable -> 1.0f
                uncertainty.isEmpty() -> 0.0f
                else -> null
            },
            reason = if (bindingMatches) reason ?: uncertainty.firstOrNull()
            else "response_binding_mismatch",
            sourceFrameId = frameId.toString(),
            receivedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = if (usable) nowEpochMs + semanticTtlMs else null,
            intentRevision = intentRevision,
            uncertaintyNotes = uncertainty,
            uncertaintyDetails = uncertaintyDetails.map {
                SemanticUncertaintyDetail(it.code, it.severity, it.affects, it.message)
            },
            analysisStatus = status.wireValue
        )
    }

    private fun requireSemanticFieldsAreNull(statusName: String) {
        require(scene == null) { "$statusName response scene must be null" }
        require(subjectType == null) { "$statusName response subject_type must be null" }
        require(brightRegionType == null) { "$statusName response bright_region_type must be null" }
        require(coloredLight == null) { "$statusName response colored_light must be null" }
    }

    companion object {
        const val DEFAULT_SEMANTIC_TTL_MS = 60_000L
        const val MAX_SEMANTIC_TTL_MS = 60_000L
        val SCENES = setOf(
            "indoor_even_light", "indoor_mixed_light", "indoor_low_light", "indoor_backlit",
            "outdoor_daylight", "outdoor_backlit", "night_low_light", "stage_colored_light",
            "high_contrast_other", "other"
        )
        val SUBJECT_TYPES = setOf(
            "person", "group", "display", "document", "object", "landscape", "none", "other"
        )
        val BRIGHT_REGION_TYPES = setOf(
            "none", "sky", "window", "display", "lamp", "specular_reflection", "mixed", "other"
        )
        val UNAVAILABLE_CODES = setOf(
            "model_not_configured", "timeout", "rate_limited", "authentication_failed",
            "connection_failed", "invalid_model_response", "model_unavailable", "backend_busy",
            "internal_error"
        )
    }
}

fun interface SceneAnalysisClient {
    fun analyzeScene(request: AnalyzeSceneRequest): AnalyzeSceneResponse
}

class V1SceneSemanticDataSource(
    private val client: SceneAnalysisClient,
    private val semanticTtlMs: Long = AnalyzeSceneResponse.DEFAULT_SEMANTIC_TTL_MS
) : SceneSemanticDataSource {
    init {
        require(semanticTtlMs in 0L..AnalyzeSceneResponse.MAX_SEMANTIC_TTL_MS)
    }

    override fun readSemantic(request: AnalyzeSceneRequest, nowEpochMs: Long): SceneSemantic {
        val response = client.analyzeScene(request)
        val receivedAt = maxOf(nowEpochMs, System.currentTimeMillis())
        return response.toSceneSemantic(request, receivedAt, semanticTtlMs)
    }
}
