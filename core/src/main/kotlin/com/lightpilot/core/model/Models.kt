package com.lightpilot.core.model

enum class ExposureProgram {
    AUTO,
    MANUAL,
    UNKNOWN
}

enum class RecordingState {
    IDLE,
    RECORDING,
    STARTING,
    STOPPING,
    UNKNOWN
}

enum class FrameSource {
    SDK_DECODED,
    SDK_RENDERED_PREVIEW,
    MANUAL_IMPORT,
    MOCK,
    UNKNOWN
}

enum class InputSource {
    REAL,
    MOCK
}

enum class ExecutionMode {
    REAL,
    MOCK
}

enum class ExecutionStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    UNKNOWN
}

enum class ReadbackStatus {
    MATCHED,
    MISMATCHED,
    UNAVAILABLE,
    UNKNOWN
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN
}

enum class PolicyAction {
    HOLD,
    EV_ONE_STEP_UP,
    EV_ONE_STEP_DOWN,
    SET_SHUTTER,
    SET_ISO,
    SET_WHITE_BALANCE
}

enum class SafetyReason {
    ALLOWED,
    NO_ACTION,
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
    EXPIRED_PROPOSAL,
    MISSING_COMMAND_ID
}

data class PolicyDiagnostics(
    val subjectRisk: Float?,
    val highlightRisk: Float?,
    val darkRisk: Float?,
    val upScore: Float,
    val downScore: Float,
    val scoreMargin: Float,
    val semanticUncertainty: Float?,
    val semanticUsed: Boolean
)

data class ShutterSpeed(
    val numerator: Double,
    val denominator: Double
) {
    init {
        require(numerator > 0.0) { "Shutter numerator must be positive" }
        require(denominator > 0.0) { "Shutter denominator must be positive" }
    }
}

data class Roi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val version: String = "manual"
) {
    init {
        require(left in 0f..1f)
        require(top in 0f..1f)
        require(right in 0f..1f)
        require(bottom in 0f..1f)
        require(right > left) { "ROI right must be greater than left" }
        require(bottom > top) { "ROI bottom must be greater than top" }
    }

    fun contains(x: Float, y: Float): Boolean {
        return x >= left && x < right && y >= top && y < bottom
    }

    companion object {
        fun central(version: String = "central-60"): Roi {
            return Roi(
                left = 0.2f,
                top = 0.2f,
                right = 0.8f,
                bottom = 0.8f,
                version = version
            )
        }
    }
}

data class GrayFrame(
    val frameId: String,
    val width: Int,
    val height: Int,
    val pixels: FloatArray,
    val source: FrameSource,
    val capturedAtEpochMs: Long
) {
    init {
        require(frameId.isNotBlank()) { "Frame id must not be blank" }
        require(width > 0) { "Frame width must be positive" }
        require(height > 0) { "Frame height must be positive" }
        require(pixels.size == width * height) {
            "Pixel count must equal width * height"
        }
        require(pixels.all { it in 0f..1f }) {
            "Gray pixels must be normalized to 0..1"
        }
    }

    fun pixel(x: Int, y: Int): Float {
        require(x in 0 until width)
        require(y in 0 until height)
        return pixels[y * width + x]
    }
}

data class VisionMetrics(
    val frameId: String,
    val source: FrameSource,
    val roiVersion: String,
    val subjectBrightness: Float?,
    val backgroundBrightness: Float?,
    val highlightRatio: Float?,
    val darkRatio: Float?,
    val motionScore: Float?,
    val capturedAtEpochMs: Long,
    val expiresAtEpochMs: Long?
)

data class UserIntent(
    val revision: Long,
    val subjectDetail: Float = 0f,
    val highlightDetail: Float = 0f,
    val motionClarity: Float = 0f,
    val lowNoise: Float = 0f,
    val colorNeutrality: Float = 0f,
    val atmospherePreservation: Float = 0f,
    val exposureStability: Float = 0f,
    val sourceText: String? = null,
    val createdAtEpochMs: Long = 0L
) {
    init {
        require(revision >= 0L)
        requireWeightsInRange(
            subjectDetail,
            highlightDetail,
            motionClarity,
            lowNoise,
            colorNeutrality,
            atmospherePreservation,
            exposureStability
        )
    }
}

data class SceneSemantic(
    val available: Boolean,
    val scene: String?,
    val subjectType: String?,
    val brightRegionType: String?,
    val coloredLight: Boolean?,
    val uncertainty: Float?,
    val reason: String?,
    val sourceFrameId: String?,
    val receivedAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val intentRevision: Long? = null,
    val uncertaintyNotes: List<String> = emptyList(),
    val analysisStatus: String? = null
) {
    init {
        uncertainty?.let { require(it in 0f..1f) }
        uncertaintyNotes.forEach {
            require(it.isNotBlank()) { "Uncertainty notes must not be blank" }
        }
        analysisStatus?.let {
            require(it in setOf("ok", "mock", "unavailable")) {
                "Unsupported scene analysis status"
            }
        }
    }
}

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
    val capabilityRevision: Long
)

data class CameraCapabilities(
    val supportedEv: List<Double>,
    val supportedShutterSpeed: List<ShutterSpeed>,
    val supportedIso: List<Int>,
    val supportedWhiteBalance: List<Int>,
    val supportedExposurePrograms: List<ExposureProgram>,
    val supportParam: Set<String>,
    val capabilityRevision: Long,
    val capturedAtEpochMs: Long
) {
    val sortedSupportedEv: List<Double>
        get() = supportedEv.distinct().sorted()
}

sealed interface ParameterTarget {
    data class Ev(val value: Double) : ParameterTarget
    data class Shutter(val value: ShutterSpeed) : ParameterTarget
    data class Iso(val value: Int) : ParameterTarget
    data class WhiteBalance(val value: Int) : ParameterTarget
}

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
    val createdAtEpochMs: Long,
    val diagnostics: PolicyDiagnostics? = null,
    val executionMode: ExecutionMode = ExecutionMode.REAL
)

data class SafetyDecision(
    val allowed: Boolean,
    val reason: SafetyReason,
    val message: String,
    val checkedProposalId: String?,
    val checkedAtEpochMs: Long
)

data class ExecutionResult(
    val commandId: String,
    val proposalId: String,
    val executionStatus: ExecutionStatus,
    val beforeValue: ParameterTarget?,
    val targetValue: ParameterTarget?,
    val readbackValue: ParameterTarget?,
    val readbackStatus: ReadbackStatus,
    val errorCode: String?,
    val connectionEpoch: String,
    val capabilityRevision: Long,
    val inputSource: InputSource,
    val executionMode: ExecutionMode
)

private fun requireWeightsInRange(vararg values: Float) {
    require(values.all { it in 0f..1f }) {
        "Intent weights must be normalized to 0..1"
    }
}
