package com.example.insta_auto_adjust.lightpilot

import android.graphics.Bitmap
import java.util.UUID

/**
 * P0 orchestration boundary. Network methods are blocking and must run off the Android main thread.
 */
class LightPilotCoordinator(
    private val backend: LightPilotBackendClient,
    private val camera: CameraAdapter,
    private val semanticCache: SceneSemanticCache = SceneSemanticCache(),
    private val policyEngine: PolicyEngine = PolicyEngine(),
    private val temporalController: TemporalController = TemporalController(),
    private val cameraExecutor: CameraExecutor = CameraExecutor(camera, SafetyGuard()),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun refreshSemantic(bitmap: Bitmap, intent: UserIntent, metrics: VisionMetrics): Boolean {
        if (!semanticCache.beginRequest(metrics.frameId)) return false
        return try {
            val semantic = backend.analyze(bitmap, intent, metrics)
            semanticCache.accept(
                semantic = semantic,
                currentIntentRevision = intent.revision,
                cameraStateRevision = camera.readState().cameraStateRevision,
                metrics = metrics,
                nowMs = clockMs(),
            )
        } catch (_: Exception) {
            semanticCache.finishFailure()
            false
        }
    }

    fun evaluateLocalFrame(intent: UserIntent, metrics: VisionMetrics): TemporalDecision {
        val state = camera.readState()
        val capabilities = camera.readCapabilities()
        val now = clockMs()
        val semantic = semanticCache.get(
            currentIntentRevision = intent.revision,
            cameraStateRevision = state.cameraStateRevision,
            metrics = metrics,
            nowMs = now,
        )
        val proposal = policyEngine.propose(intent, metrics, semantic, state, capabilities, now)
        return temporalController.observe(proposal, intent.stabilityPreference, now)
    }

    fun executeConfirmed(proposal: PolicyProposal, intent: UserIntent): ExecutionRecord {
        val record = cameraExecutor.execute(
            proposal = proposal,
            confirmedProposalId = proposal.proposalId,
            commandId = UUID.randomUUID().toString(),
            currentIntentRevision = intent.revision,
            nowMs = clockMs(),
        )
        if (record.success) temporalController.onExecuted(intent.stabilityPreference, clockMs())
        return record
    }

    fun invalidate() {
        semanticCache.invalidate()
        temporalController.reset()
    }
}
