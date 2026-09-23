package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldScopedUncertaintyTest {
    private val advancedEngine = AdvancedPolicyEngine()
    private val advancedCapabilities = AdvancedCameraCapabilities(
        supportedShutterSeconds = listOf(1.0 / 500, 1.0 / 250, 1.0 / 125, 1.0 / 60),
        supportedIso = listOf(100, 200, 400, 800),
        supportedWhiteBalanceKelvin = listOf(3200, 4000, 5000, 5600, 6500),
        shutterReadable = true, shutterWritable = true,
        isoReadable = true, isoWritable = true,
        whiteBalanceReadable = true, whiteBalanceWritable = true,
        executionMode = ExecutionMode.MOCK, inputSource = InputSource.MOCK,
    )
    private val advancedState = AdvancedCameraState(
        4, "video", ExposureProgram.MANUAL, 1.0 / 125, 400, 4000,
        RecordingState.IDLE, false, true,
    )

    @Test
    fun `subject occlusion does not block global motion policy`() {
        val semantic = semanticWith(UncertaintyDetail(
            UncertaintyCode.SUBJECT_OCCLUDED, UncertaintySeverity.WARNING,
            setOf(SemanticField.SUBJECT_TYPE, SemanticField.SUBJECT_ROI), "主体被遮挡",
        ))
        val proposal = advancedEngine.proposeMotion(
            MotionIntent(3, MotionPriority.MOTION_CLARITY, StabilityPreference.NORMAL),
            MotionMetrics(10, 0.8, 0.2), semantic, advancedState, advancedCapabilities, 1_000,
        )
        assertEquals(AdvancedAction.SET_SHUTTER, proposal.action)
    }

    @Test
    fun `subject uncertainty blocks skin strategy but not highlight exposure`() {
        val semantic = semanticWith(UncertaintyDetail(
            UncertaintyCode.SUBJECT_TYPE_UNCERTAIN, UncertaintySeverity.WARNING,
            setOf(SemanticField.SUBJECT_TYPE, SemanticField.SUBJECT_ROI), "主体类别和 ROI 不确定",
        ))
        val skin = advancedEngine.proposeColor(
            ColorIntent(3, ColorPriority.NATURAL_SKIN, StabilityPreference.NORMAL),
            ColorMetrics(10, 5200), semantic, advancedState, advancedCapabilities, 1_000,
        )
        assertEquals(AdvancedReasonCode.SEMANTIC_UNAVAILABLE, skin.reasonCode)

        val exposure = PolicyEngine().propose(
            UserIntent(3, ExposurePriority.HIGHLIGHT_DETAIL, StabilityPreference.NORMAL),
            VisionMetrics(10, null, 0.6, 0.10, 0.1), semantic,
            CameraState(4, "photo", RecordingState.IDLE, false, 0.0, true),
            CameraCapabilities(listOf(-1.0, 0.0, 1.0), true, true, false, ExecutionMode.REAL),
            1_000,
        )
        assertEquals(ExposureAction.EV_ONE_STEP_DOWN, exposure.action)
    }

    @Test
    fun `colored light uncertainty only blocks dependent color strategy`() {
        val semantic = semanticWith(UncertaintyDetail(
            UncertaintyCode.COLORED_LIGHT_UNCERTAIN, UncertaintySeverity.WARNING,
            setOf(SemanticField.COLORED_LIGHT), "彩色光判断不稳定",
        ))
        val color = advancedEngine.proposeColor(
            ColorIntent(3, ColorPriority.COLORED_LIGHT_PRESERVATION, StabilityPreference.NORMAL),
            ColorMetrics(10, 5200), semantic, advancedState, advancedCapabilities, 1_000,
        )
        val motion = advancedEngine.proposeMotion(
            MotionIntent(3, MotionPriority.MOTION_CLARITY, StabilityPreference.NORMAL),
            MotionMetrics(10, 0.8, 0.2), semantic, advancedState, advancedCapabilities, 1_000,
        )
        assertEquals(AdvancedReasonCode.SEMANTIC_UNAVAILABLE, color.reasonCode)
        assertEquals(AdvancedAction.SET_SHUTTER, motion.action)
    }

    @Test
    fun `rc2 legacy warning and affects all still block everything`() {
        val rc2 = semanticWith().copy(uncertainty = listOf("旧客户端无法识别字段范围"))
        assertTrue(rc2.hasGlobalBlocker)
        val blocking = semanticWith(UncertaintyDetail(
            UncertaintyCode.TIMEOUT, UncertaintySeverity.BLOCKING,
            setOf(SemanticField.ALL), "超时",
        ))
        assertTrue(blocking.hasGlobalBlocker)
    }

    private fun semanticWith(vararg details: UncertaintyDetail) = SceneSemantic(
        8, 3, SemanticStatus.OK, SceneLabel.INDOOR_MIXED_LIGHT, SubjectType.PERSON,
        BrightRegionType.LAMP, true, details.map { it.message }, "展示文字", details.toList(),
    )
}
