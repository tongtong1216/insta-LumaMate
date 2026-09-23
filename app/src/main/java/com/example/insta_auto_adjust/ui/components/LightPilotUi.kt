package com.example.insta_auto_adjust.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.example.insta_auto_adjust.ui.theme.PilotBlack
import com.example.insta_auto_adjust.ui.theme.PilotGray
import com.example.insta_auto_adjust.ui.theme.PilotInk
import com.example.insta_auto_adjust.ui.theme.PilotLine
import com.example.insta_auto_adjust.ui.theme.PilotWhite
import com.example.insta_auto_adjust.ui.theme.PilotYellow


val PilotSheetShape = RoundedCornerShape(
    topStart = 28.dp,
    topEnd = 28.dp
)

val PilotControlShape = RoundedCornerShape(16.dp)

@Composable
fun BrandHeader(
    subtitle: String,
    modifier: Modifier = Modifier,
    showMock: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = buildAnnotatedString {
                    append("Light")
                    withStyle(
                        SpanStyle(
                            color = PilotYellow
                        )
                    ) {
                        append("Pilot")
                    }
                },
                style = MaterialTheme.typography.headlineMedium,
                color = PilotWhite
            )

            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = PilotWhite.copy(alpha = 0.62f)
            )
        }

        if (showMock) {
            MockBadge()
        }
    }
}
@Composable
fun NavigableBrandHeader(
    subtitle: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMockInfo by remember {
        mutableStateOf(false)
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {

        // =====================================================
        // 第一行：返回 + Mock 信息按钮
        // =====================================================

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            // -------------------------------------------------
            // 左上角：返回
            // -------------------------------------------------

            Row(
                modifier = Modifier
                    .clickable {
                        onBackClick()
                    }
                    .padding(
                        horizontal = 2.dp,
                        vertical = 6.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = "‹",
                    fontSize = 22.sp,
                    color = PilotWhite
                )

                Spacer(
                    modifier = Modifier.width(4.dp)
                )

                Text(
                    text = "返回",
                    fontSize = 12.sp,
                    color = PilotWhite.copy(
                        alpha = 0.82f
                    )
                )
            }

            // -------------------------------------------------
            // 右上角：信息按钮
            // -------------------------------------------------

            Text(
                text = "ⓘ",
                modifier = Modifier
                    .clickable {
                        showMockInfo = !showMockInfo
                    }
                    .padding(6.dp),
                fontSize = 20.sp,
                color = PilotWhite.copy(
                    alpha = 0.85f
                ),
                textAlign = TextAlign.Center
            )
        }

        Spacer(
            modifier = Modifier.height(14.dp)
        )

        // =====================================================
        // 第二部分：LightPilot 标题
        // =====================================================

        Text(
            text = buildAnnotatedString {

                append("Light")

                withStyle(
                    SpanStyle(
                        color = PilotYellow
                    )
                ) {
                    append("Pilot")
                }
            },
            style = MaterialTheme.typography.headlineMedium,
            color = PilotWhite
        )

        Text(
            text = subtitle,
            modifier = Modifier.padding(
                top = 4.dp
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = PilotWhite.copy(
                alpha = 0.62f
            )
        )

        // =====================================================
        // TEST / MOCK 说明
        //
        // 默认隐藏。
        // 点击右上角 ⓘ 后显示。
        // =====================================================

        if (showMockInfo) {

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = PilotWhite.copy(
                            alpha = 0.10f
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {

                    Column(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 14.dp
                        )
                    ) {

                        Text(
                            text = "ⓘ  TEST / MOCK",
                            style = MaterialTheme.typography.labelLarge,
                            color = PilotWhite,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )

                        Text(
                            text = "当前为测试模式",
                            style = MaterialTheme.typography.bodySmall,
                            color = PilotWhite.copy(
                                alpha = 0.82f
                            )
                        )

                        Text(
                            text = "使用模拟数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = PilotWhite.copy(
                                alpha = 0.82f
                            )
                        )

                        Text(
                            text = "尚未接入真实相机",
                            style = MaterialTheme.typography.bodySmall,
                            color = PilotWhite.copy(
                                alpha = 0.82f
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MockBadge(
    text: String = "TEST / MOCK"
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        modifier = Modifier.border(
            1.dp,
            PilotWhite.copy(alpha = 0.46f),
            RoundedCornerShape(50)
        )
    ) {
        Text(
            text,
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 6.dp
            ),
            style = MaterialTheme.typography.labelMedium,
            color = PilotWhite.copy(alpha = 0.82f)
        )
    }
}

@Composable
fun WhiteSheet(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PilotWhite,
        contentColor = PilotInk,
        shape = PilotSheetShape
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    start = 22.dp,
                    top = 24.dp,
                    end = 22.dp
                )
                .navigationBarsPadding()
                .padding(
                    bottom = 24.dp
                ),
            content = {
                content()
            }
        )
    }
}

@Composable
fun BottomAnchoredPage(
    modifier: Modifier = Modifier,
    sheetTopClearance: Dp = 156.dp,
    topContent: @Composable () -> Unit,
    sheetContent: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        topContent()

        WhiteSheet(
            modifier = Modifier
                .align(
                    Alignment.BottomCenter
                )
                .heightIn(
                    max = (
                            maxHeight - sheetTopClearance
                            ).coerceAtLeast(320.dp)
                ),
            content = sheetContent
        )
    }
}

@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(
                min = 54.dp
            ),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PilotYellow,
            contentColor = PilotBlack,
            disabledContainerColor =
                PilotYellow.copy(alpha = 0.45f),
            disabledContentColor =
                PilotBlack.copy(alpha = 0.55f)
        )
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun SectionEyebrow(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = PilotGray
    )
}

@Composable
fun DataStrip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                PilotLine,
                PilotControlShape
            )
            .padding(
                vertical = 16.dp
            ),
        content = content
    )
}

@Composable
fun RowScope.DataValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = PilotGray
        )

        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = PilotInk,
            fontWeight = FontWeight.Bold
        )
    }
}
