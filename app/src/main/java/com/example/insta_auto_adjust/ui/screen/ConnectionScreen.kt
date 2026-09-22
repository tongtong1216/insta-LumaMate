package com.example.insta_auto_adjust.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.insta_auto_adjust.presentation.CameraUiState
import com.example.insta_auto_adjust.presentation.ConnectionStatus
import com.example.insta_auto_adjust.ui.components.BrandHeader
import com.example.insta_auto_adjust.ui.components.DataStrip
import com.example.insta_auto_adjust.ui.components.DataValue
import com.example.insta_auto_adjust.ui.components.PrimaryAction
import com.example.insta_auto_adjust.ui.components.WhiteSheet
import com.example.insta_auto_adjust.ui.theme.PilotWhite

@Composable
fun ConnectionScreen(
    cameraState: CameraUiState,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) {
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

        WhiteSheet {
            DataStrip {
                DataValue("拍摄模式", cameraState.mode ?: "--")
                DataValue("当前 EV", formatEv(cameraState.currentEv))
                DataValue("连接来源", cameraState.dataSource.name)
            }
            Text(
                text = "支持 EV：${cameraState.supportedEv.takeIf { it.isNotEmpty() }?.joinToString() ?: "--"}",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall
            )
            cameraState.errorMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(22.dp))
            PrimaryAction(
                text = when (cameraState.connectionStatus) {
                    ConnectionStatus.DISCONNECTED -> "连接相机"
                    ConnectionStatus.CONNECTING -> "模拟连接成功"
                    ConnectionStatus.CONNECTED -> "进入拍摄助手"
                    ConnectionStatus.ERROR -> "重新连接"
                },
                onClick = onConnectClick
            )
        }
    }
}

private fun connectionTitle(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.DISCONNECTED -> "等待连接"
    ConnectionStatus.CONNECTING -> "正在连接"
    ConnectionStatus.CONNECTED -> "相机已连接"
    ConnectionStatus.ERROR -> "连接失败"
}

private fun connectionDescription(cameraState: CameraUiState): String = when (cameraState.connectionStatus) {
    ConnectionStatus.DISCONNECTED -> "连接设备后开始拍摄分析"
    ConnectionStatus.CONNECTING -> "正在建立相机连接"
    ConnectionStatus.CONNECTED -> "设备已就绪，可以开始拍摄"
    ConnectionStatus.ERROR -> cameraState.errorMessage ?: "请重新尝试连接"
}

private fun formatEv(value: Double?): String = when {
    value == null -> "--"
    value > 0 -> "+$value"
    else -> value.toString()
}
