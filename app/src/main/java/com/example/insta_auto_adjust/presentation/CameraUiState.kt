package com.example.insta_auto_adjust.presentation

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * B 层只展示数据来源，不自行决定真实相机状态。
 *
 * REAL:
 * 数据来自 A 的真实 CameraSnapshot。
 *
 * UNAVAILABLE:
 * 当前还没有可用的真实相机数据，例如未连接、刚启动或读取失败。
 */
enum class DataSource {
    UNAVAILABLE,
    REAL
}

/**
 * B 层用于 Compose 展示的相机状态。
 *
 * 真实接入后，由 A 提供的连接状态和 CameraSnapshot 映射到这里。
 * UI 不直接持有 CameraDevice，也不自行修改 frameSource。
 */
data class CameraUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,

    val connectedCameraName: String? = null,

    val mode: String? = null,

    val currentEv: Double? = null,

    val supportedEv: List<Double> = emptyList(),

    val frameSource: String? = null,

    val dataSource: DataSource = DataSource.UNAVAILABLE,

    val errorMessage: String? = null
) {
    /**
     * 只有真实连接成功后，B 才允许进入依赖相机的正式流程。
     */
    val isRealCameraConnected: Boolean
        get() = connectionStatus == ConnectionStatus.CONNECTED &&
                dataSource == DataSource.REAL
}