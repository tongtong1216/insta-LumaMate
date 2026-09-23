package com.example.insta_auto_adjust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import com.example.insta_auto_adjust.presentation.ReportUiState
import com.example.insta_auto_adjust.ui.components.NavigableBrandHeader
import com.example.insta_auto_adjust.ui.components.BottomAnchoredPage
import com.example.insta_auto_adjust.ui.components.DataStrip
import com.example.insta_auto_adjust.ui.components.DataValue
import com.example.insta_auto_adjust.ui.components.PrimaryAction
import com.example.insta_auto_adjust.ui.components.SectionEyebrow
import com.example.insta_auto_adjust.ui.theme.PilotGray
import com.example.insta_auto_adjust.ui.theme.PilotGreen
import com.example.insta_auto_adjust.ui.theme.PilotInk
import com.example.insta_auto_adjust.ui.theme.PilotWhite

@Composable
fun ReportScreen(
    reportState: ReportUiState,
    onFinishClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BottomAnchoredPage(
        modifier = modifier,
        topContent = {

            NavigableBrandHeader(
                subtitle = "拍摄报告",
                onBackClick = onBackClick,
                modifier = Modifier.padding(
                    horizontal = 24.dp,
                    vertical = 20.dp
                )
            )
        },
        sheetContent = {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "✓",
                    modifier = Modifier.background(PilotGreen, CircleShape).padding(horizontal = 15.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = PilotWhite,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                Text("流程完成", style = MaterialTheme.typography.headlineMedium, color = PilotInk)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = reportState.intentText.ifBlank { "本次拍摄流程已完成" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PilotGray,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(28.dp))
            SectionEyebrow("参数记录")
            Spacer(Modifier.height(8.dp))
            DataStrip {
                DataValue("调整前", formatEv(reportState.beforeEv))
                DataValue("目标", formatEv(reportState.targetEv))
                DataValue("实际回读", formatEv(reportState.readbackEv))
            }

            Spacer(Modifier.height(20.dp))
            SectionEyebrow("执行证据")
            Spacer(Modifier.height(6.dp))
            Text(
                text = "SDK ACK ${ackText(reportState.sdkAck)}  ·  Proposal ${reportState.proposalId.ifBlank { "--" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = PilotInk
            )
            Text(
                text = reportState.effectObservation ?: "尚未进行拍后效果验证。",
                style = MaterialTheme.typography.bodySmall,
                color = PilotGray,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (reportState.isMock) {
                Spacer(Modifier.height(24.dp))
                MockBadgeOnWhite()
                Text(
                    text = "本报告来自模拟流程，不代表真实相机已经完成参数修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PilotGray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            PrimaryAction("完成并返回", onFinishClick)
        }
    )
}

@Composable
private fun MockBadgeOnWhite() {
    Text(
        text = "TEST / MOCK",
        style = MaterialTheme.typography.labelMedium,
        color = PilotGray
    )
}

private fun ackText(value: Boolean?): String = when (value) {
    true -> "SUCCESS"
    false -> "FAILED"
    null -> "UNKNOWN"
}

private fun formatEv(value: Double?): String = when {
    value == null -> "--"
    value > 0 -> "+$value"
    else -> value.toString()
}
