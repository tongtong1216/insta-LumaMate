package com.example.insta_auto_adjust.lightpilot

import kotlin.math.abs

class SceneSemanticCache(
    private val ttlMs: Long = 60_000,
    private val metricChangeThreshold: Double = 0.20,
) {
    private data class Entry(
        val semantic: SceneSemantic,
        val cameraStateRevision: Long,
        val metrics: VisionMetrics,
        val storedAtMs: Long,
    )

    private var latestRequestedFrameId: Long? = null
    private var requestInFlight = false
    private var entry: Entry? = null

    @Synchronized
    fun beginRequest(frameId: Long): Boolean {
        if (requestInFlight) return false
        requestInFlight = true
        latestRequestedFrameId = frameId
        return true
    }

    @Synchronized
    fun accept(
        semantic: SceneSemantic,
        currentIntentRevision: Long,
        cameraStateRevision: Long,
        metrics: VisionMetrics,
        nowMs: Long,
    ): Boolean {
        requestInFlight = false
        val accepted = semantic.status == SemanticStatus.OK && semantic.isPolicyUsable &&
            semantic.intentRevision == currentIntentRevision &&
            semantic.frameId == latestRequestedFrameId
        entry = if (accepted) Entry(semantic, cameraStateRevision, metrics, nowMs) else null
        return accepted
    }

    @Synchronized
    fun finishFailure() {
        requestInFlight = false
        entry = null
    }

    @Synchronized
    fun get(
        currentIntentRevision: Long,
        cameraStateRevision: Long,
        metrics: VisionMetrics,
        nowMs: Long,
    ): SceneSemantic? {
        val current = entry ?: return null
        val changed = listOf(
            abs(metrics.backgroundBrightness - current.metrics.backgroundBrightness),
            abs(metrics.highlightClippingRatio - current.metrics.highlightClippingRatio),
            abs(metrics.darkRatio - current.metrics.darkRatio),
        ).any { it >= metricChangeThreshold } || when {
            metrics.subjectBrightness == null && current.metrics.subjectBrightness == null -> false
            metrics.subjectBrightness == null || current.metrics.subjectBrightness == null -> true
            else -> abs(metrics.subjectBrightness - current.metrics.subjectBrightness) >= metricChangeThreshold
        }
        val invalid = nowMs - current.storedAtMs > ttlMs ||
            current.semantic.intentRevision != currentIntentRevision ||
            current.cameraStateRevision != cameraStateRevision || changed
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
}
