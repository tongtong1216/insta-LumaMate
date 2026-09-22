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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.insta_auto_adjust.presentation.ProposalDecision
import com.example.insta_auto_adjust.presentation.ShootingIntent
import com.example.insta_auto_adjust.presentation.ShootingUiState

@Composable
fun ShootingScreen(
    shootingState: ShootingUiState,
    onIntentSelected: (ShootingIntent) -> Unit,
    onAnalyzeClick: () -> Unit,

    // 用户接受当前建议
    onAcceptProposal: () -> Unit,

    // 用户拒绝修改，保持当前参数
    onHoldProposal: () -> Unit,

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

        // -------------------------
        // 页面标题
        // -------------------------

        Text(
            text = "LightPilot",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "拍摄助手",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Mock 必须明确标记
        Text(
            text = "TEST / MOCK",
            style = MaterialTheme.typography.labelMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        // -------------------------
        // 相机预览
        // -------------------------

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {

                Text(
                    text = "相机预览",
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "MOCK FRAME",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // -------------------------
        // 拍摄意图
        // -------------------------

        Text(
            text = "拍摄意图",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        IntentButton(
            text = "主体优先",
            selected =
                shootingState.selectedIntent ==
                        ShootingIntent.SUBJECT_PRIORITY,
            onClick = {
                onIntentSelected(
                    ShootingIntent.SUBJECT_PRIORITY
                )
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        IntentButton(
            text = "整体平衡",
            selected =
                shootingState.selectedIntent ==
                        ShootingIntent.BALANCED,
            onClick = {
                onIntentSelected(
                    ShootingIntent.BALANCED
                )
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        IntentButton(
            text = "高光优先",
            selected =
                shootingState.selectedIntent ==
                        ShootingIntent.HIGHLIGHT_PRIORITY,
            onClick = {
                onIntentSelected(
                    ShootingIntent.HIGHLIGHT_PRIORITY
                )
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text =
                "当前选择：${intentText(shootingState.selectedIntent)}"
        )

        Spacer(modifier = Modifier.height(20.dp))

        // -------------------------
        // 分析按钮
        // -------------------------

        Button(
            onClick = onAnalyzeClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !shootingState.isAnalyzing
        ) {

            Text(
                if (shootingState.isAnalyzing) {
                    "分析中..."
                } else {
                    "分析当前画面"
                }
            )
        }

        // -------------------------
        // VisionMetrics
        // -------------------------

        shootingState.visionMetrics?.let { metrics ->

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "画面分析",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {

                Column(
                    modifier = Modifier.padding(16.dp)
                ) {

                    Text(
                        text =
                            "主体亮度：${toPercent(metrics.subjectBrightness)}"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text =
                            "高光占比：${toPercent(metrics.highlightRatio)}"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text =
                            "暗部占比：${toPercent(metrics.darkRatio)}"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Frame：${metrics.frameId}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // -------------------------
        // 场景风险
        // -------------------------

        shootingState.sceneRisk?.let { risk ->

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "场景风险",
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
                        text = risk
                    )
                }
            }
        }

        // -------------------------
        // Policy Proposal
        // -------------------------

        shootingState.proposal?.let { proposal ->

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "调整建议",
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
                        text = proposal.action,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "原因："
                    )

                    Text(
                        text = proposal.reason
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "代价 / 风险："
                    )

                    Text(
                        text = proposal.cost
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text =
                            "Proposal：${proposal.proposalId}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // -------------------------
            // Proposal 与 Execution 明确分离
            // -------------------------

            Spacer(modifier = Modifier.height(16.dp))

            when (shootingState.proposalDecision) {

                ProposalDecision.PENDING,
                null -> {

                    Text(
                        text = "⚠ 当前内容仅为调整建议，尚未执行相机参数调整。",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onAcceptProposal,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("接受建议")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onHoldProposal,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保持当前")
                    }
                }

                ProposalDecision.ACCEPTED -> {

                    Text(
                        text = "✓ 已接受当前建议",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "等待进入执行阶段。当前尚未确认相机参数已经修改。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                ProposalDecision.HELD -> {

                    Text(
                        text = "已选择保持当前参数",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "本次建议不会进入参数调整流程。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}


@Composable
private fun IntentButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {

    if (selected) {

        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text)
        }

    } else {

        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text)
        }
    }
}


private fun intentText(
    intent: ShootingIntent
): String {

    return when (intent) {

        ShootingIntent.SUBJECT_PRIORITY ->
            "主体优先"

        ShootingIntent.BALANCED ->
            "整体平衡"

        ShootingIntent.HIGHLIGHT_PRIORITY ->
            "高光优先"
    }
}


private fun toPercent(
    value: Double
): String {

    return "${(value * 100).toInt()}%"
}