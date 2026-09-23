package com.example.insta_auto_adjust.presentation

enum class ExecutionStatus {
    IDLE,
    EXECUTING,
    SUCCESS,
    FAILED,
    UNKNOWN
}

data class ExecutionUiState(

    // 对应哪一个 Proposal
    val proposalId: String? = null,

    // 执行前实际 EV
    val beforeEv: Double? = null,

    // Proposal 希望调整到的目标 EV
    val targetEv: Double? = null,

    // SDK 是否返回 ACK
    val sdkAck: Boolean? = null,

    // 执行后从相机重新读取到的实际 EV
    val readbackEv: Double? = null,

    // 当前执行状态
    val status: ExecutionStatus = ExecutionStatus.IDLE,

    // 错误信息
    val errorMessage: String? = null,

    // 当前数据是否为 Mock
    val isMock: Boolean = false
)
