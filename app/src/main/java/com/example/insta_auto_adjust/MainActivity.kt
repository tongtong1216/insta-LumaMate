package com.example.insta_auto_adjust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.insta_auto_adjust.lightpilot.BrightRegionType
import com.example.insta_auto_adjust.lightpilot.CameraExecutor
import com.example.insta_auto_adjust.lightpilot.ExecutionRecord
import com.example.insta_auto_adjust.lightpilot.ExposureAction
import com.example.insta_auto_adjust.lightpilot.ExposurePriority
import com.example.insta_auto_adjust.lightpilot.FakeCameraAdapter
import com.example.insta_auto_adjust.lightpilot.IntentMapper
import com.example.insta_auto_adjust.lightpilot.PolicyEngine
import com.example.insta_auto_adjust.lightpilot.PolicyProposal
import com.example.insta_auto_adjust.lightpilot.SafetyGuard
import com.example.insta_auto_adjust.lightpilot.SceneLabel
import com.example.insta_auto_adjust.lightpilot.SceneSemantic
import com.example.insta_auto_adjust.lightpilot.SemanticStatus
import com.example.insta_auto_adjust.lightpilot.StabilityPreference
import com.example.insta_auto_adjust.lightpilot.SubjectType
import com.example.insta_auto_adjust.lightpilot.TemporalController
import com.example.insta_auto_adjust.lightpilot.UserIntent
import com.example.insta_auto_adjust.lightpilot.VisionMetrics
import com.example.insta_auto_adjust.ui.theme.InstaAutoAdjustTheme
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { InstaAutoAdjustTheme { LightPilotScreen() } }
    }
}

