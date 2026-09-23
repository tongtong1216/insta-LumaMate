package com.example.insta_auto_adjust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.insta_auto_adjust.presentation.ExecutionStatus
import com.example.insta_auto_adjust.presentation.ExecutionUiState
import com.example.insta_auto_adjust.ui.components.BrandHeader
import com.example.insta_auto_adjust.ui.components.BottomAnchoredPage
import com.example.insta_auto_adjust.ui.components.DataStrip
import com.example.insta_auto_adjust.ui.components.DataValue
import com.example.insta_auto_adjust.ui.components.PrimaryAction
import com.example.insta_auto_adjust.ui.components.SectionEyebrow
import com.example.insta_auto_adjust.ui.theme.PilotGray
import com.example.insta_auto_adjust.ui.theme.PilotGreen
import com.example.insta_auto_adjust.ui.theme.PilotWhite
import com.example.insta_auto_adjust.ui.theme.PilotYellow

@Composable
fun ExecutionScreen(
    executionState: ExecutionUiState,
    onExecuteClick: () -> Unit,
    onReportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BottomAnchoredPage(
        modifier = modifier,
        topContent = {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) {
            BrandHeader(subtitle = "执行反馈")
            Spacer(Modifier.height(42.dp))
            ExecutionSteps(executionState)
            Spacer(Modifier.height(44.dp))
            Text(
                text = statusTitle(executionState.status),
                style = MaterialTheme.typography.bodyMedium,
                color = PilotWhite.copy(alpha = 0.62f)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatEv(executionState.beforeEv),
                    style = MaterialTheme.typography.headlineLarge,
                    color = PilotWhite,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "  →  ",
                    style = MaterialTheme.typography.titleLarge,
                    color = PilotWhite.copy(alpha = 0.5f)
                )
                Text(
                    formatEv(executionState.targetEv),
                    style = MaterialTheme.typography.headlineLarge,
                    color = PilotWhite,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "EV",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = PilotWhite.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(34.dp))
            }
        },
        sheetContent = {
            SectionEyebrow("相机反馈  ·  ACK / READBACK")
            Spacer(Modifier.height(12.dp))
            DataStrip {
                DataValue("SDK ACK", ackText(executionState.sdkAck))
                DataValue("实际回读", formatEv(executionState.readbackEv))
            }

            Text(
                text = "Proposal ${executionState.proposalId ?: "--"}",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = PilotGray
            )

            executionState.errorMessage?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (executionState.status == ExecutionStatus.SUCCESS) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "参数执行与回读完成",
                    style = MaterialTheme.typography.titleMedium,
                    color = PilotGreen
                )
                Text(
                    text = "当前结果来自 TEST / MOCK，不代表真实相机已经修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PilotGray
                )
            }

            Spacer(Modifier.height(22.dp))
            when (executionState.status) {
                ExecutionStatus.IDLE -> PrimaryAction("执行调整", onExecuteClick)
                ExecutionStatus.SUCCESS -> PrimaryAction("查看拍摄报告", onReportClick)
                ExecutionStatus.FAILED -> Unit
                ExecutionStatus.EXECUTING -> PrimaryAction("正在执行...", {}, enabled = false)
                ExecutionStatus.UNKNOWN -> PrimaryAction("等待状态确认", {}, enabled = false)
            }
        }
    )
}

@Composable
private fun ExecutionSteps(state: ExecutionUiState) {
    val activeCount = when (state.status) {
        ExecutionStatus.IDLE -> 1
        ExecutionStatus.EXECUTING -> 2
        ExecutionStatus.SUCCESS -> 4
        ExecutionStatus.FAILED -> if (state.sdkAck == null) 2 else 3
        ExecutionStatus.UNKNOWN -> 1
    }
    val labels = listOf("分析", "下发", "ACK", "回读")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.height(24.dp).fillMaxWidth(0.16f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.background(
                            if (index < activeCount) PilotYellow else PilotWhite.copy(alpha = 0.28f),
                            CircleShape
                        ).padding(5.dp)
                    )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index < activeCount) PilotWhite else PilotWhite.copy(alpha = 0.45f)
                )
            }
        }
    }
}

private fun statusTitle(status: ExecutionStatus): String = when (status) {
    ExecutionStatus.IDLE -> "建议已确认，等待执行"
    ExecutionStatus.EXECUTING -> "正在执行参数调整"
    ExecutionStatus.SUCCESS -> "执行与回读已完成"
    ExecutionStatus.FAILED -> "参数执行失败"
    ExecutionStatus.UNKNOWN -> "等待相机状态"
}

private fun ackText(value: Boolean?): String = when (value) {
    true -> "SUCCESS"
    false -> "FAILED"
    null -> "--"
}

private fun formatEv(value: Double?): String = when {
    value == null -> "--"
    value > 0 -> "+$value"
    else -> value.toString()
}
