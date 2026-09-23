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
import com.example.insta_auto_adjust.presentation.ExecutionStatus
import com.example.insta_auto_adjust.presentation.ExecutionUiState

@Composable
fun ExecutionScreen(
    executionState: ExecutionUiState,
    onExecuteClick: () -> Unit,
    onReportClick: () -> Unit,
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
            text = "执行反馈",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (executionState.isMock) "TEST / MOCK" else "REAL CAMERA / SDK",
            style = MaterialTheme.typography.labelMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ---------------------------------------------------------
        // 待执行建议
        // ---------------------------------------------------------

        Text(
            text = "待执行建议",
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
                    text = "Proposal：${executionState.proposalId}"
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "执行前 EV：${formatEv(executionState.beforeEv)}"
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "目标 EV：${formatEv(executionState.targetEv)}"
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---------------------------------------------------------
        // WAITING
        //
        // 用户已经接受 Proposal，
        // 但还没有真正进入参数执行。
        // ---------------------------------------------------------

        if (executionState.status == ExecutionStatus.IDLE) {

            Text(
                text = "用户已接受建议，但相机参数尚未执行。",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onExecuteClick,
                modifier = Modifier.fillMaxWidth()
            ) {

                Text(
                    text = "执行调整"
                )
            }
        }

        // ---------------------------------------------------------
        // EXECUTING
        //
        // 当前正在等待 SDK 执行结果。
        // ---------------------------------------------------------

        if (executionState.status == ExecutionStatus.EXECUTING) {

            Text(
                text = "正在执行参数调整...",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "正在等待 SDK ACK 与最新参数回读。",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ---------------------------------------------------------
        // 如果已经有 ACK，则显示 SDK ACK
        // ---------------------------------------------------------

        executionState.sdkAck?.let { ack ->

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "SDK ACK",
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
                        text = if (ack) {
                            "ACK：SUCCESS"
                        } else {
                            "ACK：FAILED"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // ---------------------------------------------------------
        // 如果已经有实际回读，则显示 Readback
        // ---------------------------------------------------------

        executionState.readbackEv?.let { readback ->

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "实际回读",
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
                        text = "Readback EV：${formatEv(readback)}",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "该数值表示执行后重新读取到的相机参数。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ---------------------------------------------------------
        // SUCCESS
        //
        // 注意：
        // 成功这里只代表：
        //
        // 执行 + ACK + Readback 已完成
        //
        // 不代表画面质量一定改善。
        // ---------------------------------------------------------

        if (executionState.status == ExecutionStatus.SUCCESS) {

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "执行结果",
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
                        text = "✓ 参数执行与回读完成",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Before：${formatEv(executionState.beforeEv)}"
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Target：${formatEv(executionState.targetEv)}"
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Readback：${formatEv(executionState.readbackEv)}"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (executionState.isMock) {
                        Text(
                            text = "当前结果来自 TEST / MOCK 流程，不代表真实相机已经修改。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            text = "真实 SDK ACK 与相机参数回读均已完成。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // -----------------------------------------------------
            // 新增：
            // 执行与回读完成以后，可以进入第 4 页拍摄报告。
            // -----------------------------------------------------

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onReportClick,
                modifier = Modifier.fillMaxWidth()
            ) {

                Text(
                    text = "查看拍摄报告"
                )
            }
        }

        // ---------------------------------------------------------
        // FAILED
        // ---------------------------------------------------------

        if (executionState.status == ExecutionStatus.FAILED) {

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "执行失败",
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
                        text = "参数执行未成功完成。"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "不要把目标值当成真实相机回读值。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * EV 显示格式。
 *
 * 例如：
 *
 *  1.0  -> +1.0
 *  0.0  -> 0.0
 * -1.0  -> -1.0
 * null  -> --
 */
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
