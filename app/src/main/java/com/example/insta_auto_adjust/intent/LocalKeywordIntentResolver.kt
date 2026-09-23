package com.example.insta_auto_adjust.intent

import com.example.insta_auto_adjust.presentation.ShootingIntent
import com.example.insta_auto_adjust.presentation.UserIntentUi

/**
 * B 层的本地兜底意图解析器。
 *
 * 作用：
 *
 * 用户自然语言 / 快捷意图
 *          ↓
 *     UserIntentUi
 *
 * 注意：
 * 这里不决定 EV +1 / EV -1 / HOLD。
 *
 * EV 动作应该由 C 的 PolicyEngine 决定。
 */
object LocalKeywordIntentResolver {

    fun resolve(
        rawText: String,
        selectedIntent: ShootingIntent
    ): UserIntentUi {

        // --------------------------------------------------------
        // 1. 先根据快捷意图建立基础权重
        // --------------------------------------------------------

        var subjectPriority: Double
        var highlightProtection: Double
        var stabilityPreference: Double

        when (selectedIntent) {

            ShootingIntent.SUBJECT_PRIORITY -> {
                subjectPriority = 0.9
                highlightProtection = 0.4
                stabilityPreference = 0.4
            }

            ShootingIntent.BALANCED -> {
                subjectPriority = 0.6
                highlightProtection = 0.6
                stabilityPreference = 0.5
            }

            ShootingIntent.HIGHLIGHT_PRIORITY -> {
                subjectPriority = 0.4
                highlightProtection = 0.9
                stabilityPreference = 0.5
            }

            ShootingIntent.STABLE_EXPOSURE -> {
                subjectPriority = 0.5
                highlightProtection = 0.6
                stabilityPreference = 0.9
            }
        }


        // --------------------------------------------------------
        // 2. 根据用户自然语言做简单关键词修正
        //
        // 当前只是本地兜底解析器。
        // 后续 D 的语义模型可以替换/增强这一层。
        // --------------------------------------------------------

        val normalizedText = rawText.trim().lowercase()


        // 用户强调主体 / 人物
        if (
            normalizedText.contains("人物") ||
            normalizedText.contains("人脸") ||
            normalizedText.contains("主体") ||
            normalizedText.contains("脸")
        ) {
            subjectPriority = maxOf(
                subjectPriority,
                0.8
            )
        }


        // 用户明确说主体偏暗
        if (
            normalizedText.contains("人物太暗") ||
            normalizedText.contains("脸太暗") ||
            normalizedText.contains("主体太暗") ||
            normalizedText.contains("人物暗") ||
            normalizedText.contains("主体暗")
        ) {
            subjectPriority = maxOf(
                subjectPriority,
                0.9
            )
        }


        // 用户强调高光保护 / 不要过曝
        if (
            normalizedText.contains("过曝") ||
            normalizedText.contains("高光") ||
            normalizedText.contains("天空") ||
            normalizedText.contains("不要太亮")
        ) {
            highlightProtection = maxOf(
                highlightProtection,
                0.9
            )
        }


        // 用户强调稳定
        if (
            normalizedText.contains("稳定") ||
            normalizedText.contains("不要跳") ||
            normalizedText.contains("不要频繁") ||
            normalizedText.contains("保持曝光")
        ) {
            stabilityPreference = maxOf(
                stabilityPreference,
                0.9
            )
        }


        // --------------------------------------------------------
        // 3. 返回结构化 UserIntentUi
        // --------------------------------------------------------

        return UserIntentUi(
            subjectPriority = subjectPriority,
            highlightProtection = highlightProtection,
            stabilityPreference = stabilityPreference,

            // 当前 MVP 只允许 EV
            allowedAdjustments = setOf("EV"),

            rawText = rawText,

            source =
                if (rawText.isBlank()) {
                    "QUICK_PRESET"
                } else {
                    "LOCAL_KEYWORD"
                }
        )
    }
}