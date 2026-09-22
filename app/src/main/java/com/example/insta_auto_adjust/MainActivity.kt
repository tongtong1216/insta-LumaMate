package com.example.insta_auto_adjust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.example.insta_auto_adjust.policy.PolicyDemo
import com.example.insta_auto_adjust.policy.PolicyDemoResult
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
    var result by remember { mutableStateOf(PolicyDemo.runSubjectFirstBacklight()) }
    var runCount by remember { mutableStateOf(1) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("LightPilot C 策略演示")
        Text("当前使用 Mock 数据，不会连接真实相机或执行真实调参。")
        Text("用户意图：${result.intent.sourceText ?: "未提供"}")
        Text(
            "画面指标：主体亮度 ${
                result.metrics.subjectBrightness?.formatScore()
            }，高光比例 ${
                result.metrics.highlightRatio?.formatScore()
            }，暗部比例 ${
                result.metrics.darkRatio?.formatScore()
            }"
        )
        Text("策略动作：${result.proposal.action}")
        Text("策略理由：${result.proposal.reason}")
        Text("风险等级：${result.proposal.risk}")
        Text("安全判定：${result.safetyDecision.reason}")
        Text(result.safetyDecision.message)
        Text("本地策略运行次数：$runCount")
        (result.proposal.parameter as? ParameterTarget.Ev)?.let {
            Text("建议 EV：${it.value}")
        }
        Button(
            onClick = {
                result = PolicyDemo.runSubjectFirstBacklight()
                runCount += 1
            }
        ) {
            Text("重新运行 Mock 策略（第 ${runCount + 1} 次）")
        }
    }
}

private fun Float.formatScore(): String {
    return "%.2f".format(this)
}

@Preview(showBackground = true)
@Composable
fun PolicyDemoPreview() {
    InstaAutoAdjustTheme {
        PolicyDemoScreen()
    }
}
