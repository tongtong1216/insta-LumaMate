package com.example.insta_auto_adjust.camera.insta360

import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.param.CameraParam
import com.example.insta_auto_adjust.camera.contract.CameraAction
import com.example.insta_auto_adjust.camera.contract.CameraCommandExecutor
import com.example.insta_auto_adjust.camera.contract.CameraParameter
import com.example.insta_auto_adjust.camera.contract.CameraSnapshot
import com.example.insta_auto_adjust.camera.contract.CameraSnapshotReader
import com.example.insta_auto_adjust.camera.contract.EvTarget
import com.example.insta_auto_adjust.camera.contract.ExecutionReason
import com.example.insta_auto_adjust.camera.contract.ExecutionResult
import com.example.insta_auto_adjust.camera.contract.ExecutionStatus
import com.example.insta_auto_adjust.camera.contract.ExposureProgram
import com.example.insta_auto_adjust.camera.contract.IsoTarget
import com.example.insta_auto_adjust.camera.contract.ParameterAdjustmentStep
import com.example.insta_auto_adjust.camera.contract.ParameterTarget
import com.example.insta_auto_adjust.camera.contract.PolicyAction
import com.example.insta_auto_adjust.camera.contract.PolicyProposal
import com.example.insta_auto_adjust.camera.contract.PolicyProposalExecutor
import com.example.insta_auto_adjust.camera.contract.RecordingState
import com.example.insta_auto_adjust.camera.contract.SafetyDecision
import com.example.insta_auto_adjust.camera.contract.ShutterSpeed
import com.example.insta_auto_adjust.camera.contract.ShutterSpeedTarget
import com.example.insta_auto_adjust.camera.contract.WhiteBalanceTarget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Writes only values that appear in the current camera capability snapshot. */
class Insta360CameraCommandExecutor(
    private val deviceProvider: () -> CameraDevice?,
    private val snapshotReader: CameraSnapshotReader,
    private val clock: () -> Long = System::currentTimeMillis,
    private val setterTimeoutMs: Long = DEFAULT_SETTER_TIMEOUT_MS,
) : CameraCommandExecutor, PolicyProposalExecutor {
    private val commandMutex = Mutex()
    private val dispatchedCommandIds = mutableSetOf<String>()

    override suspend fun setEv(commandId: String, targetEv: Double): ExecutionResult =
        execute(commandId, CameraAction.SET_EV, EV_SPEC) { targetEv }

    override suspend fun setIso(commandId: String, targetIso: Int): ExecutionResult =
        execute(commandId, CameraAction.SET_ISO, ISO_SPEC) { targetIso }

    override suspend fun setShutterSpeed(commandId: String, targetShutterSpeed: ShutterSpeed): ExecutionResult =
        execute(commandId, CameraAction.SET_SHUTTER_SPEED, SHUTTER_SPEC) { targetShutterSpeed }

    override suspend fun setWhiteBalance(commandId: String, targetWhiteBalance: Int): ExecutionResult =
        execute(commandId, CameraAction.SET_WHITE_BALANCE, WHITE_BALANCE_SPEC) { targetWhiteBalance }

    override suspend fun setEvOneStepUp(commandId: String): ExecutionResult =
        execute(commandId, CameraAction.EV_ONE_STEP_UP, EV_SPEC) { it -> EV_SPEC.next(it, 1) }

    override suspend fun setEvOneStepDown(commandId: String): ExecutionResult =
        execute(commandId, CameraAction.EV_ONE_STEP_DOWN, EV_SPEC) { it -> EV_SPEC.next(it, -1) }

    override suspend fun executeConfirmed(
        proposal: PolicyProposal,
        safetyDecision: SafetyDecision,
        userConfirmed: Boolean,
    ): ExecutionResult {
        val action = proposal.action.toCameraAction()
        val before = runCatching { snapshotReader.readSnapshot() }.getOrElse {
            return unknown(proposal.proposalId, action, null, proposal.parameter, null, ExecutionReason.READBACK_FAILURE)
        }
        if (proposal.action == PolicyAction.HOLD) {
            return result(
                proposal.proposalId, CameraAction.HOLD, before, null, null, before,
                ExecutionStatus.SKIPPED, ExecutionReason.POLICY_HOLD,
            )
        }
        if (!userConfirmed) {
            return result(
                proposal.proposalId, action, before, proposal.parameter, null, before,
                ExecutionStatus.REJECTED, ExecutionReason.USER_NOT_CONFIRMED,
            )
        }
        if (!safetyDecision.allowed ||
            (safetyDecision.checkedProposalId != null && safetyDecision.checkedProposalId != proposal.proposalId)
        ) {
            return result(
                proposal.proposalId, action, before, proposal.parameter, null, before,
                ExecutionStatus.REJECTED, ExecutionReason.SAFETY_REJECTED,
            )
        }
        if (clock() > proposal.validUntilEpochMs ||
            before.state.connectionEpoch != proposal.connectionEpoch ||
            before.state.capabilityRevision != proposal.capabilityRevision
        ) {
            return result(
                proposal.proposalId, action, before, proposal.parameter, null, before,
                ExecutionStatus.REJECTED, ExecutionReason.STALE_PROPOSAL,
            )
        }
        return when (proposal.action) {
            PolicyAction.HOLD -> error("handled above")
            PolicyAction.EV_ONE_STEP_UP -> proposal.parameter.executeEvOrStep(
                proposal.proposalId,
                before.state.currentEv,
                upward = true,
            )
            PolicyAction.EV_ONE_STEP_DOWN -> proposal.parameter.executeEvOrStep(
                proposal.proposalId,
                before.state.currentEv,
                upward = false,
            )
            PolicyAction.SET_ISO -> when (val target = proposal.parameter) {
                is IsoTarget -> setIso(proposal.proposalId, target.value)
                else -> targetMismatch(proposal, action, before)
            }
            PolicyAction.SET_SHUTTER -> when (val target = proposal.parameter) {
                is ShutterSpeedTarget -> setShutterSpeed(proposal.proposalId, target.value)
                else -> targetMismatch(proposal, action, before)
            }
            PolicyAction.SET_WHITE_BALANCE -> when (val target = proposal.parameter) {
                is WhiteBalanceTarget -> setWhiteBalance(proposal.proposalId, target.value)
                else -> targetMismatch(proposal, action, before)
            }
        }
    }

    private suspend fun ParameterTarget?.executeEvOrStep(
        commandId: String,
        currentEv: Double?,
        upward: Boolean,
    ): ExecutionResult = when (this) {
        null -> if (upward) setEvOneStepUp(commandId) else setEvOneStepDown(commandId)
        is EvTarget -> {
            val validDirection = currentEv != null &&
                if (upward) value > currentEv else value < currentEv
            if (validDirection) setEv(commandId, value)
            else result(
                commandId,
                if (upward) CameraAction.EV_ONE_STEP_UP else CameraAction.EV_ONE_STEP_DOWN,
                null,
                this,
                null,
                null,
                ExecutionStatus.REJECTED,
                ExecutionReason.TARGET_ACTION_MISMATCH,
            )
        }
        else -> result(
            commandId,
            if (upward) CameraAction.EV_ONE_STEP_UP else CameraAction.EV_ONE_STEP_DOWN,
            null,
            this,
            null,
            null,
            ExecutionStatus.REJECTED,
            ExecutionReason.TARGET_ACTION_MISMATCH,
        )
    }

    private fun targetMismatch(
        proposal: PolicyProposal,
        action: CameraAction,
        before: CameraSnapshot,
    ) = result(
        proposal.proposalId,
        action,
        before,
        proposal.parameter,
        null,
        before,
        ExecutionStatus.REJECTED,
        ExecutionReason.TARGET_ACTION_MISMATCH,
    )

    private fun PolicyAction.toCameraAction(): CameraAction = when (this) {
        PolicyAction.HOLD -> CameraAction.HOLD
        PolicyAction.EV_ONE_STEP_UP -> CameraAction.EV_ONE_STEP_UP
        PolicyAction.EV_ONE_STEP_DOWN -> CameraAction.EV_ONE_STEP_DOWN
        PolicyAction.SET_ISO -> CameraAction.SET_ISO
        PolicyAction.SET_SHUTTER -> CameraAction.SET_SHUTTER_SPEED
        PolicyAction.SET_WHITE_BALANCE -> CameraAction.SET_WHITE_BALANCE
    }

    private suspend fun <T : Any> execute(
        commandId: String,
        action: CameraAction,
        spec: ParameterSpec<T>,
        targetResolver: (CameraSnapshot) -> T?,
    ): ExecutionResult = commandMutex.withLock {
        if (commandId.isBlank()) return@withLock rejected(commandId, action, ExecutionReason.INVALID_COMMAND)
        if (commandId in dispatchedCommandIds) {
            return@withLock rejected(commandId, action, ExecutionReason.DUPLICATE_COMMAND)
        }
        val before = runCatching { snapshotReader.readSnapshot() }.getOrElse {
            return@withLock unknown(commandId, action, null, null, null, ExecutionReason.READBACK_FAILURE)
        }
        before.preflightFailure(spec)?.let { return@withLock rejected(commandId, action, it, before) }
        val targetValue = targetResolver(before) ?: return@withLock rejected(
            commandId,
            action,
            if (action == CameraAction.EV_ONE_STEP_UP || action == CameraAction.EV_ONE_STEP_DOWN) {
                ExecutionReason.NO_ADJACENT_EV
            } else {
                ExecutionReason.ILLEGAL_PARAMETER_TARGET
            },
            before,
        )
        val target = spec.asTarget(targetValue)
        val stepTargets = spec.path(before, targetValue)
            ?: return@withLock rejected(commandId, action, ExecutionReason.ILLEGAL_PARAMETER_TARGET, before)
        if (stepTargets.isEmpty()) {
            return@withLock result(
                commandId, action, before, target, null, before, ExecutionStatus.SUCCEEDED, null,
            )
        }
        val device = deviceProvider()?.takeIf { it.isConnected() }
            ?: return@withLock rejected(commandId, action, ExecutionReason.CAMERA_DISCONNECTED, before)
        dispatchedCommandIds += commandId
        val steps = mutableListOf<ParameterAdjustmentStep>()
        var latestReadback: CameraSnapshot = before
        for (stepTarget in stepTargets) {
            if (!device.isConnected()) {
                return@withLock result(
                    commandId, action, before, target, null, latestReadback,
                    ExecutionStatus.UNKNOWN, ExecutionReason.CONNECTION_CHANGED, steps,
                )
            }
            val step = device.setAndRead(spec, stepTarget)
            steps += step.evidence
            step.readback?.let { latestReadback = it }
            if (step.status != ExecutionStatus.SUCCEEDED) {
                return@withLock result(
                    commandId, action, before, target, step.evidence.sdkAcknowledged, step.readback,
                    step.status, step.reason, steps,
                )
            }
            val readback = checkNotNull(step.readback)
            if (readback.state.connectionEpoch != before.state.connectionEpoch) {
                return@withLock result(
                    commandId, action, before, target, true, readback,
                    ExecutionStatus.UNKNOWN, ExecutionReason.CONNECTION_CHANGED, steps,
                )
            }
            if (readback.state.capabilityRevision != before.state.capabilityRevision) {
                return@withLock result(
                    commandId, action, before, target, true, readback,
                    ExecutionStatus.UNKNOWN, ExecutionReason.CAPABILITY_CHANGED, steps,
                )
            }
            readback.preflightFailure(spec)?.let { reason ->
                return@withLock result(
                    commandId, action, before, target, true, readback,
                    ExecutionStatus.FAILED, reason, steps,
                )
            }
        }
        result(commandId, action, before, target, true, latestReadback, ExecutionStatus.SUCCEEDED, null, steps)
    }

    private suspend fun <T : Any> CameraDevice.setAndRead(
        spec: ParameterSpec<T>,
        targetValue: T,
    ): StepResult {
        val parameter = capture.getSupportParam().firstOrNull { it.getName().matches(spec) }
            ?: return StepResult.failure(
                spec.asTarget(targetValue), ExecutionStatus.UNKNOWN, ExecutionReason.CAPABILITY_CHANGED,
            )
        @Suppress("UNCHECKED_CAST")
        val rawParameter = parameter as CameraParam<Any>
        val rawTarget = rawParameter.getSupported().getOrNull()
            ?.firstOrNull { raw -> spec.rawToValue(raw)?.let { spec.same(it, targetValue) } == true }
            ?: return StepResult.failure(
                spec.asTarget(targetValue), ExecutionStatus.UNKNOWN, ExecutionReason.CAPABILITY_CHANGED,
            )
        val setAttempt = runCatching {
            withTimeoutOrNull(setterTimeoutMs) { rawParameter.setValue(rawTarget) }
        }
        val readback = runCatching { snapshotReader.readSnapshot() }.getOrNull()
        val target = spec.asTarget(targetValue)
        if (setAttempt.isFailure) {
            return StepResult.failure(target, ExecutionStatus.FAILED, ExecutionReason.SDK_FAILURE, false, readback)
        }
        val sdkResult = setAttempt.getOrNull()
        if (sdkResult == null) {
            return StepResult.failure(target, ExecutionStatus.UNKNOWN, ExecutionReason.SETTER_TIMEOUT, null, readback)
        }
        if (sdkResult.isFailure) {
            return StepResult.failure(target, ExecutionStatus.FAILED, ExecutionReason.SDK_FAILURE, false, readback)
        }
        if (readback == null) {
            return StepResult.failure(target, ExecutionStatus.UNKNOWN, ExecutionReason.READBACK_FAILURE, true)
        }
        val actual = spec.current(readback)
        if (actual == null || !spec.same(actual, targetValue)) {
            return StepResult.failure(target, ExecutionStatus.FAILED, ExecutionReason.READBACK_MISMATCH, true, readback)
        }
        return StepResult.success(target, spec.asTarget(actual), readback)
    }

    private fun <T : Any> CameraSnapshot.preflightFailure(spec: ParameterSpec<T>): ExecutionReason? = when {
        state.connectionEpoch == "disconnected" -> ExecutionReason.CAMERA_DISCONNECTED
        state.recordingState in ACTIVE_RECORDING_STATES -> ExecutionReason.RECORDING_ACTIVE
        state.isBusy || state.isWorking != false -> ExecutionReason.CAMERA_BUSY
        spec.manualExposureOnly && state.exposureProgram != ExposureProgram.MANUAL -> {
            ExecutionReason.MANUAL_EXPOSURE_REQUIRED
        }
        spec.supported(this).isEmpty() -> ExecutionReason.MISSING_EV_CAPABILITY
        spec.current(this) == null -> ExecutionReason.CURRENT_PARAMETER_UNREADABLE
        else -> null
    }

    private fun String.matches(spec: ParameterSpec<*>): Boolean =
        lowercase(Locale.ROOT) in spec.aliases

    private fun rejected(
        commandId: String,
        action: CameraAction,
        reason: ExecutionReason,
        before: CameraSnapshot? = null,
    ) = result(commandId, action, before, null, null, null, ExecutionStatus.REJECTED, reason)

    private fun unknown(
        commandId: String,
        action: CameraAction,
        before: CameraSnapshot?,
        target: ParameterTarget?,
        readback: CameraSnapshot?,
        reason: ExecutionReason,
    ) = result(commandId, action, before, target, null, readback, ExecutionStatus.UNKNOWN, reason)

    private fun result(
        commandId: String,
        action: CameraAction,
        before: CameraSnapshot?,
        target: ParameterTarget?,
        sdkAcknowledged: Boolean?,
        readback: CameraSnapshot?,
        status: ExecutionStatus,
        reason: ExecutionReason?,
        steps: List<ParameterAdjustmentStep> = emptyList(),
    ) = ExecutionResult(
        commandId = commandId,
        action = action,
        before = before,
        target = target,
        sdkAcknowledged = sdkAcknowledged,
        readback = readback,
        status = status,
        reason = reason,
        steps = steps,
        completedAtEpochMs = clock(),
    )

    private data class ParameterSpec<T : Any>(
        val parameter: CameraParameter,
        val aliases: Set<String>,
        val manualExposureOnly: Boolean,
        val current: (CameraSnapshot) -> T?,
        val supported: (CameraSnapshot) -> List<T>,
        val rawToValue: (Any?) -> T?,
        val comparator: Comparator<T>,
        val same: (T, T) -> Boolean,
        val asTarget: (T) -> ParameterTarget,
    ) {
        fun path(snapshot: CameraSnapshot, target: T): List<T>? {
            val legalValues = supported(snapshot).distinct().sortedWith(comparator)
            val currentValue = current(snapshot) ?: return null
            val currentIndex = legalValues.indexOfFirst { same(it, currentValue) }
            val targetIndex = legalValues.indexOfFirst { same(it, target) }
            if (currentIndex < 0 || targetIndex < 0) return null
            return when {
                currentIndex == targetIndex -> emptyList()
                currentIndex < targetIndex -> legalValues.subList(currentIndex + 1, targetIndex + 1)
                else -> (targetIndex until currentIndex).reversed().map(legalValues::get)
            }
        }

        fun next(snapshot: CameraSnapshot, direction: Int): T? {
            val legalValues = supported(snapshot).distinct().sortedWith(comparator)
            val currentValue = current(snapshot) ?: return null
            val currentIndex = legalValues.indexOfFirst { same(it, currentValue) }
            return legalValues.getOrNull(currentIndex + direction)
        }
    }

    private data class StepResult(
        val evidence: ParameterAdjustmentStep,
        val readback: CameraSnapshot?,
        val status: ExecutionStatus,
        val reason: ExecutionReason?,
    ) {
        companion object {
            fun success(target: ParameterTarget, readback: ParameterTarget, snapshot: CameraSnapshot) = StepResult(
                ParameterAdjustmentStep(target, true, readback, ExecutionStatus.SUCCEEDED, null),
                snapshot,
                ExecutionStatus.SUCCEEDED,
                null,
            )

            fun failure(
                target: ParameterTarget,
                status: ExecutionStatus,
                reason: ExecutionReason,
                sdkAcknowledged: Boolean? = null,
                readback: CameraSnapshot? = null,
            ) = StepResult(
                ParameterAdjustmentStep(target, sdkAcknowledged, null, status, reason),
                readback,
                status,
                reason,
            )
        }
    }

    private companion object {
        const val DEFAULT_SETTER_TIMEOUT_MS = 5_000L
        val ACTIVE_RECORDING_STATES = setOf(
            RecordingState.STARTING,
            RecordingState.RECORDING,
            RecordingState.STOPPING,
        )
        val EV_SPEC = ParameterSpec(
            CameraParameter.EV, setOf("exposure_bias", "exposurebias", "ev"), false,
            { it.state.currentEv }, { it.capabilities.supportedEv }, ::rawDoubleOrNull,
            Comparator.naturalOrder(), ::sameDouble, ::EvTarget,
        )
        val ISO_SPEC = ParameterSpec(
            CameraParameter.ISO, setOf("exposure_iso", "iso"), true,
            { it.state.currentIso }, { it.capabilities.supportedIso }, ::rawIntOrNull,
            Comparator.naturalOrder(), Int::equals, ::IsoTarget,
        )
        val SHUTTER_SPEC = ParameterSpec(
            CameraParameter.SHUTTER_SPEED,
            setOf("exposure_shutter_speed", "exposureshutterspeed", "shutter_speed"),
            true,
            { it.state.currentShutterSpeed }, { it.capabilities.supportedShutterSpeed },
            ::rawShutterSpeedOrNull, compareBy { it.numerator / it.denominator },
            ::sameShutterSpeed, ::ShutterSpeedTarget,
        )
        val WHITE_BALANCE_SPEC = ParameterSpec(
            CameraParameter.WHITE_BALANCE, setOf("white_balance", "whitebalance"), false,
            { it.state.currentWhiteBalance }, { it.capabilities.supportedWhiteBalance }, ::rawIntOrNull,
            Comparator.naturalOrder(), Int::equals, ::WhiteBalanceTarget,
        )
    }
}

