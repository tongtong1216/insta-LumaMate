package com.example.insta_auto_adjust.lightpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedIntentMapperTest {
    @Test
    fun selectsMotionAndColorStages() {
        val motion = AdvancedIntentMapper.suggest("宁愿暗一点也不要拖影，动作要清楚")
        assertEquals(AdvancedStage.MOTION_NOISE, motion.stage)
        assertEquals(MotionPriority.MOTION_CLARITY, motion.motionPriority)

        val color = AdvancedIntentMapper.suggest("请保留舞台灯和彩色光")
        assertEquals(AdvancedStage.COLOR_ATMOSPHERE, color.stage)
        assertEquals(ColorPriority.COLORED_LIGHT_PRESERVATION, color.colorPriority)
    }

    @Test
    fun conflictOrNoMatchRequiresManualStageConfirmation() {
        val conflict = AdvancedIntentMapper.suggest("动作清晰，同时保留暖色氛围")
        assertNull(conflict.stage)
        val unknown = AdvancedIntentMapper.suggest("随便拍一下")
        assertNull(unknown.stage)
    }
}
