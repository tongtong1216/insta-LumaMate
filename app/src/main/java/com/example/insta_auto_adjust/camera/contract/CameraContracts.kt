package com.example.insta_auto_adjust.camera.contract

/**
 * Shared camera contract consumed by the policy and UI modules.
 *
 * Values are snapshots read from the camera adapter. Unsupported or unreadable values are
 * represented explicitly instead of using a sentinel such as 0.
 */
data class CameraState(
    val connectionEpoch: String,
    val mode: String?,
    val exposureProgram: ExposureProgram,
    val currentEv: Double?,
    val currentIso: Int?,
    val currentShutterSpeed: ShutterSpeed?,
    val currentWhiteBalance: Int?,
    val isWorking: Boolean?,
    val isPreRecording: Boolean?,
    val isBusy: Boolean,
    val recordingState: RecordingState,
    val frameSource: FrameSource,
    val capabilityRevision: Long,
)

data class ShutterSpeed(
    val numerator: Double,
    val denominator: Double,
)

data class CameraCapabilities(
    val supportedEv: List<Double>,
    val supportedShutterSpeed: List<ShutterSpeed>,
    val supportedIso: List<Int>,
    val supportedWhiteBalance: List<Int>,
    val supportedExposurePrograms: List<ExposureProgram>,
    /** Raw [com.arashivision.sdk.camera.api.param.CameraParam.getName] values. */
    val supportParam: Set<String>,
    val capabilityRevision: Long,
    val capturedAtEpochMs: Long,
)

data class CameraSnapshot(
    val state: CameraState,
    val capabilities: CameraCapabilities,
)

enum class ExposureProgram {
    AUTO,
    MANUAL,
    UNKNOWN,
}

enum class RecordingState {
    IDLE,
    RECORDING,
    STARTING,
    STOPPING,
    UNKNOWN,
}

enum class FrameSource {
    SDK_DECODED,
    SDK_RENDERED_PREVIEW,
    MANUAL_IMPORT,
    MOCK,
    UNKNOWN,
}

/** Reads a fresh camera snapshot. Implementations must never substitute UI-cached values. */
fun interface CameraSnapshotReader {
    suspend fun readSnapshot(): CameraSnapshot
}

/**
 * The complete SDK-free boundary supplied by the camera module to C.
 *
 * C receives this interface from app wiring, reads a fresh snapshot before producing a proposal,
 * and submits only the proposal that B has safety-checked and the user has confirmed.  C must not
 * depend on an Insta360 implementation class or call a raw camera setter.
 */
interface CameraAdapter : CameraSnapshotReader, PolicyProposalExecutor

/**
 * The only camera mutations enabled in the first delivery phase.
 *
 * The policy layer supplies an EV target. The adapter accepts it only when it exactly matches the
 * current camera-supported list; callers can never supply a raw SDK parameter name.
 */
interface CameraCommandExecutor {
    suspend fun setEv(commandId: String, targetEv: Double): ExecutionResult

    suspend fun setIso(commandId: String, targetIso: Int): ExecutionResult

    suspend fun setShutterSpeed(commandId: String, targetShutterSpeed: ShutterSpeed): ExecutionResult

    suspend fun setWhiteBalance(commandId: String, targetWhiteBalance: Int): ExecutionResult

    suspend fun setEvOneStepUp(commandId: String): ExecutionResult

    suspend fun setEvOneStepDown(commandId: String): ExecutionResult
}

/** The C-to-A policy contract. This remains SDK-free and can live in a shared module later. */
data class PolicyProposal(
    val proposalId: String,
    val intentRevision: Long,
    val frameId: String,
    val action: PolicyAction,
    val parameter: ParameterTarget?,
    val reason: String,
    val risk: RiskLevel,
    val cost: Float,
    val validUntilEpochMs: Long,
    val connectionEpoch: String,
    val capabilityRevision: Long,
    val inputSource: InputSource,
)

enum class PolicyAction {
    HOLD,
    EV_ONE_STEP_UP,
    EV_ONE_STEP_DOWN,
    SET_SHUTTER,
    SET_ISO,
    SET_WHITE_BALANCE,
}

enum class RiskLevel { LOW, MEDIUM, HIGH, UNKNOWN }

