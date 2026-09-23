package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedPolicyEngineTest {
    private val engine = AdvancedPolicyEngine()
    private val capabilities = AdvancedCameraCapabilities(
        supportedShutterSeconds = listOf(1.0 / 1000, 1.0 / 500, 1.0 / 250, 1.0 / 125, 1.0 / 60),
        supportedIso = listOf(100, 200, 400, 800, 1600),
        supportedWhiteBalanceKelvin = listOf(3200, 4000, 5000, 5600, 6500),
        shutterReadable = true,
        shutterWritable = true,
        isoReadable = true,
        isoWritable = true,
        whiteBalanceReadable = true,
        whiteBalanceWritable = true,
        executionMode = ExecutionMode.MOCK,
        inputSource = InputSource.MOCK,
    )
    private val state = AdvancedCameraState(
        cameraStateRevision = 4,
        mode = "video",
        exposureProgram = ExposureProgram.MANUAL,
        currentShutterSeconds = 1.0 / 125,
        currentIso = 400,
        currentWhiteBalanceKelvin = 4000,
        recordingState = RecordingState.IDLE,
        isBusy = false,
        stateKnown = true,
    )

    @Test
    fun motionClarityUsesAdjacentFasterShutterAndStaysMock() {
        val proposal = engine.proposeMotion(
            MotionIntent(3, MotionPriority.MOTION_CLARITY, StabilityPreference.NORMAL, "动作清楚"),
            MotionMetrics(10, motionScore = 0.8, darkRatio = 0.2),
            semantic(), state, capabilities, 1_000,
        )

        assertEquals(AdvancedAction.SET_SHUTTER, proposal.action)
        assertEquals(1.0 / 250, proposal.targetShutterSeconds!!, 1e-12)
        assertEquals(ExecutionMode.MOCK, proposal.executionMode)
        assertEquals(InputSource.MOCK, proposal.inputSource)
        assertFalse(AdvancedSafetyGuard.check(proposal).allowed)
        assertEquals(SafetyRejection.MOCK_EXECUTION,
            AdvancedSafetyGuard.check(proposal).rejection)
    }

    @Test
    fun lowNoiseAndBrightnessChooseAdjacentIsoValues() {
        val lowNoise = engine.proposeMotion(
            MotionIntent(3, MotionPriority.LOW_NOISE, StabilityPreference.NORMAL),
            MotionMetrics(10, 0.1, 0.2), semantic(), state, capabilities, 1_000,
        )
        val brightness = engine.proposeMotion(
            MotionIntent(3, MotionPriority.BRIGHTNESS_PRIORITY, StabilityPreference.NORMAL),
            MotionMetrics(10, 0.1, 0.7), semantic(), state, capabilities, 1_000,
        )

        assertEquals(AdvancedAction.SET_ISO, lowNoise.action)
        assertEquals(200, lowNoise.targetIso)
        assertEquals(AdvancedAction.SET_ISO, brightness.action)
        assertEquals(800, brightness.targetIso)
    }

    @Test
    fun autoModeAndMissingCapabilitiesHold() {
        val auto = engine.proposeMotion(
            MotionIntent(3, MotionPriority.MOTION_CLARITY, StabilityPreference.NORMAL),
            MotionMetrics(10, 0.8, 0.2), semantic(),
            state.copy(exposureProgram = ExposureProgram.AUTO), capabilities, 1_000,
        )
        assertEquals(AdvancedAction.HOLD, auto.action)
        assertEquals(AdvancedReasonCode.MANUAL_MODE_REQUIRED, auto.reasonCode)

        val missing = engine.proposeMotion(
            MotionIntent(3, MotionPriority.MOTION_CLARITY, StabilityPreference.NORMAL),
            MotionMetrics(10, 0.8, 0.2), semantic(), state,
            capabilities.copy(supportedShutterSeconds = emptyList()), 1_000,
        )
        assertEquals(AdvancedReasonCode.CAMERA_CAPABILITY_UNVERIFIED, missing.reasonCode)
        assertNull(missing.targetShutterSeconds)
    }

    @Test
    fun colorAccuracyAndNaturalSkinUseSupportedWhiteBalance() {
        val accurate = engine.proposeColor(
            ColorIntent(3, ColorPriority.COLOR_ACCURACY, StabilityPreference.NORMAL),
            ColorMetrics(11, 5_700), semantic(), state, capabilities, 1_000,
        )
        val skin = engine.proposeColor(
            ColorIntent(3, ColorPriority.NATURAL_SKIN, StabilityPreference.NORMAL),
            ColorMetrics(11, 3_200), semantic(), state, capabilities, 1_000,
        )

        assertEquals(AdvancedAction.SET_WHITE_BALANCE, accurate.action)
        assertEquals(5_600, accurate.targetWhiteBalanceKelvin)
        assertEquals(AdvancedAction.SET_WHITE_BALANCE, skin.action)
        assertEquals(5_000, skin.targetWhiteBalanceKelvin)
    }

    @Test
    fun atmosphereAndColorStabilitySafelyHold() {
        val atmosphere = engine.proposeColor(
            ColorIntent(3, ColorPriority.ATMOSPHERE_PRESERVATION, StabilityPreference.NORMAL),
            ColorMetrics(11, 3_200), semantic(), state, capabilities, 1_000,
        )
        val stability = engine.proposeColor(
            ColorIntent(3, ColorPriority.COLOR_STABILITY, StabilityPreference.NORMAL),
            ColorMetrics(11, 3_200), semantic(), state, capabilities, 1_000,
        )

        assertEquals(AdvancedAction.HOLD, atmosphere.action)
        assertEquals(AdvancedReasonCode.ATMOSPHERE_PRESERVED, atmosphere.reasonCode)
        assertEquals(AdvancedAction.HOLD, stability.action)
        assertEquals(AdvancedReasonCode.WHITE_BALANCE_LOCK_UNVERIFIED, stability.reasonCode)
    }

    @Test
    fun unusableSemanticAlwaysHolds() {
        val proposal = engine.proposeColor(
            ColorIntent(3, ColorPriority.COLOR_ACCURACY, StabilityPreference.NORMAL),
            ColorMetrics(11, 5_700), semantic().copy(status = SemanticStatus.MOCK),
            state, capabilities, 1_000,
        )
        assertEquals(AdvancedAction.HOLD, proposal.action)
        assertEquals(AdvancedReasonCode.SEMANTIC_UNAVAILABLE, proposal.reasonCode)
    }

    private fun semantic() = SceneSemantic(
        8, 3, SemanticStatus.OK, SceneLabel.INDOOR_MIXED_LIGHT, SubjectType.PERSON,
        BrightRegionType.LAMP, true, emptyList(), "展示文字",
    )
}
