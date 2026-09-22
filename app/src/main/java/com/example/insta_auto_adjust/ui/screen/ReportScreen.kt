package com.example.insta_auto_adjust.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.insta_auto_adjust.presentation.ReportUiState

@Composable
fun ReportScreen(
    reportState: ReportUiState,
    onFinishClick: () -> Unit,
    modifier: Modifier = Modifier
) {

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {

        Spacer(modifier = Modifier.height(24.dp))

        // ---------------------------------------------------------
        // 页面标题
        // ---------------------------------------------------------

        Text(
            text = "LightPilot",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "拍摄报告",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (reportState.isMock) {
                "TEST / MOCK"
            } else {
                "REAL"
            },
            style = MaterialTheme.typography.labelMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ---------------------------------------------------------
        // 用户意图
        // ---------------------------------------------------------

        Text(
            text = "拍摄意图",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(16.dp)
            ) {

                Text(
                    text = reportState.intentText
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---------------------------------------------------------
        // Proposal
        // ---------------------------------------------------------

        Text(
            text = "本次建议",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(16.dp)
            ) {

                Text(
                    text = "Proposal：${reportState.proposalId}"
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Before EV：${formatEv(reportState.beforeEv)}"
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Target EV：${formatEv(reportState.targetEv)}"
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---------------------------------------------------------
        // 执行证据
        // ---------------------------------------------------------

        Text(
            text = "执行证据",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(16.dp)
            ) {

                Text(
                    text = when (reportState.sdkAck) {
                        true -> "SDK ACK：SUCCESS"
                        false -> "SDK ACK：FAILED"
                        null -> "SDK ACK：UNKNOWN"
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Readback EV：${formatEv(reportState.readbackEv)}"
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---------------------------------------------------------
        // 效果观察
        // ---------------------------------------------------------

        Text(
            text = "效果观察",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(16.dp)
            ) {

                if (reportState.effectObservation != null) {

                    Text(
                        text = reportState.effectObservation
                    )

                } else {

                    Text(
                        text = "尚未进行拍后效果验证。"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "参数回读成功不等于画面质量已经改善。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---------------------------------------------------------
        // Mock 提示
        // ---------------------------------------------------------

        if (reportState.isMock) {

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {

                Column(
                    modifier = Modifier.padding(16.dp)
                ) {

                    Text(
                        text = "TEST / MOCK"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "本报告中的执行数据来自 Mock 流程，不代表真实相机已经完成参数修改。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // ---------------------------------------------------------
        // 完成本次流程
        // ---------------------------------------------------------

        Button(
            onClick = onFinishClick,
            modifier = Modifier.fillMaxWidth()
        ) {

            Text(
                text = "完成本次流程"
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private fun formatEv(
    value: Double?
): String {

    if (value == null) {
        return "--"
    }

    return if (value > 0) {
        "+$value"
    } else {
        value.toString()
    }
}