enum class InputSource { REAL_CAMERA, MOCK, UNKNOWN }

data class SafetyDecision(
    val allowed: Boolean,
    val reasonCode: SafetyReason,
    val checkedProposalId: String?,
    val checkedAtEpochMs: Long,
)

enum class SafetyReason {
    ALLOWED,
    STALE_FRAME,
    STALE_INTENT,
    STALE_CAPABILITY,
    CAMERA_BUSY,
    RECORDING,
    UNKNOWN_CAMERA_STATE,
    ILLEGAL_TARGET,
    DUPLICATE_COMMAND,
    MODEL_UNAVAILABLE,
    USER_LOCKED,
    CONNECTION_CHANGED,
    UNSUPPORTED_PARAMETER,
}

/** A's formal boundary: execute only a C proposal already allowed and confirmed by the user. */
interface PolicyProposalExecutor {
    suspend fun executeConfirmed(
        proposal: PolicyProposal,
        safetyDecision: SafetyDecision,
        userConfirmed: Boolean,
    ): ExecutionResult
}

enum class CameraAction {
    HOLD,
    SET_EV,
    SET_ISO,
    SET_SHUTTER_SPEED,
    SET_WHITE_BALANCE,
    EV_ONE_STEP_UP,
    EV_ONE_STEP_DOWN,
}

/**
 * Evidence returned for every attempted write. A setter acknowledgement is not treated as a
 * confirmed camera change; consumers must use [readback] to inspect the latest camera state.
 */
enum class CameraParameter {
    EV,
    ISO,
    SHUTTER_SPEED,
    WHITE_BALANCE,
}

/** Typed target from C's policy layer. The adapter maps it to the SDK's actual raw value. */
sealed interface ParameterTarget {
    val parameter: CameraParameter
}

data class EvTarget(val value: Double) : ParameterTarget {
    override val parameter = CameraParameter.EV
}

data class IsoTarget(val value: Int) : ParameterTarget {
    override val parameter = CameraParameter.ISO
}

data class ShutterSpeedTarget(val value: ShutterSpeed) : ParameterTarget {
    override val parameter = CameraParameter.SHUTTER_SPEED
}

data class WhiteBalanceTarget(val value: Int) : ParameterTarget {
    override val parameter = CameraParameter.WHITE_BALANCE
}

data class ExecutionResult(
    val commandId: String,
    val action: CameraAction,
    val before: CameraSnapshot?,
    val target: ParameterTarget?,
    val sdkAcknowledged: Boolean?,
    val readback: CameraSnapshot?,
    val status: ExecutionStatus,
    val reason: ExecutionReason?,
    /** Per-step evidence for a multi-step adjustment, in the order sent to the camera. */
    val steps: List<ParameterAdjustmentStep>,
    val completedAtEpochMs: Long,
)

data class ParameterAdjustmentStep(
    val target: ParameterTarget,
    val sdkAcknowledged: Boolean?,
    val readback: ParameterTarget?,
    val status: ExecutionStatus,
    val reason: ExecutionReason?,
)

enum class ExecutionStatus {
    SUCCEEDED,
    SKIPPED,
    REJECTED,
    FAILED,
    /** The camera may have received the write, but its final outcome cannot be established. */
    UNKNOWN,
}

enum class ExecutionReason {
    INVALID_COMMAND,
    DUPLICATE_COMMAND,
    CAMERA_DISCONNECTED,
    CAMERA_BUSY,
    RECORDING_ACTIVE,
    MISSING_EV_CAPABILITY,
    CURRENT_EV_UNREADABLE,
    CURRENT_PARAMETER_UNREADABLE,
    NO_ADJACENT_EV,
    ILLEGAL_EV_TARGET,
    ILLEGAL_PARAMETER_TARGET,
    MANUAL_EXPOSURE_REQUIRED,
    SETTER_TIMEOUT,
    SDK_FAILURE,
    READBACK_FAILURE,
    READBACK_MISMATCH,
    CONNECTION_CHANGED,
    CAPABILITY_CHANGED,
    USER_NOT_CONFIRMED,
    SAFETY_REJECTED,
    STALE_PROPOSAL,
    POLICY_HOLD,
    TARGET_ACTION_MISMATCH,
}
