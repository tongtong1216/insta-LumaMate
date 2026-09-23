package com.example.insta_auto_adjust.presentation

data class ReportUiState(

    // 本次报告对应的 Proposal
    val proposalId: String = "",

    // 用户拍摄意图
    val intentText: String = "",

    // 调整前 EV
    val beforeEv: Double? = null,

    // 建议目标 EV
    val targetEv: Double? = null,

    // SDK 是否确认执行
    val sdkAck: Boolean? = null,

    // 执行后的实际回读
    val readbackEv: Double? = null,

    // 本次流程是否来自 Mock
    val isMock: Boolean = true,

    // 效果观察
    //
    // 注意：
    // 这里目前不能写“画质提升 xx%”
    // 因为我们没有真实的拍后验证结果
    val effectObservation: String? = null
)