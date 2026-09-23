package com.example.insta_auto_adjust.lightpilot

import java.util.UUID

/** Stage 2/3 are planning-only until A verifies the corresponding GO Ultra SDK capabilities. */
enum class AdvancedStage { MOTION_NOISE, COLOR_ATMOSPHERE }

enum class MotionPriority(val wireValue: String) {
    MOTION_CLARITY("motion_clarity"), LOW_NOISE("low_noise"),
    BRIGHTNESS_PRIORITY("brightness_priority"), MOTION_BALANCED("motion_balanced");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

enum class ColorPriority(val wireValue: String) {
    COLOR_ACCURACY("color_accuracy"), NATURAL_SKIN("natural_skin"),
    ATMOSPHERE_PRESERVATION("atmosphere_preservation"),
    COLORED_LIGHT_PRESERVATION("colored_light_preservation"),
    COLOR_STABILITY("color_stability");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

data class MotionIntent(
    val revision: Long,
    val priority: MotionPriority,
    val stabilityPreference: StabilityPreference,
    val sourceText: String? = null,
) {
    init {
        require(revision >= 0)
        require(sourceText == null || sourceText.trim().length in 1..1000)
    }
}

data class ColorIntent(
    val revision: Long,
    val priority: ColorPriority,
    val stabilityPreference: StabilityPreference,
    val sourceText: String? = null,
) {
    init {
        require(revision >= 0)
        require(sourceText == null || sourceText.trim().length in 1..1000)
    }
}

data class MotionMetrics(
    val frameId: Long,
    /** 0 means static, 1 means strong inter-frame motion. */
    val motionScore: Double?,
    val darkRatio: Double,
) {
    init {
        require(frameId >= 0)
        listOfNotNull(motionScore, darkRatio).forEach {
            require(it.isFinite() && it in 0.0..1.0)
        }
    }
}

data class ColorMetrics(
    val frameId: Long,
    /** A local estimator may provide this value; null means the estimator is unavailable. */
    val estimatedColorTemperatureKelvin: Int?,
) {
    init {
        require(frameId >= 0)
        require(estimatedColorTemperatureKelvin == null || estimatedColorTemperatureKelvin in 1_000..20_000)
    }
}

enum class ExposureProgram { AUTO, MANUAL, UNKNOWN }
enum class InputSource { REAL, MOCK }

data class AdvancedCameraCapabilities(
    val supportedShutterSeconds: List<Double>,
    val supportedIso: List<Int>,
    val supportedWhiteBalanceKelvin: List<Int>,
    val shutterReadable: Boolean,
    val shutterWritable: Boolean,
    val isoReadable: Boolean,
    val isoWritable: Boolean,
    val whiteBalanceReadable: Boolean,
    val whiteBalanceWritable: Boolean,
    val executionMode: ExecutionMode,
    val inputSource: InputSource,
) {
    init {
        require(supportedShutterSeconds.all { it.isFinite() && it > 0.0 })
        require(supportedIso.all { it > 0 })
        require(supportedWhiteBalanceKelvin.all { it in 1_000..20_000 })
        require(supportedShutterSeconds.distinct().size == supportedShutterSeconds.size)
        require(supportedIso.distinct().size == supportedIso.size)
        require(supportedWhiteBalanceKelvin.distinct().size == supportedWhiteBalanceKelvin.size)
    }
}

data class AdvancedCameraState(
    val cameraStateRevision: Long,
    val mode: String,
    val exposureProgram: ExposureProgram,
    val currentShutterSeconds: Double?,
    val currentIso: Int?,
    val currentWhiteBalanceKelvin: Int?,
    val recordingState: RecordingState,
    val isBusy: Boolean,
    val stateKnown: Boolean,
) {
    init {
        require(cameraStateRevision >= 0)
        require(currentShutterSeconds == null || currentShutterSeconds.isFinite() && currentShutterSeconds > 0.0)
        require(currentIso == null || currentIso > 0)
        require(currentWhiteBalanceKelvin == null || currentWhiteBalanceKelvin in 1_000..20_000)
    }
}

enum class AdvancedAction { HOLD, SET_SHUTTER, SET_ISO, SET_WHITE_BALANCE }

enum class AdvancedReasonCode {
    MOTION_DETECTED, NOISE_REDUCTION, SCENE_TOO_DARK, MOTION_BALANCED,
    COLOR_ACCURACY, NATURAL_SKIN, ATMOSPHERE_PRESERVED, COLORED_LIGHT_PRESERVED,
    PARAMETER_ALREADY_SUITABLE, PARAMETER_LIMIT_REACHED, METRICS_UNAVAILABLE,
    MANUAL_MODE_REQUIRED, CAMERA_CAPABILITY_UNVERIFIED, CAMERA_STATE_UNAVAILABLE,
    SUBJECT_NOT_FOUND, SEMANTIC_UNAVAILABLE, WHITE_BALANCE_LOCK_UNVERIFIED,
    TEMPORAL_CONFIRMATION_PENDING, COOLDOWN_ACTIVE,
}

data class AdvancedPolicyProposal(
    val proposalId: String = UUID.randomUUID().toString(),
    val stage: AdvancedStage,
    val intentRevision: Long,
    val cameraStateRevision: Long,
    val semanticFrameId: Long,
    val metricsFrameId: Long,
    val semanticStatus: SemanticStatus,
    val executionMode: ExecutionMode,
    val inputSource: InputSource,
    val action: AdvancedAction,
    val targetShutterSeconds: Double? = null,
    val targetIso: Int? = null,
    val targetWhiteBalanceKelvin: Int? = null,
    val reasonCode: AdvancedReasonCode,
    val reason: String,
    val risk: RiskLevel,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    val targetSignature: String
        get() = "$action/$targetShutterSeconds/$targetIso/$targetWhiteBalanceKelvin"
}
