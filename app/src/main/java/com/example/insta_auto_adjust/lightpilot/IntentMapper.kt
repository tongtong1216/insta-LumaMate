package com.example.insta_auto_adjust.lightpilot

object IntentMapper {
    private val subjectWords = listOf("人物", "主体", "人脸", "拍清楚")
    private val highlightWords = listOf("屏幕", "灯光", "天空", "高光", "不过曝", "保留氛围")
    private val stabilityWords = listOf("稳定", "不要跳", "不要频繁变化")

    fun suggest(text: String): Pair<ExposurePriority, StabilityPreference> {
        val subject = subjectWords.any(text::contains)
        val highlight = highlightWords.any(text::contains)
        val priority = when {
            subject && !highlight -> ExposurePriority.SUBJECT_DETAIL
            highlight && !subject -> ExposurePriority.HIGHLIGHT_DETAIL
            else -> ExposurePriority.BALANCED
        }
        val stability = if (stabilityWords.any(text::contains)) {
            StabilityPreference.HIGH
        } else {
            StabilityPreference.NORMAL
        }
        return priority to stability
    }
}
