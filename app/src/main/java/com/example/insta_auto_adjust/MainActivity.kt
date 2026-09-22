package com.example.insta_auto_adjust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.example.insta_auto_adjust.policy.MockPolicyScenario
import com.example.insta_auto_adjust.policy.LocalKeywordIntentResolver
import com.example.insta_auto_adjust.policy.PolicyDemo
import com.lightpilot.core.model.ParameterTarget
import com.example.insta_auto_adjust.ui.theme.InstaAutoAdjustTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InstaAutoAdjustTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PolicyDemoScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun PolicyDemoScreen(modifier: Modifier = Modifier) {
    val intentResolver = remember { LocalKeywordIntentResolver() }
    val demoSession = remember { PolicyDemo.newSession() }
    var selectedScenario by remember {
        mutableStateOf<MockPolicyScenario?>(MockPolicyScenario.SUBJECT_FIRST)
    }
    var intentText by remember { mutableStateOf(MockPolicyScenario.SUBJECT_FIRST.sourceText) }
    var resolution by remember {
        mutableStateOf(
            intentResolver.resolve(
                sourceText = intentText,
                revision = 1L,
                nowEpochMs = System.currentTimeMillis()
            )
        )
    }
    var result by remember {
        mutableStateOf(
            demoSession.run(
                intent = resolution.intent,
                scenario = MockPolicyScenario.SUBJECT_FIRST
            )
        )
    }
    var runCount by remember { mutableStateOf(1) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
        .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("LightPilot C 策略演示")
        Text("当前使用 Mock 数据，不连接真实相机，也不执行真实调参。")
        Text("B 接收用户意图，C 只负责计算策略建议和安全判定。")
        Text("三个场景是快捷预设，不是用户输入的全部范围。")
        OutlinedTextField(
            value = intentText,
            onValueChange = { intentText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("告诉助手你想优先拍好什么") },
            placeholder = {
                Text("例如：人脸要清楚，窗外天空不要过曝，夜景尽量少噪点")
            },
            minLines = 3
        )
        Button(
            onClick = {
                if (intentText.isNotBlank()) {
                    val nextRun = runCount + 1
                    val nextResolution = intentResolver.resolve(
                        sourceText = intentText,
                        revision = nextRun.toLong(),
                        nowEpochMs = System.currentTimeMillis()
                    )
                    resolution = nextResolution
                    selectedScenario = null
                    runCount = nextRun
                    result = demoSession.run(
                        intent = nextResolution.intent,
                        nowEpochMs = System.currentTimeMillis()
                    )
                }
            }
        ) {
            Text("解析用户意图并生成策略")
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MockPolicyScenario.entries.forEach { scenario ->
                FilterChip(
                    selected = selectedScenario == scenario,
                    onClick = {
                        val nextRun = runCount + 1
                        selectedScenario = scenario
                        intentText = scenario.sourceText
                        runCount = nextRun
                        val nextResolution = intentResolver.resolve(
                            sourceText = scenario.sourceText,
                            revision = nextRun.toLong(),
                            nowEpochMs = System.currentTimeMillis()
                        )
                        resolution = nextResolution
                        result = demoSession.run(
                            intent = nextResolution.intent,
                            scenario = scenario,
                            nowEpochMs = System.currentTimeMillis()
                        )
                    },
                    label = { Text(scenario.title) }
                )
            }
        }
        Text("当前入口：${result.scenario?.title ?: "自然语言意图"}")
        result.scenario?.let { scenario ->
            Text("预设说明：${scenario.description}")
        }
        Text("用户意图：${result.intent.sourceText ?: "未提供"}")
        Text(
            "意图解析：${resolution.resolverName}" +
                if (resolution.usedFallback) {
                    "，未识别明确偏好，按平衡意图处理"
                } else {
                    "，识别到：${resolution.matchedSignals.joinToString("、")}"
                }
        )
        Text(
            "画面指标：主体亮度 ${
                result.metrics.subjectBrightness?.formatScore()
            }，高光比例 ${
                result.metrics.highlightRatio?.formatScore()
            }，暗部比例 ${
                result.metrics.darkRatio?.formatScore()
            }"
        )
        Text(
            "输入标记：input_source=${
                result.proposal.inputSource.name.lowercase()
            }，execution_mode=${
                result.proposal.executionMode.name.lowercase()
            }"
        )
        Text("策略动作：${result.proposal.action}")
        Text("建议类型：${result.proposal.parameter.describeTarget()}")
        if (result.proposal.executionMode == com.lightpilot.core.model.ExecutionMode.MOCK &&
            result.proposal.action != com.lightpilot.core.model.PolicyAction.HOLD
        ) {
            Text("模拟建议：仅用于策略演示，尚未验证 GO Ultra SDK 能力")
        }
        Text("策略理由：${result.proposal.reason}")
        Text("风险等级：${result.proposal.risk}")
        Text(
            "连续确认：${
                result.temporalDecision?.reason ?: "未使用协调器"
            }"
        )
        Text(
            "是否允许真实执行：${
                if (result.canRequestConfirmation) "是" else "否"
            }"
        )
        Text("安全判定：${result.safetyDecision.reason}")
        Text(result.safetyDecision.message)
        result.proposal.diagnostics?.let { diagnostics ->
            Text(
                "评分：up=${diagnostics.upScore.formatScore()}，" +
                    "down=${diagnostics.downScore.formatScore()}，" +
                    "margin=${diagnostics.scoreMargin.formatScore()}"
            )
        }
        Text("本地策略运行次数：$runCount")
        (result.proposal.parameter as? ParameterTarget.Ev)?.let {
            Text("建议 EV：${it.value}")
        }
        Button(
            onClick = {
                val nextRun = runCount + 1
                runCount = nextRun
                val nextResolution = intentResolver.resolve(
                    sourceText = intentText,
                    revision = nextRun.toLong(),
                    nowEpochMs = System.currentTimeMillis()
                )
                resolution = nextResolution
                selectedScenario = null
                result = demoSession.run(
                    intent = nextResolution.intent,
                    nowEpochMs = System.currentTimeMillis()
                )
            }
        ) {
            Text("重新运行当前 Mock 策略")
        }
    }
}

private fun Float.formatScore(): String {
    return "%.2f".format(this)
}

private fun ParameterTarget?.describeTarget(): String {
    return when (this) {
        null -> "无"
        is ParameterTarget.Ev -> "EV ${value.formatDecimal()}"
        is ParameterTarget.Shutter -> "快门 ${value.numerator.formatDecimal()}/${value.denominator.formatDecimal()}"
        is ParameterTarget.Iso -> "ISO ${value}"
        is ParameterTarget.WhiteBalance -> "白平衡 ${value}K"
    }
}

private fun Double.formatDecimal(): String {
    return if (this % 1.0 == 0.0) {
        this.toInt().toString()
    } else {
        "%.2f".format(this)
    }
}

@Preview(showBackground = true)
@Composable
fun PolicyDemoPreview() {
    InstaAutoAdjustTheme {
        PolicyDemoScreen()
    }
}
