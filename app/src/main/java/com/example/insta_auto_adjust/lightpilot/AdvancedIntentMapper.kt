package com.example.insta_auto_adjust.lightpilot

data class AdvancedIntentSuggestion(
    val stage: AdvancedStage?,
    val motionPriority: MotionPriority,
    val colorPriority: ColorPriority,
)

/** Deterministic local preselection only. The user still confirms the structured result. */
object AdvancedIntentMapper {
    private val motionClarityWords = listOf("不要拖影", "动作清楚", "动作清晰", "冻结运动", "快门")
    private val lowNoiseWords = listOf("低噪点", "不要噪点", "少噪点", "画面干净")
    private val brightnessWords = listOf("亮一点", "保证亮度", "优先亮度")
    private val motionBalancedWords = listOf("运动平衡", "清晰又干净", "动作和噪点")

    private val colorAccuracyWords = listOf("真实颜色", "颜色准确", "还原颜色", "颜色还原")
    private val naturalSkinWords = listOf("肤色", "人物自然", "皮肤自然")
    private val atmosphereWords = listOf("保留氛围", "暖色氛围", "冷色氛围", "现场氛围")
    private val coloredLightWords = listOf("彩灯", "彩色光", "霓虹", "舞台灯")
    private val colorStabilityWords = listOf("颜色稳定", "白平衡稳定", "不要偏蓝", "不要偏黄")

    fun suggest(text: String): AdvancedIntentSuggestion {
        val motionHits = listOf(
            MotionPriority.MOTION_CLARITY to hits(text, motionClarityWords),
            MotionPriority.LOW_NOISE to hits(text, lowNoiseWords),
            MotionPriority.BRIGHTNESS_PRIORITY to hits(text, brightnessWords),
            MotionPriority.MOTION_BALANCED to hits(text, motionBalancedWords),
        ).filter { it.second }
        val colorHits = listOf(
            ColorPriority.COLOR_ACCURACY to hits(text, colorAccuracyWords),
            ColorPriority.NATURAL_SKIN to hits(text, naturalSkinWords),
            ColorPriority.ATMOSPHERE_PRESERVATION to hits(text, atmosphereWords),
            ColorPriority.COLORED_LIGHT_PRESERVATION to hits(text, coloredLightWords),
            ColorPriority.COLOR_STABILITY to hits(text, colorStabilityWords),
        ).filter { it.second }

        val motionPriority = if (motionHits.size == 1) motionHits.single().first
            else MotionPriority.MOTION_BALANCED
        val colorPriority = if (colorHits.size == 1) colorHits.single().first
            else ColorPriority.COLOR_STABILITY
        val stage = when {
            motionHits.isNotEmpty() && colorHits.isEmpty() -> AdvancedStage.MOTION_NOISE
            colorHits.isNotEmpty() && motionHits.isEmpty() -> AdvancedStage.COLOR_ATMOSPHERE
            else -> null
        }
        return AdvancedIntentSuggestion(stage, motionPriority, colorPriority)
    }

    private fun hits(text: String, words: List<String>) = words.any(text::contains)
}

