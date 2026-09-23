package com.example.insta_auto_adjust.presentation

/**
 * B 层使用的结构化用户拍摄意图。
 *
 * 这是 UI / Presentation 层 DTO。
 *
 * 后续真正与 C 对接时，
 * 再将 UserIntentUi 映射为 C 定义的 UserIntent。
 */
data class UserIntentUi(

    /**
     * 用户有多重视主体曝光。
     *
     * 0.0 = 不强调
     * 1.0 = 非常强调
     */
    val subjectPriority: Double = 0.5,

    /**
     * 用户有多重视高光保护。
     *
     * 0.0 = 不强调
     * 1.0 = 非常强调
     */
    val highlightProtection: Double = 0.5,

    /**
     * 用户有多重视曝光稳定。
     *
     * 0.0 = 更允许调整
     * 1.0 = 尽量保持稳定
     */
    val stabilityPreference: Double = 0.5,

    /**
     * 当前允许策略层建议调整的参数。
     *
     * MVP 阶段只允许 EV。
     */
    val allowedAdjustments: Set<String> = setOf("EV"),

    /**
     * 原始用户输入。
     *
     * 方便调试和比赛演示时追踪：
     * “用户说了什么”。
     */
    val rawText: String = "",

    /**
     * 当前意图来自哪里。
     *
     * QUICK_PRESET
     * LOCAL_KEYWORD
     *
     * 后续如果 D 的云端语义模型接入，
     * 可以增加 CLOUD_SEMANTIC。
     */
    val source: String = "QUICK_PRESET"
)