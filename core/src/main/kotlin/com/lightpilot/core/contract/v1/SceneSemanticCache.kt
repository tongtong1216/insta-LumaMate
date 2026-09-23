package com.lightpilot.core.contract.v1

import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.model.VisionMetrics
import kotlin.math.abs

data class SemanticRequestToken(
    val frameId: Long,
    val intentRevision: Long
)

/**
 * C-side rc3 semantic cache. It enforces one in-flight request, response
 * binding, the 60-second upper bound, context invalidation, and metric drift.
 */
class SceneSemanticCache(
    private val maxAgeMs: Long = AnalyzeSceneResponse.DEFAULT_SEMANTIC_TTL_MS,
    private val metricInvalidationDelta: Float = 0.20f
) {
    private data class Entry(
        val semantic: SceneSemantic,
        val baseline: VisionMetrics,
        val cameraStateRevision: Long,
        val mode: String?
    )

    private var inFlight: SemanticRequestToken? = null
    private var latestRequested: SemanticRequestToken? = null
    private var entry: Entry? = null

    init {
        require(maxAgeMs in 0L..AnalyzeSceneResponse.MAX_SEMANTIC_TTL_MS)
        require(metricInvalidationDelta in 0f..1f)
    }

    @Synchronized
    fun beginRequest(frameId: Long, intentRevision: Long): SemanticRequestToken? {
        if (inFlight != null) return null
        return SemanticRequestToken(frameId, intentRevision).also {
            inFlight = it
            latestRequested = it
        }
    }

    @Synchronized
    fun complete(
        token: SemanticRequestToken,
        semantic: SceneSemantic,
        baseline: VisionMetrics,
        currentIntentRevision: Long,
        cameraStateRevision: Long,
        mode: String?,
        nowEpochMs: Long
    ): Boolean {
        if (token != inFlight) return false
        inFlight = null
        val latest = latestRequested
        val accepted = semantic.available &&
            semantic.analysisStatus == SceneAnalysisStatus.OK.wireValue &&
            token == latest &&
            token.intentRevision == currentIntentRevision &&
            semantic.intentRevision == currentIntentRevision &&
            semantic.sourceFrameId == token.frameId.toString() &&
            baseline.frameId == token.frameId.toString() &&
            semantic.uncertaintyDetails.none {
                it.severity == "blocking" || "all" in it.affects
            } &&
            !isExpired(semantic, nowEpochMs)
        if (!accepted) {
            entry = null
            return false
        }
        entry = Entry(semantic, baseline, cameraStateRevision, mode)
        return true
    }

    @Synchronized
    fun fail(token: SemanticRequestToken) {
        if (token == inFlight) inFlight = null
        entry = null
    }

    @Synchronized
    fun current(
        intentRevision: Long,
        cameraStateRevision: Long,
        mode: String?,
        metrics: VisionMetrics,
        nowEpochMs: Long
    ): SceneSemantic? {
        val current = entry ?: return null
        val invalid = current.semantic.intentRevision != intentRevision ||
            current.cameraStateRevision != cameraStateRevision ||
            current.mode != mode ||
            isExpired(current.semantic, nowEpochMs) ||
            metricsChanged(current.baseline, metrics)
        if (invalid) {
            entry = null
            return null
        }
        return current.semantic
    }

    @Synchronized
    fun invalidate() {
        entry = null
    }

    @Synchronized
    fun hasInFlightRequest(): Boolean = inFlight != null

    private fun isExpired(semantic: SceneSemantic, nowEpochMs: Long): Boolean {
        if (nowEpochMs < semantic.receivedAtEpochMs) return true
        if (nowEpochMs - semantic.receivedAtEpochMs > maxAgeMs) return true
        return semantic.expiresAtEpochMs?.let { nowEpochMs > it } ?: true
    }

    private fun metricsChanged(first: VisionMetrics, second: VisionMetrics): Boolean {
        return changed(first.subjectBrightness, second.subjectBrightness) ||
            changed(first.backgroundBrightness, second.backgroundBrightness) ||
            changed(first.highlightRatio, second.highlightRatio) ||
            changed(first.darkRatio, second.darkRatio)
    }

    private fun changed(first: Float?, second: Float?): Boolean {
        if (first == null && second == null) return false
        if (first == null || second == null) return true
        return abs(first - second) >= metricInvalidationDelta
    }
}
