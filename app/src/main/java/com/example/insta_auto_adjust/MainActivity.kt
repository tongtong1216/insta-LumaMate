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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.insta_auto_adjust.lightpilot.BrightRegionType
import com.example.insta_auto_adjust.lightpilot.AdvancedAction
import com.example.insta_auto_adjust.lightpilot.AdvancedCameraCapabilities
import com.example.insta_auto_adjust.lightpilot.AdvancedCameraState
import com.example.insta_auto_adjust.lightpilot.AdvancedIntentMapper
import com.example.insta_auto_adjust.lightpilot.AdvancedPolicyEngine
import com.example.insta_auto_adjust.lightpilot.AdvancedPolicyProposal
import com.example.insta_auto_adjust.lightpilot.AdvancedSafetyGuard
import com.example.insta_auto_adjust.lightpilot.AdvancedStage
import com.example.insta_auto_adjust.lightpilot.AdvancedTemporalController
import com.example.insta_auto_adjust.lightpilot.CameraExecutor
import com.example.insta_auto_adjust.lightpilot.ColorIntent
import com.example.insta_auto_adjust.lightpilot.ColorMetrics
import com.example.insta_auto_adjust.lightpilot.ColorPriority
import com.example.insta_auto_adjust.lightpilot.ExecutionRecord
import com.example.insta_auto_adjust.lightpilot.ExposureAction
import com.example.insta_auto_adjust.lightpilot.ExposureProgram
import com.example.insta_auto_adjust.lightpilot.ExposurePriority
import com.example.insta_auto_adjust.lightpilot.FakeCameraAdapter
import com.example.insta_auto_adjust.lightpilot.InputSource
import com.example.insta_auto_adjust.lightpilot.IntentMapper
import com.example.insta_auto_adjust.lightpilot.MotionIntent
import com.example.insta_auto_adjust.lightpilot.MotionMetrics
import com.example.insta_auto_adjust.lightpilot.MotionPriority
import com.example.insta_auto_adjust.lightpilot.MultiStageIntent
import com.example.insta_auto_adjust.lightpilot.LightPilotBackendClient
import com.example.insta_auto_adjust.lightpilot.StageWeights
import com.example.insta_auto_adjust.lightpilot.IntentParseStatus
import com.example.insta_auto_adjust.lightpilot.ACTIVE_STAGE_WEIGHT
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            MultiStageIntentPanel()
            HorizontalDivider()
            Text("阶段一 EV 单策略演示", style = MaterialTheme.typography.headlineSmall)
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
            HorizontalDivider()
            AdvancedPlanningPanel()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MultiStageIntentPanel() {
    var sourceText by remember { mutableStateOf("夜间跑步时拍清楚人物，同时保留霓虹灯颜色") }
    var exposureWeight by remember { mutableStateOf(0.8f) }
    var motionWeight by remember { mutableStateOf(0.95f) }
    var colorWeight by remember { mutableStateOf(0.85f) }
    var exposurePriority by remember { mutableStateOf<ExposurePriority?>(null) }
    var motionPriority by remember { mutableStateOf<MotionPriority?>(null) }
    var colorPriority by remember { mutableStateOf<ColorPriority?>(null) }
    var stability by remember { mutableStateOf(StabilityPreference.NORMAL) }
    var revision by remember { mutableLongStateOf(0) }
    var confirmed by remember { mutableStateOf<MultiStageIntent?>(null) }
    var status by remember { mutableStateOf("输入文本后调用后端百炼解析") }
    var ambiguities by remember { mutableStateOf<List<String>>(emptyList()) }
    var parsing by remember { mutableStateOf(false) }
    val backend = remember { LightPilotBackendClient("http://10.0.2.2:8000") }
    val scope = rememberCoroutineScope()

    Text("rc3 多阶段意图", style = MaterialTheme.typography.headlineSmall)
    Text("三个权重互相独立；只有点击确认后 intent_revision 才递增。")
    OutlinedTextField(
        value = sourceText,
        onValueChange = {
            sourceText = it.take(1000)
            confirmed = null
        },
        label = { Text("多阶段拍摄意图") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(enabled = !parsing && sourceText.isNotBlank(), onClick = {
        parsing = true
        confirmed = null
        status = "正在调用 /api/v1/parse-intent…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { backend.parseIntent(sourceText.trim()) }
                ambiguities = result.ambiguities
                if (result.status == IntentParseStatus.OK && result.draft != null) {
                    val draft = result.draft
                    exposureWeight = draft.weights.exposure.toFloat()
                    motionWeight = draft.weights.motionNoise.toFloat()
                    colorWeight = draft.weights.colorAtmosphere.toFloat()
                    exposurePriority = draft.exposurePriority
                    motionPriority = draft.motionPriority
                    colorPriority = draft.colorPriority
                    stability = draft.stabilityPreference
                    status = "百炼解析完成：${result.reason}"
                } else {
                    status = "解析不可用，请手动选择后再确认：${result.reason}"
                }
            } catch (error: Exception) {
                ambiguities = listOf("后端不可用，请手动选择所有已激活阶段")
                status = "解析失败，请手动选择；不会自动生成相机动作"
            } finally {
                parsing = false
            }
        }
    }) { Text(if (parsing) "解析中" else "用百炼解析三个阶段") }

    StageWeightSlider("曝光", exposureWeight) { exposureWeight = it; confirmed = null }
    StageWeightSlider("运动/噪点", motionWeight) { motionWeight = it; confirmed = null }
    StageWeightSlider("色彩/氛围", colorWeight) { colorWeight = it; confirmed = null }

    if (exposureWeight >= ACTIVE_STAGE_WEIGHT.toFloat()) {
        Text("曝光优先级（必选）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposurePriority.entries.forEach { item ->
                FilterChip(selected = exposurePriority == item,
                    onClick = { exposurePriority = item; confirmed = null },
                    label = { Text(item.label()) })
            }
        }
    }
    if (motionWeight >= ACTIVE_STAGE_WEIGHT.toFloat()) {
        Text("运动/噪点优先级（必选）")
        MotionPriority.entries.forEach { item ->
            FilterChip(selected = motionPriority == item,
                onClick = { motionPriority = item; confirmed = null },
                label = { Text(item.label()) })
        }
    }
    if (colorWeight >= ACTIVE_STAGE_WEIGHT.toFloat()) {
        Text("色彩/氛围优先级（必选）")
        ColorPriority.entries.forEach { item ->
            FilterChip(selected = colorPriority == item,
                onClick = { colorPriority = item; confirmed = null },
                label = { Text(item.label()) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("高稳定模式")
        Switch(checked = stability == StabilityPreference.HIGH, onCheckedChange = {
            stability = if (it) StabilityPreference.HIGH else StabilityPreference.NORMAL
            confirmed = null
        })
    }
    ambiguities.forEach { Text("需确认：$it") }
    val canConfirm = sourceText.isNotBlank() &&
        (exposureWeight < ACTIVE_STAGE_WEIGHT.toFloat() || exposurePriority != null) &&
        (motionWeight < ACTIVE_STAGE_WEIGHT.toFloat() || motionPriority != null) &&
        (colorWeight < ACTIVE_STAGE_WEIGHT.toFloat() || colorPriority != null)
    Button(enabled = canConfirm, onClick = {
        val next = revision + 1
        confirmed = MultiStageIntent(
            revision = next,
            sourceText = sourceText.trim(),
            weights = StageWeights(exposureWeight.toDouble(), motionWeight.toDouble(),
                colorWeight.toDouble()),
            exposurePriority = exposurePriority,
            motionPriority = motionPriority,
            colorPriority = colorPriority,
            stabilityPreference = stability,
        )
        revision = next
        status = "多阶段意图已由用户确认，intent_revision=$next"
    }) { Text("确认多阶段意图") }
    Text(status)
    confirmed?.let {
        Text("已激活：${it.activeStages().joinToString()}；权重=${it.weights}")
    }
}

@Composable
private fun StageWeightSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Text("$label 权重 ${value.format2()}")
    Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
}

@Composable
private fun AdvancedPlanningPanel() {
    var sourceText by remember { mutableStateOf("宁愿暗一点也不要拖影，动作要清楚") }
    var stage by remember { mutableStateOf(AdvancedStage.MOTION_NOISE) }
    var motionPriority by remember { mutableStateOf(MotionPriority.MOTION_CLARITY) }
    var colorPriority by remember { mutableStateOf(ColorPriority.COLOR_ACCURACY) }
    var stability by remember { mutableStateOf(StabilityPreference.NORMAL) }
    var revision by remember { mutableLongStateOf(0) }
    var confirmedRevision by remember { mutableStateOf<Long?>(null) }
    var frameId by remember { mutableLongStateOf(100) }
    var motionScore by remember { mutableStateOf(0.75f) }
    var darkRatio by remember { mutableStateOf(0.40f) }
    var estimatedKelvin by remember { mutableStateOf(5600f) }
    var candidate by remember { mutableStateOf<AdvancedPolicyProposal?>(null) }
    var stableProposal by remember { mutableStateOf<AdvancedPolicyProposal?>(null) }
    var progress by remember { mutableStateOf("请先确认高级意图") }
    var safetyResult by remember { mutableStateOf<String?>(null) }
    val engine = remember { AdvancedPolicyEngine() }
    val temporal = remember { AdvancedTemporalController() }
    val capabilities = remember {
        AdvancedCameraCapabilities(
            supportedShutterSeconds = listOf(1.0 / 1000, 1.0 / 500, 1.0 / 250,
                1.0 / 125, 1.0 / 60),
            supportedIso = listOf(100, 200, 400, 800, 1600),
            supportedWhiteBalanceKelvin = listOf(3200, 4000, 5000, 5600, 6500),
            shutterReadable = true,
            shutterWritable = true,
            isoReadable = true,
            isoWritable = true,
            whiteBalanceReadable = true,
            whiteBalanceWritable = true,
            executionMode = com.example.insta_auto_adjust.lightpilot.ExecutionMode.MOCK,
            inputSource = InputSource.MOCK,
        )
    }
    val state = remember {
        AdvancedCameraState(
            cameraStateRevision = 1,
            mode = "video",
            exposureProgram = ExposureProgram.MANUAL,
            currentShutterSeconds = 1.0 / 125,
            currentIso = 400,
            currentWhiteBalanceKelvin = 4000,
            recordingState = com.example.insta_auto_adjust.lightpilot.RecordingState.IDLE,
            isBusy = false,
            stateKnown = true,
        )
    }

    Text("阶段二/三策略规划", style = MaterialTheme.typography.headlineSmall)
    Text("当前只使用 Mock 能力验证意图到参数方向；SafetyGuard 会拒绝真实执行。")
    OutlinedTextField(
        value = sourceText,
        onValueChange = {
            sourceText = it.take(1000)
            confirmedRevision = null
        },
        label = { Text("阶段二/三意图原文") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = {
        val suggestion = AdvancedIntentMapper.suggest(sourceText)
        motionPriority = suggestion.motionPriority
        colorPriority = suggestion.colorPriority
        suggestion.stage?.let { stage = it }
        confirmedRevision = null
        candidate = null
        stableProposal = null
        safetyResult = null
        temporal.reset()
        progress = if (suggestion.stage == null) {
            "文本同时命中多个阶段或未命中，请手动选择并确认"
        } else {
            "已预选 ${suggestion.stage}，请确认结构化意图"
        }
    }) { Text("从文本预选阶段二/三") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AdvancedStage.entries.forEach { item ->
            FilterChip(
                selected = stage == item,
                onClick = {
                    stage = item
                    confirmedRevision = null
                    candidate = null
                    stableProposal = null
                    safetyResult = null
                    temporal.reset()
                },
                label = { Text(if (item == AdvancedStage.MOTION_NOISE) "阶段二 运动/噪点" else "阶段三 色彩/氛围") },
            )
        }
    }
    if (stage == AdvancedStage.MOTION_NOISE) {
        Text("运动与噪点意图")
        MotionPriority.entries.forEach { priority ->
            FilterChip(
                selected = motionPriority == priority,
                onClick = {
                    motionPriority = priority
                    confirmedRevision = null
                    temporal.reset()
                },
                label = { Text(priority.label()) },
            )
        }
        Text("运动指标 ${motionScore.format2()}")
        Slider(value = motionScore, onValueChange = { motionScore = it }, valueRange = 0f..1f)
        Text("暗部比例 ${darkRatio.format2()}")
        Slider(value = darkRatio, onValueChange = { darkRatio = it }, valueRange = 0f..1f)
    } else {
        Text("色彩与氛围意图")
        ColorPriority.entries.forEach { priority ->
            FilterChip(
                selected = colorPriority == priority,
                onClick = {
                    colorPriority = priority
                    confirmedRevision = null
                    temporal.reset()
                },
                label = { Text(priority.label()) },
            )
        }
        Text("本地估计色温 ${estimatedKelvin.toInt()} K")
        Slider(value = estimatedKelvin, onValueChange = { estimatedKelvin = it },
            valueRange = 2800f..7000f)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("高稳定模式")
        Switch(
            checked = stability == StabilityPreference.HIGH,
            onCheckedChange = {
                stability = if (it) StabilityPreference.HIGH else StabilityPreference.NORMAL
                confirmedRevision = null
                temporal.reset()
            },
        )
    }
    Button(onClick = {
        revision += 1
        confirmedRevision = revision
        candidate = null
        stableProposal = null
        safetyResult = null
        temporal.reset()
        progress = "高级意图已确认，revision=$revision"
    }) { Text("确认阶段二/三意图") }
    Button(enabled = confirmedRevision != null, onClick = {
        val currentRevision = confirmedRevision ?: return@Button
        frameId += 1
        val semantic = SceneSemantic(
            frameId = frameId,
            intentRevision = currentRevision,
            status = SemanticStatus.OK,
            scene = if (stage == AdvancedStage.MOTION_NOISE) SceneLabel.INDOOR_LOW_LIGHT
                else SceneLabel.STAGE_COLORED_LIGHT,
            subjectType = SubjectType.PERSON,
            brightRegionType = BrightRegionType.LAMP,
            coloredLight = stage == AdvancedStage.COLOR_ATMOSPHERE,
            uncertainty = emptyList(),
            reason = "Mock 演示语义，不可作为真机执行依据",
        )
        val proposal = if (stage == AdvancedStage.MOTION_NOISE) {
            engine.proposeMotion(
                MotionIntent(currentRevision, motionPriority, stability,
                    sourceText.trim().ifEmpty { null }),
                MotionMetrics(frameId, motionScore.toDouble(), darkRatio.toDouble()),
                semantic, state, capabilities, System.currentTimeMillis(),
            )
        } else {
            engine.proposeColor(
                ColorIntent(currentRevision, colorPriority, stability,
                    sourceText.trim().ifEmpty { null }),
                ColorMetrics(frameId, estimatedKelvin.toInt()),
                semantic, state, capabilities, System.currentTimeMillis(),
            )
        }
        candidate = proposal
        val decision = temporal.observe(proposal, stability, System.currentTimeMillis())
        stableProposal = decision.stableProposal
        safetyResult = null
        progress = if (decision.stableProposal == null) {
            "方向确认 ${decision.observedFrames}/${decision.requiredFrames}"
        } else {
            "已生成 ${proposal.stage} 规划：${proposal.action}"
        }
    }) { Text("提交一帧高级规划") }

    Text(progress)
    (stableProposal ?: candidate)?.let { AdvancedProposalCard(it) }
    stableProposal?.takeIf { it.action != AdvancedAction.HOLD }?.let { item ->
        Button(onClick = {
            val decision = AdvancedSafetyGuard.check(item)
            safetyResult = if (decision.allowed) "允许执行" else "已拦截：${decision.rejection}"
        }) { Text("验证执行安全拦截") }
    }
    safetyResult?.let { Text(it) }
}

@Composable
private fun AdvancedProposalCard(proposal: AdvancedPolicyProposal) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("动作：${proposal.action}", style = MaterialTheme.typography.titleMedium)
            Text("目标快门：${proposal.targetShutterSeconds ?: "保持"}")
            Text("目标 ISO：${proposal.targetIso ?: "保持"}")
            Text("目标白平衡：${proposal.targetWhiteBalanceKelvin?.let { "$it K" } ?: "保持"}")
            Text("原因码：${proposal.reasonCode}")
            Text(proposal.reason)
            Text("输入：${proposal.inputSource} · 执行：${proposal.executionMode}")
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

private fun MotionPriority.label() = when (this) {
    MotionPriority.MOTION_CLARITY -> "动作清晰"
    MotionPriority.LOW_NOISE -> "低噪点"
    MotionPriority.BRIGHTNESS_PRIORITY -> "亮度优先"
    MotionPriority.MOTION_BALANCED -> "运动平衡"
}

private fun ColorPriority.label() = when (this) {
    ColorPriority.COLOR_ACCURACY -> "颜色还原"
    ColorPriority.NATURAL_SKIN -> "自然肤色"
    ColorPriority.ATMOSPHERE_PRESERVATION -> "保留氛围"
    ColorPriority.COLORED_LIGHT_PRESERVATION -> "保留彩色光"
    ColorPriority.COLOR_STABILITY -> "颜色稳定"
}

private fun Float.format2() = String.format(Locale.US, "%.2f", this)

@Preview(showBackground = true)
@Composable
private fun LightPilotPreview() {
    InstaAutoAdjustTheme { LightPilotScreen() }
}
