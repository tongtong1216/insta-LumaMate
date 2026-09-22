package com.lightpilot.core.contract

import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExecutionResult
import com.lightpilot.core.model.GrayFrame
import com.lightpilot.core.model.PolicyProposal
import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.contract.v1.AnalyzeSceneRequest

/**
 * Boundary for member A's SDK adapter.
 *
 * This interface intentionally exposes only the shared DTOs. It must not
 * contain Insta360 SDK types so the policy module remains Android-independent.
 */
interface CameraDataSource {
    fun readState(): CameraState

    fun readCapabilities(): CameraCapabilities
}

/**
 * Boundary for the preview adapter. A may decode an SDK frame into the
 * normalized grayscale frame consumed by FrameAnalyzer.
 */
interface PreviewFrameDataSource {
    fun readLatestFrame(): GrayFrame?
}

/**
 * Boundary for member A's confirmed command execution.
 *
 * The implementation must re-check camera state and capabilities before
 * calling the SDK and must return the post-write readback result.
 */
interface CameraCommandExecutor {
    fun execute(
        proposal: PolicyProposal,
        commandId: String
    ): ExecutionResult
}

/**
 * Boundary for member D's backend adapter.
 *
 * The implementation may call HTTP, a local mock, or another transport. The
 * policy module only consumes the normalized semantic result.
 */
interface SceneSemanticDataSource {
    fun readSemantic(
        request: AnalyzeSceneRequest,
        nowEpochMs: Long
    ): SceneSemantic
}
