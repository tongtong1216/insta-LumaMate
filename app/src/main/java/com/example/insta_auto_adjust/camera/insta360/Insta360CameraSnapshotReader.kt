package com.example.insta_auto_adjust.camera.insta360

import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.param.CameraParam
import com.example.insta_auto_adjust.camera.contract.CameraCapabilities
import com.example.insta_auto_adjust.camera.contract.CameraSnapshot
import com.example.insta_auto_adjust.camera.contract.CameraSnapshotReader
import com.example.insta_auto_adjust.camera.contract.CameraState
import com.example.insta_auto_adjust.camera.contract.ExposureProgram
import com.example.insta_auto_adjust.camera.contract.FrameSource
import com.example.insta_auto_adjust.camera.contract.RecordingState
import com.example.insta_auto_adjust.camera.contract.ShutterSpeed
import java.util.Locale

/**
 * Reads the current device state directly from Insta360 SDK parameters.
 *
 * The caller supplies the currently connected [CameraDevice]; this prevents the adapter from
 * treating a Compose/UI value as the camera's source of truth. A disconnected or unreadable
 * device yields an explicitly unsafe snapshot that C's SafetyGuard must reject.
 */
class Insta360CameraSnapshotReader(
    private val deviceProvider: () -> CameraDevice?,
    private val sessionContext: CameraSessionContext,
    private val captureRuntimeProvider: () -> CaptureRuntimeState = { CaptureRuntimeState() },
    private val clock: () -> Long = System::currentTimeMillis,
) : CameraSnapshotReader {
    private var lastCapabilityFingerprint: String? = null
    private var capabilityRevision = 0L

    override suspend fun readSnapshot(): CameraSnapshot {
        val now = clock()
        val device = deviceProvider()
        if (device == null || !device.isConnected()) {
            return unavailableSnapshot(now)
        }

        val capture = device.capture
        // CameraParam instances are cached by the SDK.  A plain getValue() can therefore keep
        // returning the value last read by this app after the user changes a setting on the
        // camera or in the official Demo.  This is the same refresh sequence that previously
        // made EV update live: synchronize the camera's complete parameter cache first, then
        // read the individual values below.
        //
        // The former stack overflow was caused by our String-to-number mapper recursively
        // calling itself, not by syncAllParams().  Keep this call outside capture callbacks;
        // this reader is only invoked by the serialized manual/polling read path.
        capture.syncAllParams()
        val parameters = mutableListOf<RawCameraParameter>()
        for (parameter in capture.getSupportParam()) {
            parameters += parameter.readRaw()
        }
        val supportParam = parameters.mapTo(linkedSetOf()) { it.name }
        val fingerprint = parameters.joinToString("|") { parameter ->
            "${parameter.name}:${parameter.supported.joinToString(",")}" 
        }
        if (fingerprint != lastCapabilityFingerprint) {
            lastCapabilityFingerprint = fingerprint
            capabilityRevision += 1
        }

        val exposureProgram = parameters.firstValue(ParameterKind.EXPOSURE_PROGRAM).toExposureProgram()
        val working = runCatching { capture.isWorking() }.getOrNull()
        val runtime = captureRuntimeProvider()
        val state = CameraState(
            connectionEpoch = sessionContext.connectionEpoch,
            mode = runCatching { capture.functionMode.getValue().getOrNull()?.toString() }.getOrNull(),
            exposureProgram = exposureProgram,
            currentEv = parameters.firstValue(ParameterKind.EV).toDoubleOrNull(),
            currentIso = parameters.firstValue(ParameterKind.ISO).toIntOrNull(),
            currentShutterSpeed = parameters.firstValue(ParameterKind.SHUTTER).toShutterSpeedOrNull(),
            currentWhiteBalance = parameters.firstValue(ParameterKind.WHITE_BALANCE).toIntOrNull(),
            isWorking = working,
            // GO Ultra's 2.1.5 API exposes no verified query for pre-record state.
            isPreRecording = runtime.isPreRecording,
            isBusy = working != false || runtime.recordingState.isActiveOrTransitioning(),
            recordingState = runtime.recordingState,
            frameSource = sessionContext.frameSource,
            capabilityRevision = capabilityRevision,
        )
        val capabilities = CameraCapabilities(
            supportedEv = parameters.supportedValues(ParameterKind.EV).mapNotNull { it.toDoubleOrNull() },
            supportedShutterSpeed = parameters.supportedValues(ParameterKind.SHUTTER)
                .mapNotNull { it.toShutterSpeedOrNull() },
            supportedIso = parameters.supportedValues(ParameterKind.ISO).mapNotNull { it.toIntOrNull() },
            supportedWhiteBalance = parameters.supportedValues(ParameterKind.WHITE_BALANCE)
                .mapNotNull { it.toIntOrNull() },
            supportedExposurePrograms = parameters.supportedValues(ParameterKind.EXPOSURE_PROGRAM)
                .map { it.toExposureProgram() }
                .distinct(),
            supportParam = supportParam,
            capabilityRevision = capabilityRevision,
            capturedAtEpochMs = now,
        )
        return CameraSnapshot(state, capabilities)
    }

    private fun unavailableSnapshot(now: Long): CameraSnapshot {
        val state = CameraState(
            connectionEpoch = sessionContext.connectionEpoch,
            mode = null,
            exposureProgram = ExposureProgram.UNKNOWN,
            currentEv = null,
            currentIso = null,
            currentShutterSpeed = null,
            currentWhiteBalance = null,
            isWorking = null,
            isPreRecording = null,
            isBusy = true,
            recordingState = RecordingState.UNKNOWN,
            frameSource = FrameSource.UNKNOWN,
            capabilityRevision = capabilityRevision,
        )
        return CameraSnapshot(
            state,
            CameraCapabilities(
                supportedEv = emptyList(),
                supportedShutterSpeed = emptyList(),
                supportedIso = emptyList(),
                supportedWhiteBalance = emptyList(),
                supportedExposurePrograms = emptyList(),
                supportParam = emptySet(),
                capabilityRevision = capabilityRevision,
                capturedAtEpochMs = now,
            ),
        )
    }
}

