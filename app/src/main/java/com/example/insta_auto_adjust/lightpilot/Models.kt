package com.example.insta_auto_adjust.lightpilot

import java.util.UUID

enum class ExposurePriority(val wireValue: String) {
    SUBJECT_DETAIL("subject_detail"),
    HIGHLIGHT_DETAIL("highlight_detail"),
    BALANCED("balanced");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

enum class StabilityPreference(val wireValue: String) {
    NORMAL("normal"), HIGH("high");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

data class UserIntent(
    val revision: Long,
    val exposurePriority: ExposurePriority,
    val stabilityPreference: StabilityPreference,
    val sourceText: String? = null,
) {
    init {
        require(revision >= 0)
        require(sourceText == null || sourceText.trim().length in 1..1000)
    }
}

data class VisionMetrics(
    val frameId: Long,
    val subjectBrightness: Double?,
    val backgroundBrightness: Double,
    val highlightClippingRatio: Double,
    val darkRatio: Double,
) {
    init {
        require(frameId >= 0)
        listOfNotNull(subjectBrightness, backgroundBrightness, highlightClippingRatio, darkRatio)
            .forEach { require(it in 0.0..1.0 && it.isFinite()) }
    }
}

enum class SemanticStatus(val wireValue: String) {
    OK("ok"), MOCK("mock"), UNAVAILABLE("unavailable");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

enum class SceneLabel(val wireValue: String) {
    INDOOR_EVEN_LIGHT("indoor_even_light"), INDOOR_MIXED_LIGHT("indoor_mixed_light"),
    INDOOR_LOW_LIGHT("indoor_low_light"), INDOOR_BACKLIT("indoor_backlit"),
    OUTDOOR_DAYLIGHT("outdoor_daylight"), OUTDOOR_BACKLIT("outdoor_backlit"),
    NIGHT_LOW_LIGHT("night_low_light"), STAGE_COLORED_LIGHT("stage_colored_light"),
    HIGH_CONTRAST_OTHER("high_contrast_other"), OTHER("other");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

enum class SubjectType(val wireValue: String) {
    PERSON("person"), GROUP("group"), DISPLAY("display"), DOCUMENT("document"),
    OBJECT("object"), LANDSCAPE("landscape"), NONE("none"), OTHER("other");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

enum class BrightRegionType(val wireValue: String) {
    NONE("none"), SKY("sky"), WINDOW("window"), DISPLAY("display"), LAMP("lamp"),
    SPECULAR_REFLECTION("specular_reflection"), MIXED("mixed"), OTHER("other");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

data class SceneSemantic(
    val frameId: Long,
    val intentRevision: Long,
    val status: SemanticStatus,
    val scene: SceneLabel?,
    val subjectType: SubjectType?,
    val brightRegionType: BrightRegionType?,
    val coloredLight: Boolean?,
    val uncertainty: List<String>,
    val reason: String?,
) {
    init {
        require(frameId >= 0 && intentRevision >= 0)
    }

    val isPolicyUsable: Boolean
        get() = status == SemanticStatus.OK && scene != null && subjectType != null &&
            brightRegionType != null && coloredLight != null && uncertainty.isEmpty()
}

enum class RecordingState { IDLE, RECORDING, UNKNOWN }
enum class ExecutionMode { REAL, MOCK }

data class CameraCapabilities(
    val supportedEv: List<Double>,
    val evReadable: Boolean,
    val evWritable: Boolean,
    val canWriteEvWhileRecording: Boolean,
    val executionMode: ExecutionMode,
) {
    init {
        require(supportedEv.all { it.isFinite() })
        require(supportedEv.distinct().size == supportedEv.size)
    }
}

data class CameraState(
    val cameraStateRevision: Long,
    val mode: String,
    val recordingState: RecordingState,
    val isBusy: Boolean,
    val currentEv: Double,
    val stateKnown: Boolean,
) {
    init {
        require(cameraStateRevision >= 0 && currentEv.isFinite())
    }
}

enum class ExposureAction { HOLD, EV_ONE_STEP_UP, EV_ONE_STEP_DOWN }

enum class ReasonCode {
    SUBJECT_TOO_DARK, SUBJECT_TOO_BRIGHT, HIGHLIGHTS_CLIPPING, BALANCED_EXPOSURE,
    EXPOSURE_CONFLICT, EV_LIMIT_REACHED, SUBJECT_NOT_FOUND, SEMANTIC_UNAVAILABLE,
    CAMERA_CAPABILITY_INVALID, TEMPORAL_CONFIRMATION_PENDING, COOLDOWN_ACTIVE,
}

enum class RiskLevel { LOW, MEDIUM, HIGH }

data class PolicyProposal(
    val proposalId: String = UUID.randomUUID().toString(),
    val intentRevision: Long,
    val cameraStateRevision: Long,
    val semanticFrameId: Long,
    val metricsFrameId: Long,
    val semanticStatus: SemanticStatus,
    val executionMode: ExecutionMode,
    val action: ExposureAction,
    val targetEv: Double?,
    val reasonCode: ReasonCode,
    val reason: String,
    val risk: RiskLevel,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

enum class SafetyRejection {
    HOLD_ACTION, USER_NOT_CONFIRMED, STALE_INTENT, STALE_CAMERA_STATE, EXPIRED,
    CAMERA_STATE_UNKNOWN, CAMERA_BUSY, RECORDING_WRITE_UNSUPPORTED, EV_NOT_READABLE,
    EV_NOT_WRITABLE, ILLEGAL_TARGET, DUPLICATE_COMMAND, SEMANTIC_NOT_OK, MOCK_EXECUTION,
}

data class SafetyDecision(val allowed: Boolean, val rejection: SafetyRejection? = null)

data class ExecutionRecord(
    val commandId: String,
    val proposalId: String,
    val success: Boolean,
    val beforeEv: Double?,
    val targetEv: Double?,
    val readbackEv: Double?,
    val message: String,
)
