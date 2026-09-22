package com.example.insta_auto_adjust.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.insta_auto_adjust.presentation.CameraUiState
import com.example.insta_auto_adjust.presentation.ConnectionStatus

@Composable
fun ConnectionScreen(
    cameraState: CameraUiState,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "LightPilot",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "意图驱动的 AI 智能拍摄助手",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "相机状态",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                when (cameraState.connectionStatus) {
                    ConnectionStatus.DISCONNECTED -> {
                        Text("● 未连接")
                    }

                    ConnectionStatus.CONNECTING -> {
                        Text("● 正在连接...")
                    }

                    ConnectionStatus.CONNECTED -> {
                        Text("● 已连接")

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("模式：${cameraState.mode ?: "--"}")

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("当前 EV：${cameraState.currentEv ?: "--"}")

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "支持 EV：${
                                if (cameraState.supportedEv.isEmpty()) {
                                    "--"
                                } else {
                                    cameraState.supportedEv.joinToString()
                                }
                            }"
                        )
                    }

                    ConnectionStatus.ERROR -> {
                        Text("● 连接失败")

                        cameraState.errorMessage?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("错误：$it")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("数据来源：${cameraState.dataSource}")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onConnectClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = when (cameraState.connectionStatus) {
                    ConnectionStatus.DISCONNECTED -> "连接相机"
                    ConnectionStatus.CONNECTING -> "模拟连接成功"
                    ConnectionStatus.CONNECTED -> "进入拍摄助手"
                    ConnectionStatus.ERROR -> "重新连接"
                }
            )
        }
    }
}
