package com.example.insta_auto_adjust.presentation

enum class ShootingIntent {
    SUBJECT_PRIORITY,
    BALANCED,
    HIGHLIGHT_PRIORITY
}

/**
 * 用户对当前 Proposal 的处理状态。
 *
 * PENDING  = 已经收到建议，但用户还没有决定
 * ACCEPTED = 用户接受建议
 * HELD     = 用户选择保持当前参数
 */
enum class ProposalDecision {
    PENDING,
    ACCEPTED,
    HELD
}

data class ShootingUiState(

    // 用户选择的拍摄意图
    val selectedIntent: ShootingIntent = ShootingIntent.BALANCED,

    // 是否正在分析当前画面
    val isAnalyzing: Boolean = false,

    // C 的视觉分析结果
    // null 表示目前还没有分析结果
    val visionMetrics: VisionMetricsUi? = null,

    // 当前场景风险描述
    // null 表示目前还没有风险分析结果
    val sceneRisk: String? = null,

    // C 给出的策略建议
    // null 表示目前还没有 Proposal
    val proposal: PolicyProposalUi? = null,

    // 用户是否已经处理当前 Proposal
    // null = 当前还没有 Proposal
    // PENDING = 有 Proposal，但用户还没决定
    // ACCEPTED = 用户接受建议
    // HELD = 用户选择保持当前
    val proposalDecision: ProposalDecision? = null
)