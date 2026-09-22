package com.example.insta_auto_adjust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.insta_auto_adjust.presentation.ProposalDecision
import com.example.insta_auto_adjust.presentation.ShootingIntent
import com.example.insta_auto_adjust.presentation.ShootingUiState
import com.example.insta_auto_adjust.ui.components.BrandHeader
import com.example.insta_auto_adjust.ui.components.DataStrip
import com.example.insta_auto_adjust.ui.components.DataValue
import com.example.insta_auto_adjust.ui.components.PrimaryAction
import com.example.insta_auto_adjust.ui.components.SectionEyebrow
import com.example.insta_auto_adjust.ui.components.WhiteSheet
import com.example.insta_auto_adjust.ui.theme.PilotGray
import com.example.insta_auto_adjust.ui.theme.PilotGraySoft
import com.example.insta_auto_adjust.ui.theme.PilotInk
import com.example.insta_auto_adjust.ui.theme.PilotLine
import com.example.insta_auto_adjust.ui.theme.PilotWhite
import com.example.insta_auto_adjust.ui.theme.PilotYellow

@Composable
fun ShootingScreen(
    shootingState: ShootingUiState,
    onIntentSelected: (ShootingIntent) -> Unit,
    onIntentTextChange: (String) -> Unit,
    onAnalyzeClick: () -> Unit,
    onAcceptProposal: () -> Unit,
    onHoldProposal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BrandHeader(
            subtitle = "拍摄助手",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
        )

        WhiteSheet {
            PreviewPanel()
            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = shootingState.intentInputText,
                onValueChange = onIntentTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：人物脸太暗了，但不要让天空过曝") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PilotInk,
                    unfocusedTextColor = PilotInk,
                    focusedBorderColor = PilotInk,
                    unfocusedBorderColor = PilotLine,
                    focusedPlaceholderColor = PilotGray,
                    unfocusedPlaceholderColor = PilotGray,
                    cursorColor = PilotInk
                )
            )

            Spacer(Modifier.height(14.dp))
            IntentSelector(shootingState.selectedIntent, onIntentSelected)
            Text(
                text = "当前：${intentText(shootingState.selectedIntent)}",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = PilotGray
            )
            Spacer(Modifier.height(18.dp))

            PrimaryAction(
                text = if (shootingState.isAnalyzing) "分析中..." else "分析当前画面",
                onClick = onAnalyzeClick,
                enabled = !shootingState.isAnalyzing
            )

            shootingState.userIntent?.let { intent ->
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "意图权重  主体 ${toPercent(intent.subjectPriority)}  ·  高光 ${toPercent(intent.highlightProtection)}  ·  稳定 ${toPercent(intent.stabilityPreference)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PilotGray
                )
                Text(
                    text = "${intent.source} · ${intent.allowedAdjustments.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PilotGray
                )
            }

            shootingState.visionMetrics?.let { metrics ->
                Spacer(Modifier.height(24.dp))
                SectionEyebrow("画面分析")
                Spacer(Modifier.height(8.dp))
                DataStrip {
                    DataValue("主体", toPercent(metrics.subjectBrightness))
                    DataValue("高光", toPercent(metrics.highlightRatio))
                    DataValue("暗部", toPercent(metrics.darkRatio))
                }
                Text(
                    text = "Frame ${metrics.frameId}",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = PilotGray
                )
            }

            if (shootingState.sceneRisk != null && shootingState.proposal == null) {
                Spacer(Modifier.height(20.dp))
                SectionEyebrow("场景风险")
                Text(
                    text = shootingState.sceneRisk,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PilotInk
                )
            }

            shootingState.proposal?.let { proposal ->
                Spacer(Modifier.height(28.dp))
                SectionEyebrow("建议调整  ·  PROPOSAL")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = proposal.action,
                    style = MaterialTheme.typography.headlineLarge,
                    color = PilotInk,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = shootingState.sceneRisk?.let { "$it · ${proposal.reason}" } ?: proposal.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PilotGray
                )
                Text(
                    text = "${proposal.cost} · ${proposal.proposalId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PilotGray,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(18.dp))
                ProposalActions(
                    decision = shootingState.proposalDecision,
                    onAccept = onAcceptProposal,
                    onHold = onHoldProposal
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PreviewPanel() {
    Box(
        modifier = Modifier.fillMaxWidth().height(190.dp).background(PilotGraySoft, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("⌜     ⌝\n\n⌞     ⌟", color = PilotGray, textAlign = TextAlign.Center)
        Text(
            text = "MOCK FRAME",
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            style = MaterialTheme.typography.labelMedium,
            color = PilotGray
        )
    }
}

@Composable
private fun IntentSelector(selected: ShootingIntent, onSelect: (ShootingIntent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IntentChoice("主体", selected == ShootingIntent.SUBJECT_PRIORITY, Modifier.weight(1f)) {
            onSelect(ShootingIntent.SUBJECT_PRIORITY)
        }
        IntentChoice("平衡", selected == ShootingIntent.BALANCED, Modifier.weight(1f)) {
            onSelect(ShootingIntent.BALANCED)
        }
        IntentChoice("高光", selected == ShootingIntent.HIGHLIGHT_PRIORITY, Modifier.weight(1f)) {
            onSelect(ShootingIntent.HIGHLIGHT_PRIORITY)
        }
        IntentChoice("稳定", selected == ShootingIntent.STABLE_EXPOSURE, Modifier.weight(1f)) {
            onSelect(ShootingIntent.STABLE_EXPOSURE)
        }
    }
}

@Composable
private fun IntentChoice(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = RoundedCornerShape(50),
        color = if (selected) PilotYellow else PilotGraySoft,
        contentColor = PilotInk
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun ProposalActions(
    decision: ProposalDecision?,
    onAccept: () -> Unit,
    onHold: () -> Unit
) {
    when (decision) {
        ProposalDecision.PENDING, null -> {
            PrimaryAction("接受建议", onAccept)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onHold,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp)
            ) { Text("保持当前", color = PilotInk) }
        }
        ProposalDecision.ACCEPTED -> Text(
            "已接受建议，等待进入执行阶段。",
            style = MaterialTheme.typography.bodyMedium,
            color = PilotInk
        )
        ProposalDecision.HELD -> Text(
            "已保持当前参数，本次建议不会执行。",
            style = MaterialTheme.typography.bodyMedium,
            color = PilotInk
        )
    }
}

private fun intentText(intent: ShootingIntent): String = when (intent) {
    ShootingIntent.SUBJECT_PRIORITY -> "主体优先"
    ShootingIntent.BALANCED -> "整体平衡"
    ShootingIntent.HIGHLIGHT_PRIORITY -> "高光优先"
    ShootingIntent.STABLE_EXPOSURE -> "稳定曝光"
}

private fun toPercent(value: Double): String = "${(value * 100).toInt()}%"