@Composable
fun LightPilotScreen() {
    var sourceText by remember { mutableStateOf("优先拍清楚人物，同时尽量保留背景灯光") }
    var selectedPriority by remember { mutableStateOf(ExposurePriority.BALANCED) }
    var stability by remember { mutableStateOf(StabilityPreference.NORMAL) }
    var intentRevision by remember { mutableLongStateOf(0) }
    var confirmedIntent by remember { mutableStateOf<UserIntent?>(null) }
    var subjectBrightness by remember { mutableStateOf(0.25f) }
    var highlightRatio by remember { mutableStateOf(0.02f) }
    var frameId by remember { mutableLongStateOf(0) }
    var progress by remember { mutableStateOf("请先确认意图") }
    var proposal by remember { mutableStateOf<PolicyProposal?>(null) }
    var execution by remember { mutableStateOf<ExecutionRecord?>(null) }
    val policy = remember { PolicyEngine() }
    val temporal = remember { TemporalController() }
    val fakeCamera = remember { FakeCameraAdapter() }
    val executor = remember { CameraExecutor(fakeCamera, SafetyGuard()) }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("LightPilot P0", style = MaterialTheme.typography.headlineMedium)
            Text("当前为 Mock 相机演示。可以验证意图、策略和安全拦截，不会写入真实设备。")
            OutlinedTextField(
                value = sourceText,
                onValueChange = { sourceText = it.take(1000) },
                label = { Text("拍摄意图原文") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                val suggested = IntentMapper.suggest(sourceText)
                selectedPriority = suggested.first
                stability = suggested.second
                progress = "已根据文本预选，请确认结构化意图"
            }) { Text("从文本预选") }
            Text("曝光优先级")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposurePriority.entries.forEach { priority ->
                    FilterChip(
                        selected = selectedPriority == priority,
                        onClick = { selectedPriority = priority },
                        label = { Text(priority.label()) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("高稳定模式")
                Switch(
                    checked = stability == StabilityPreference.HIGH,
                    onCheckedChange = {
                        stability = if (it) StabilityPreference.HIGH else StabilityPreference.NORMAL
                    },
                )
            }
            Button(onClick = {
                intentRevision += 1
                confirmedIntent = UserIntent(
                    revision = intentRevision,
                    exposurePriority = selectedPriority,
                    stabilityPreference = stability,
                    sourceText = sourceText.trim().ifEmpty { null },
                )
                temporal.reset()
                proposal = null
                execution = null
                progress = "意图已确认，revision=$intentRevision"
            }) { Text("确认意图") }

            HorizontalDivider()
            Text("本地画面指标", style = MaterialTheme.typography.titleMedium)
            Text("主体亮度 ${subjectBrightness.format2()}")
            Slider(value = subjectBrightness, onValueChange = { subjectBrightness = it },
                valueRange = 0f..1f)
            Text("高光溢出比例 ${highlightRatio.format2()}")
            Slider(value = highlightRatio, onValueChange = { highlightRatio = it },
                valueRange = 0f..0.20f)
            Button(enabled = confirmedIntent != null, onClick = {
                val intent = confirmedIntent ?: return@Button
                frameId += 1
                val metrics = VisionMetrics(
                    frameId = frameId,
                    subjectBrightness = subjectBrightness.toDouble(),
                    backgroundBrightness = 0.5,
                    highlightClippingRatio = highlightRatio.toDouble(),
                    darkRatio = if (subjectBrightness < 0.12f) 0.4 else 0.05,
                )
                // Fixed semantic is for local policy demonstration. Real frames use LightPilotBackendClient.
                val semantic = SceneSemantic(
                    frameId = frameId,
                    intentRevision = intent.revision,
                    status = SemanticStatus.OK,
                    scene = SceneLabel.INDOOR_MIXED_LIGHT,
                    subjectType = SubjectType.PERSON,
                    brightRegionType = BrightRegionType.DISPLAY,
                    coloredLight = false,
                    uncertainty = emptyList(),
                    reason = "Mock 演示语义，不可作为真机执行依据",
                )
                val candidate = policy.propose(intent, metrics, semantic, fakeCamera.readState(),
                    fakeCamera.readCapabilities(), System.currentTimeMillis())
                val temporalDecision = temporal.observe(candidate, intent.stabilityPreference,
                    System.currentTimeMillis())
                proposal = temporalDecision.stableProposal
                execution = null
                progress = if (temporalDecision.stableProposal == null) {
                    "方向确认 ${temporalDecision.observedFrames}/${temporalDecision.requiredFrames}"
                } else {
                    "已生成建议：${candidate.action}"
                }
            }) { Text("提交一帧本地分析") }

            Text(progress)
            proposal?.let { item ->
                ProposalCard(item)
                Button(enabled = item.action != ExposureAction.HOLD, onClick = {
                    execution = executor.execute(
                        proposal = item,
                        confirmedProposalId = item.proposalId,
                        commandId = UUID.randomUUID().toString(),
                        currentIntentRevision = confirmedIntent!!.revision,
                        nowMs = System.currentTimeMillis(),
                    )
                }) { Text("确认建议并请求执行") }
            }
            execution?.let { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (record.success) "执行成功" else "SafetyGuard 已拦截")
                        Text("before=${record.beforeEv} target=${record.targetEv} readback=${record.readbackEv}")
                        Text("结果：${record.message}")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProposalCard(proposal: PolicyProposal) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("动作：${proposal.action}", style = MaterialTheme.typography.titleMedium)
            Text("目标 EV：${proposal.targetEv ?: "保持"}")
            Text("原因码：${proposal.reasonCode}")
            Text(proposal.reason)
            Text("风险：${proposal.risk} · 模式：${proposal.executionMode}")
        }
    }
}

private fun ExposurePriority.label() = when (this) {
    ExposurePriority.SUBJECT_DETAIL -> "主体优先"
    ExposurePriority.HIGHLIGHT_DETAIL -> "高光优先"
    ExposurePriority.BALANCED -> "平衡"
}

private fun Float.format2() = String.format(Locale.US, "%.2f", this)

@Preview(showBackground = true)
@Composable
private fun LightPilotPreview() {
    InstaAutoAdjustTheme { LightPilotScreen() }
}
