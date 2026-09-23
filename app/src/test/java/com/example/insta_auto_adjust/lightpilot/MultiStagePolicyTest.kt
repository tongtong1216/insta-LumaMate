package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiStagePolicyTest {
    @Test
    fun `coordinator evaluates all three active stages but returns one decision`() {
        val coordinator = MultiStagePolicyCoordinator()
        val multi = intent(StageWeights(0.9, 0.8, 0.7))
        val semantic = SceneSemantic(
            8, 3, SemanticStatus.OK, SceneLabel.STAGE_COLORED_LIGHT, SubjectType.PERSON,
            BrightRegionType.LAMP, true, emptyList(), "测试",
        )
        var result: MultiStageEvaluation? = null
        repeat(3) {
            result = coordinator.evaluate(
                multi,
                VisionMetrics(10L + it, 0.2, 0.5, 0.01, 0.2),
                MotionMetrics(10L + it, 0.8, 0.2),
                ColorMetrics(10L + it, 5_600),
                semantic,
                CameraState(4, "photo", RecordingState.IDLE, false, 0.0, true),
                CameraCapabilities(listOf(-1.0, 0.0, 1.0), true, true, false,
                    ExecutionMode.REAL),
                AdvancedCameraState(4, "video", ExposureProgram.MANUAL, 1.0 / 125,
                    400, 4000, RecordingState.IDLE, false, true),
                AdvancedCameraCapabilities(
                    listOf(1.0 / 250, 1.0 / 125), listOf(100, 400),
                    listOf(4000, 5600), true, true, true, true, true, true,
                    ExecutionMode.MOCK, InputSource.MOCK,
                ),
                1_000L + it,
            )
        }
        assertEquals(3, result!!.rawCandidates.size)
        assertEquals(PolicyStage.EXPOSURE, result!!.decision.selected!!.stage)
        assertEquals(UnifiedAction.EV_ONE_STEP_UP, result!!.decision.selected!!.action)
    }

    @Test
    fun `arbiter selects at most one highest scoring action`() {
        val intent = intent(StageWeights(0.8, 0.7, 0.6))
        val decision = PolicyArbiter().arbitrate(intent, listOf(
            candidate(PolicyStage.EXPOSURE, 0.8, 0.9),
            candidate(PolicyStage.MOTION_NOISE, 0.7, 0.8, UnifiedAction.SET_SHUTTER),
            candidate(PolicyStage.COLOR_ATMOSPHERE, 0.6, 0.8, UnifiedAction.SET_WHITE_BALANCE),
        ), 1_100)
        assertEquals(ArbitrationReason.ACTION_SELECTED, decision.reason)
        assertEquals(PolicyStage.EXPOSURE, decision.selected!!.stage)
    }

    @Test
    fun `score difference below point one requires user choice`() {
        val intent = intent(StageWeights(0.8, 0.8, 0.2))
        val decision = PolicyArbiter().arbitrate(intent, listOf(
            candidate(PolicyStage.EXPOSURE, 0.8, 0.9),
            candidate(PolicyStage.MOTION_NOISE, 0.8, 0.85, UnifiedAction.SET_SHUTTER),
        ), 1_100)
        assertNull(decision.selected)
        assertEquals(ArbitrationReason.MULTI_OBJECTIVE_CONFLICT, decision.reason)
        assertTrue(decision.requiresUserChoice)
    }

    @Test
    fun `blocked highest weight goal never silently falls through`() {
        val intent = intent(StageWeights(0.6, 0.95, 0.2))
        val blockedMotion = candidate(
            PolicyStage.MOTION_NOISE, 0.95, 1.0, UnifiedAction.SET_SHUTTER,
            constraints = listOf(StageConstraint(
                StageConstraintCode.PLANNING_ONLY, "尚未验收", true)),
        )
        val decision = PolicyArbiter().arbitrate(intent, listOf(
            blockedMotion, candidate(PolicyStage.EXPOSURE, 0.6, 1.0),
        ), 1_100)
        assertNull(decision.selected)
        assertEquals(ArbitrationReason.PRIMARY_OBJECTIVE_BLOCKED, decision.reason)
    }

    @Test
    fun `neon preservation constraint removes white balance correction`() {
        val multi = intent(StageWeights(0.2, 0.8, 0.8)).copy(
            colorPriority = ColorPriority.COLORED_LIGHT_PRESERVATION,
        )
        val preservation = StageCandidate(
            PolicyStage.COLOR_ATMOSPHERE, 0.8, UnifiedAction.HOLD, 0.0,
            CandidateDisposition.SATISFIED, "保留霓虹", "COLOR/HOLD",
            1_000, 6_000, 3, 4, true,
            listOf(StageConstraint(StageConstraintCode.COLORED_LIGHT_PRESERVATION,
                "不主动中和霓虹", false)),
        )
        val whiteBalance = candidate(PolicyStage.COLOR_ATMOSPHERE, 0.8, 1.0,
            UnifiedAction.SET_WHITE_BALANCE)
        val motion = candidate(PolicyStage.MOTION_NOISE, 0.8, 0.7,
            UnifiedAction.SET_SHUTTER)
        val decision = PolicyArbiter().arbitrate(
            multi, listOf(preservation, whiteBalance, motion), 1_100,
        )
        assertEquals(UnifiedAction.SET_SHUTTER, decision.selected!!.action)
        assertTrue(decision.alternatives.none { it.action == UnifiedAction.SET_WHITE_BALANCE })
    }

    @Test
    fun `normal stability needs three matching local frames and resets after execution`() {
        val controller = FusionTemporalController()
        val input = listOf(candidate(PolicyStage.EXPOSURE, 0.8, 1.0,
            temporalConfirmed = false))
        assertFalse(controller.observe(input, StabilityPreference.NORMAL).single().temporalConfirmed)
        assertFalse(controller.observe(input, StabilityPreference.NORMAL).single().temporalConfirmed)
        assertTrue(controller.observe(input, StabilityPreference.NORMAL).single().temporalConfirmed)
        controller.onExecutionAcknowledged()
        assertFalse(controller.observe(input, StabilityPreference.NORMAL).single().temporalConfirmed)
    }

    @Test
    fun `camera revision change invalidates temporal count`() {
        val controller = FusionTemporalController()
        val input = listOf(candidate(PolicyStage.EXPOSURE, 0.8, 1.0,
            temporalConfirmed = false))
        controller.observe(input, StabilityPreference.NORMAL)
        controller.observe(input, StabilityPreference.NORMAL)
        val changed = input.map { it.copy(cameraStateRevision = 5) }
        assertFalse(controller.observe(changed, StabilityPreference.NORMAL).single().temporalConfirmed)
    }

    @Test
    fun `direction change resets every stage count`() {
        val controller = FusionTemporalController()
        val exposure = candidate(PolicyStage.EXPOSURE, 0.8, 1.0,
            temporalConfirmed = false)
        val motion = candidate(PolicyStage.MOTION_NOISE, 0.7, 1.0,
            UnifiedAction.SET_SHUTTER, temporalConfirmed = false)
        controller.observe(listOf(exposure, motion), StabilityPreference.NORMAL)
        controller.observe(listOf(exposure, motion), StabilityPreference.NORMAL)
        val changedExposure = exposure.copy(
            action = UnifiedAction.EV_ONE_STEP_DOWN,
            targetSignature = "EXPOSURE/EV_ONE_STEP_DOWN",
        )
        val result = controller.observe(listOf(changedExposure, motion), StabilityPreference.NORMAL)
        assertFalse(result.single { it.stage == PolicyStage.MOTION_NOISE }.temporalConfirmed)
    }

    @Test
    fun `planning only advanced candidate is never selectable`() {
        val multi = intent(StageWeights(0.2, 0.9, 0.2))
        val proposal = AdvancedPolicyProposal(
            stage = AdvancedStage.MOTION_NOISE,
            intentRevision = 3,
            cameraStateRevision = 4,
            semanticFrameId = 8,
            metricsFrameId = 10,
            semanticStatus = SemanticStatus.OK,
            executionMode = ExecutionMode.MOCK,
            inputSource = InputSource.MOCK,
            action = AdvancedAction.SET_SHUTTER,
            targetShutterSeconds = 1.0 / 250,
            reasonCode = AdvancedReasonCode.MOTION_DETECTED,
            reason = "运动明显",
            risk = RiskLevel.MEDIUM,
            createdAtMs = 1_000,
            expiresAtMs = 6_000,
        )
        val candidate = StageCandidateFactory.advanced(
            multi, proposal, MotionMetrics(10, 0.8, 0.2),
            AdvancedCameraState(4, "video", ExposureProgram.MANUAL, 1.0 / 125,
                400, 4000, RecordingState.IDLE, false, true),
            AdvancedCameraCapabilities(
                listOf(1.0 / 250, 1.0 / 125), listOf(100, 400), listOf(4000, 5600),
                true, true, true, true, true, true, ExecutionMode.MOCK, InputSource.MOCK,
            ),
        ).copy(temporalConfirmed = true)
        assertTrue(candidate.blocked)
        assertEquals(ArbitrationReason.PRIMARY_OBJECTIVE_BLOCKED,
            PolicyArbiter().arbitrate(multi, listOf(candidate), 1_100).reason)
    }

    private fun intent(weights: StageWeights) = MultiStageIntent(
        3, "多目标测试", weights, ExposurePriority.SUBJECT_DETAIL,
        MotionPriority.MOTION_CLARITY, ColorPriority.COLOR_ACCURACY,
        StabilityPreference.NORMAL,
    )

    private fun candidate(
        stage: PolicyStage,
        weight: Double,
        urgency: Double,
        action: UnifiedAction = UnifiedAction.EV_ONE_STEP_UP,
        temporalConfirmed: Boolean = true,
        constraints: List<StageConstraint> = emptyList(),
    ) = StageCandidate(
        stage, weight, action, urgency, CandidateDisposition.ACTION, "测试",
        "$stage/$action", 1_000, 6_000, 3, 4, temporalConfirmed, constraints,
    )
}
