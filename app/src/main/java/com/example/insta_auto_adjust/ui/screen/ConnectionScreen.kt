package com.example.insta_auto_adjust.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.insta_auto_adjust.presentation.CameraUiState
import com.example.insta_auto_adjust.presentation.ConnectionStatus
import com.example.insta_auto_adjust.presentation.DataSource
import com.example.insta_auto_adjust.ui.components.BrandHeader
import com.example.insta_auto_adjust.ui.components.BottomAnchoredPage
import com.example.insta_auto_adjust.ui.components.DataStrip
import com.example.insta_auto_adjust.ui.components.DataValue
import com.example.insta_auto_adjust.ui.components.PrimaryAction
import com.example.insta_auto_adjust.ui.theme.PilotWhite

@Composable
fun ConnectionScreen(
    cameraState: CameraUiState,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BottomAnchoredPage(
        modifier = modifier,
        topContent = {
            Column(
                modifier = Modifier.padding(
                    horizontal = 24.dp,
                    vertical = 28.dp
                )
            ) {
                BrandHeader(subtitle = "AI 相机拍摄助手")

                Spacer(Modifier.height(58.dp))

                Text(
                    text = "相机状态",
                    style = MaterialTheme.typography.labelMedium,
                    color = PilotWhite.copy(alpha = 0.55f)
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = connectionTitle(cameraState.connectionStatus),
                    style = MaterialTheme.typography.headlineLarge,
                    color = PilotWhite
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = connectionDescription(cameraState),
                    style = MaterialTheme.typography.bodyLarge,
                    color = PilotWhite.copy(alpha = 0.62f)
                )

                Spacer(Modifier.height(46.dp))

                Text(
                    text = "当前 EV",
                    style = MaterialTheme.typography.labelMedium,
                    color = PilotWhite.copy(alpha = 0.55f)
                )

                Text(
                    text = formatEv(cameraState.currentEv),
                    style = MaterialTheme.typography.headlineLarge,
                    color = PilotWhite,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        sheetContent = {
            DataStrip {
                DataValue(
                    "设备",
                    cameraState.connectedCameraName ?: "--"
                )

                DataValue(
                    "拍摄模式",
                    cameraState.mode ?: "--"
                )

                DataValue(
                    "当前 EV",
                    formatEv(cameraState.currentEv)
                )
            }

            Text(
                text = "数据来源：${dataSourceLabel(cameraState.dataSource)}",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "支持 EV：${
                    cameraState.supportedEv
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString()
                        ?: "--"
                }",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall
            )

            cameraState.errorMessage?.let {
                Spacer(Modifier.height(16.dp))

                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(22.dp))

            PrimaryAction(
                text = when (cameraState.connectionStatus) {
                    ConnectionStatus.DISCONNECTED -> "连接相机"
                    ConnectionStatus.CONNECTING -> "正在连接…"
                    ConnectionStatus.CONNECTED -> "进入拍摄助手"
                    ConnectionStatus.ERROR -> "重新连接"
                },
                onClick = onConnectClick,
                enabled = cameraState.connectionStatus != ConnectionStatus.CONNECTING
            )
        }
    )
}

private fun connectionTitle(
    status: ConnectionStatus
): String = when (status) {
    ConnectionStatus.DISCONNECTED -> "等待连接"
    ConnectionStatus.CONNECTING -> "正在连接"
    ConnectionStatus.CONNECTED -> "相机已连接"
    ConnectionStatus.ERROR -> "连接失败"
}

private fun connectionDescription(
    cameraState: CameraUiState
): String = when (cameraState.connectionStatus) {
    ConnectionStatus.DISCONNECTED ->
        "连接设备后开始拍摄分析"

    ConnectionStatus.CONNECTING ->
        "正在建立真实相机连接，请稍候"

    ConnectionStatus.CONNECTED ->
        cameraState.connectedCameraName?.let {
            "$it 已连接，可以开始拍摄"
        } ?: "设备已连接，可以开始拍摄"

    ConnectionStatus.ERROR ->
        cameraState.errorMessage ?: "请重新尝试连接"
}

private fun dataSourceLabel(
    dataSource: DataSource
): String = when (dataSource) {
    DataSource.UNAVAILABLE -> "等待真实相机数据"
    DataSource.REAL -> "真实相机"
}

private fun formatEv(value: Double?): String = when {
    value == null -> "--"
    value > 0 -> "+$value"
    else -> value.toString()
}