private fun rawDoubleOrNull(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> runCatching { java.lang.Double.parseDouble(value.trim()) }.getOrNull()
    else -> null
}

private fun rawIntOrNull(value: Any?): Int? = when (value) {
    is Number -> value.toInt()
    is String -> runCatching { Integer.parseInt(value.trim()) }.getOrNull()
    else -> null
}

private fun rawShutterSpeedOrNull(value: Any?): ShutterSpeed? {
    val text = value?.toString()?.trim()?.removePrefix("(")?.removeSuffix(")") ?: return null
    val delimiter = if ('/' in text) '/' else ','
    val parts = text.split(delimiter)
    if (parts.size != 2) return null
    val numerator = rawDoubleOrNull(parts[0].trim()) ?: return null
    val denominator = rawDoubleOrNull(parts[1].trim())?.takeIf { it != 0.0 } ?: return null
    return ShutterSpeed(numerator, denominator)
}

private fun sameDouble(left: Double, right: Double): Boolean =
    kotlin.math.abs(left - right) < VALUE_EPSILON

private fun sameShutterSpeed(left: ShutterSpeed, right: ShutterSpeed): Boolean =
    sameDouble(left.numerator / left.denominator, right.numerator / right.denominator)

private const val VALUE_EPSILON = 0.000_001
