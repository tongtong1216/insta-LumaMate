package com.example.insta_auto_adjust.presentation


enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class DataSource {
    MOCK,
    REAL
}

data class CameraUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val mode: String? = null,
    val currentEv: Double? = null,
    val supportedEv: List<Double> = emptyList(),
    val dataSource: DataSource = DataSource.MOCK,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val scannedDevices: List<CameraDeviceUi> = emptyList()
)

data class CameraDeviceUi(
    val name: String,
    val address: String
)