/** State supplied directly by the official capture-status callback. */
data class CaptureRuntimeState(
    val isPreRecording: Boolean? = null,
    val recordingState: RecordingState = RecordingState.UNKNOWN,
)

private data class RawCameraParameter(
    val name: String,
    val current: Any?,
    val supported: List<Any>,
)

private enum class ParameterKind(vararg val aliases: String) {
    EV("exposure_bias", "exposurebias", "ev"),
    ISO("exposure_iso", "iso"),
    SHUTTER("exposure_shutter_speed", "exposureshutterspeed", "shutter_speed"),
    WHITE_BALANCE("white_balance", "whitebalance"),
    EXPOSURE_PROGRAM("exposure_program", "exposureprogram"),
}

@Suppress("UNCHECKED_CAST")
private suspend fun CameraParam<*>.readRaw(): RawCameraParameter {
    val parameter = this as CameraParam<Any>
    return RawCameraParameter(
        name = parameter.getName(),
        current = parameter.getValue().getOrNull(),
        supported = parameter.getSupported().getOrDefault(emptyList()),
    )
}

private fun List<RawCameraParameter>.firstValue(kind: ParameterKind): Any? =
    firstOrNull { parameter -> parameter.name.matches(kind) }?.current

private fun List<RawCameraParameter>.supportedValues(kind: ParameterKind): List<Any> =
    firstOrNull { parameter -> parameter.name.matches(kind) }?.supported.orEmpty()

private fun String.matches(kind: ParameterKind): Boolean =
    lowercase(Locale.ROOT) in kind.aliases

private fun Any?.toDoubleOrNull(): Double? = when (this) {
    is Number -> toDouble()
    is String -> runCatching { java.lang.Double.parseDouble(trim()) }.getOrNull()
    else -> null
}

private fun Any?.toIntOrNull(): Int? = when (this) {
    is Number -> toInt()
    is String -> runCatching { Integer.parseInt(trim()) }.getOrNull()
    else -> null
}

private fun Any?.toExposureProgram(): ExposureProgram {
    val value = toString().uppercase(Locale.ROOT)
    return when {
        "MANUAL" in value -> ExposureProgram.MANUAL
        "AUTO" in value -> ExposureProgram.AUTO
        else -> ExposureProgram.UNKNOWN
    }
}

private fun Any?.toShutterSpeedOrNull(): ShutterSpeed? {
    val text = this?.toString()?.trim()?.removePrefix("(")?.removeSuffix(")") ?: return null
    val delimiter = if ('/' in text) '/' else ','
    val parts = text.split(delimiter)
    if (parts.size != 2) return null
    val numerator = parts[0].trim().toDoubleOrNull() ?: return null
    val denominator = parts[1].trim().toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
    return ShutterSpeed(numerator, denominator)
}

private fun RecordingState.isActiveOrTransitioning(): Boolean =
    this == RecordingState.STARTING || this == RecordingState.RECORDING || this == RecordingState.STOPPING
