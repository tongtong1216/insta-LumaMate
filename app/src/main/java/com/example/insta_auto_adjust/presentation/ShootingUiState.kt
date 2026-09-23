package com.example.insta_auto_adjust.presentation

/**
 * 用户在 B/UI 层选择的快捷拍摄意图。
 *
 * 后续会转换成 UserIntentUi，
 * 再作为 B -> C 的输入。
 */
enum class ShootingIntent {
    SUBJECT_PRIORITY,
    BALANCED,
    HIGHLIGHT_PRIORITY,
    STABLE_EXPOSURE
}


/**
 * 用户对于当前 Proposal 的决定。
 *
 * PENDING:
 * 已经生成建议，等待用户决定。
 *
 * ACCEPTED:
 * 用户接受建议。
 *
 * HELD:
 * 用户选择保持当前参数，不执行建议。
 */
enum class ProposalDecision {
    PENDING,
    ACCEPTED,
    HELD
}


/**
 * 拍摄助手页面的 UI 状态。
 *
 * 注意：
 * 这是 B 的 presentation 层状态，
 * 不是 C 的 PolicyEngine 数据结构。
 */
data class ShootingUiState(
    val dataSource: DataSource = DataSource.MOCK,

    // ------------------------------------------------------------
    // B：快捷拍摄意图
    // ------------------------------------------------------------

    val selectedIntent: ShootingIntent = ShootingIntent.BALANCED,


    // ------------------------------------------------------------
    // B：用户自然语言输入
    //
    // 例如：
    // "人物脸太暗了"
    // "不要让天空过曝"
    // ------------------------------------------------------------

    val intentInputText: String = "",


    // ------------------------------------------------------------
    // B -> C：
    // 已经结构化后的 UserIntent
    //
    // null 表示当前还没有完成意图解析。
    // ------------------------------------------------------------

    val userIntent: UserIntentUi? = null,


    // ------------------------------------------------------------
    // B -> C：
    // 用户是否明确要求保持当前参数
    //
    // true 时，后续 C 应返回 HOLD。
    // ------------------------------------------------------------

    val userLocked: Boolean = false,


    // ------------------------------------------------------------
    // B：
    // 当前是否正在分析
    // ------------------------------------------------------------

    val isAnalyzing: Boolean = false,


    // ------------------------------------------------------------
    // C -> B：
    // 视觉分析结果
    // ------------------------------------------------------------

    val visionMetrics: VisionMetricsUi? = null,


    // ------------------------------------------------------------
    // UI 展示：
    // 当前场景风险
    // ------------------------------------------------------------

    val sceneRisk: String? = null,


    // ------------------------------------------------------------
    // C -> B：
    // 策略建议
    // ------------------------------------------------------------

    val proposal: PolicyProposalUi? = null,

    // 当前建议是否满足真实执行条件。
    // 没有实时相机帧时，建议仍可展示，但不能进入真实执行。
    val proposalExecutable: Boolean = false,

    // 建议暂时不能执行时，向用户说明阻塞原因。
    val proposalBlockReason: String? = null,


    // ------------------------------------------------------------
    // B：
    // 用户对 Proposal 的决定
    //
    // null:
    // 当前还没有 Proposal / 没有进入确认阶段。
    // ------------------------------------------------------------

    val proposalDecision: ProposalDecision? = null
)
