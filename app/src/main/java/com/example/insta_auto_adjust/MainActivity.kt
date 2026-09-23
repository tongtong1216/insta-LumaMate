package com.example.insta_auto_adjust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.insta_auto_adjust.presentation.AppScreen
import com.example.insta_auto_adjust.presentation.CameraUiState
import com.example.insta_auto_adjust.presentation.ConnectionStatus
import com.example.insta_auto_adjust.presentation.DataSource
import com.example.insta_auto_adjust.presentation.ExecutionStatus
import com.example.insta_auto_adjust.presentation.ExecutionUiState
import com.example.insta_auto_adjust.presentation.PolicyProposalUi
import com.example.insta_auto_adjust.presentation.ProposalDecision
import com.example.insta_auto_adjust.presentation.ReportUiState
import com.example.insta_auto_adjust.presentation.ShootingUiState
import com.example.insta_auto_adjust.presentation.VisionMetricsUi
import com.example.insta_auto_adjust.ui.screen.ConnectionScreen
import com.example.insta_auto_adjust.ui.screen.ExecutionScreen
import com.example.insta_auto_adjust.ui.screen.ReportScreen
import com.example.insta_auto_adjust.ui.screen.ShootingScreen
import com.example.insta_auto_adjust.ui.theme.InstaAutoAdjustTheme
import com.example.insta_auto_adjust.intent.LocalKeywordIntentResolver

class MainActivity : ComponentActivity() {

    // =========================================================
    // 当前页面
    // =========================================================

    private var currentScreen by mutableStateOf(
        AppScreen.CONNECTION
    )

    // =========================================================
    // 相机连接页面状态
    // =========================================================

    private var cameraState by mutableStateOf(
        CameraUiState()
    )

    // =========================================================
    // 拍摄助手页面状态
    // =========================================================

    private var shootingState by mutableStateOf(
        ShootingUiState()
    )

    // =========================================================
    // 执行反馈页面状态
    // =========================================================

    private var executionState by mutableStateOf(
        ExecutionUiState()
    )

    // =========================================================
    // 拍摄报告页面状态
    // =========================================================

    private var reportState by mutableStateOf(
        ReportUiState()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {

            InstaAutoAdjustTheme {

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top)
                ) { innerPadding ->

                    when (currentScreen) {

                        // =================================================
                        // 页面 1：连接页面
                        // =================================================

                        AppScreen.CONNECTION -> {

                            ConnectionScreen(
                                cameraState = cameraState,

                                onConnectClick = {
                                    handleConnectionClick()
                                },

                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
                        }

                        // =================================================
                        // 页面 2：拍摄助手
                        // =================================================

                        AppScreen.SHOOTING -> {

                            ShootingScreen(
                                shootingState = shootingState,
                                isCameraConnected = cameraState.isRealCameraConnected,
                                onIntentSelected = { intent ->

                                    shootingState = shootingState.copy(
                                        selectedIntent = intent,

                                        // 用户改变拍摄意图后，
                                        // 旧分析结果和旧 Proposal 失效
                                        isAnalyzing = false,
                                        visionMetrics = null,
                                        sceneRisk = null,
                                        proposal = null,
                                        proposalDecision = null
                                    )
                                },

                                onIntentTextChange = { text ->

                                    shootingState = shootingState.copy(
                                        intentInputText = text,

                                        // 用户修改了意图，
                                        // 原来的结构化意图与分析结果都应失效
                                        userIntent = null,
                                        visionMetrics = null,
                                        sceneRisk = null,
                                        proposal = null,
                                        proposalDecision = null,
                                        isAnalyzing = false
                                    )
                                },

                                onAnalyzeClick = {
                                    if (cameraState.isRealCameraConnected) {
                                        handleMockAnalysis()
                                    }
                                },

                                onAcceptProposal = {
                                    handleAcceptProposal()
                                },

                                onHoldProposal = {
                                    handleHoldProposal()
                                },
                                onBackClick = {
                                    currentScreen = AppScreen.CONNECTION
                                },
                                onPreviewContainerReady = { container ->
                                    /*
                                     * A/B Preview integration point.
                                     *
                                     * A 集成时在这里使用长期存活的
                                     * CameraPreviewController：
                                     *
                                     * previewController.attach(container)
                                     * previewController.start()
                                     *
                                     * B 不创建、不持有 Insta360 SDK player。
                                     */
                                },

                                onPreviewContainerReleased = {
                                    /*
                                     * A 集成时：
                                     *
                                     * previewController.stop()
                                     * previewController.detach()
                                     *
                                     * SDK player / pipeline 的实际释放由 A 负责。
                                     */
                                },

                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
                        }

                        // =================================================
                        // 页面 3：执行反馈
                        // =================================================

                        AppScreen.EXECUTION -> {

                            ExecutionScreen(
                                executionState = executionState,

                                onExecuteClick = {
                                    handleMockExecution()
                                },

                                onReportClick = {
                                    handleOpenReport()
                                },
                                onBackClick = {
                                    currentScreen = AppScreen.SHOOTING
                                },

                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
                        }

                        // =================================================
                        // 页面 4：拍摄报告
                        // =================================================

                        AppScreen.REPORT -> {

                            ReportScreen(
                                reportState = reportState,

                                onFinishClick = {
                                    handleFinishReport()
                                },
                                onBackClick = {
                                    currentScreen = AppScreen.EXECUTION
                                },

                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    // =========================================================
// 相机连接入口
// =========================================================

    private fun handleConnectionClick() {

        when (cameraState.connectionStatus) {

            ConnectionStatus.DISCONNECTED,
            ConnectionStatus.ERROR -> {

                /*
                 * A/B 集成边界：
                 *
                 * 正式集成时，这里由 A 的长期存活
                 * Insta360ConnectionController 发起真实连接流程：
                 *
                 * 1. initializeAndScan()
                 * 2. 用户选择真实设备
                 * 3. connectViaBluetoothWifi(...)
                 * 4. 必要时 requestBleAuthorization()
                 *
                 * B 不再伪造 CONNECTED、EV、mode 或 supportedEv。
                 *
                 * 当前 B 分支没有 A 的 camera 模块，因此这里只进入
                 * CONNECTING 展示状态，等待 A 集成真实 Controller。
                 */
                cameraState = cameraState.copy(
                    connectionStatus = ConnectionStatus.CONNECTING,
                    dataSource = DataSource.UNAVAILABLE,
                    errorMessage = null
                )
            }

            ConnectionStatus.CONNECTING -> {
                /*
                 * 不允许第二次点击直接伪造连接成功。
                 *
                 * CONNECTED 必须由 A 的真实 CameraConnectionState
                 * 映射得到。
                 */
            }

            ConnectionStatus.CONNECTED -> {

                /*
                 * 只有真实相机连接状态才允许进入拍摄页。
                 */
                if (cameraState.isRealCameraConnected) {
                    currentScreen = AppScreen.SHOOTING
                }
            }
        }
    }
    // =========================================================
    // Mock 画面分析
    // =========================================================

    private fun handleMockAnalysis() {

        // 进入分析状态，并清除旧结果
        shootingState = shootingState.copy(
            isAnalyzing = true,
            visionMetrics = null,
            sceneRisk = null,
            proposal = null,
            proposalDecision = null
        )

        // ---------------------------------------------------------
        // Mock VisionMetrics
        //
        // 后续这里由 C 的真实 VisionMetrics 替换
        // ---------------------------------------------------------

        val mockVisionMetrics = VisionMetricsUi(
            frameId = "mock-frame-001",
            subjectBrightness = 0.32,
            highlightRatio = 0.18,
            darkRatio = 0.41,
            roiVersion = "mock-roi-v1",
            timestamp = System.currentTimeMillis()
        )

        // ---------------------------------------------------------
        // Mock PolicyProposal
        //
        // 后续这里由 C 的真实 PolicyProposal 替换
        // ---------------------------------------------------------

        val mockProposal = PolicyProposalUi(
            proposalId = "mock-proposal-001",
            action = "EV +1 step",
            reason = "主体偏暗，同时高光仍有调整空间",
            cost = "高光区域可能进一步变亮",
            validUntil = System.currentTimeMillis() + 10_000
        )

        // ---------------------------------------------------------
        // 把分析结果交给 UI
        // ---------------------------------------------------------

        val resolvedIntent =
            LocalKeywordIntentResolver.resolve(
                rawText = shootingState.intentInputText,
                selectedIntent = shootingState.selectedIntent
            )

        shootingState = shootingState.copy(
            isAnalyzing = false,

            // B 解析得到的结构化用户意图
            userIntent = resolvedIntent,

            // 当前仍然是 Mock VisionMetrics
            visionMetrics = mockVisionMetrics,

            sceneRisk = "主体偏暗",

            // 当前 Proposal 暂时仍然是 Mock
            // 下一阶段再替换成 C 的真实 PolicyEngine 输出
            proposal = mockProposal
        )
    }

    // =========================================================
    // 用户接受 Proposal
    // =========================================================

    private fun handleAcceptProposal() {

        val proposal = shootingState.proposal
            ?: return

        // ---------------------------------------------------------
        // 这里只表示：
        //
        // 用户接受了建议。
        //
        // 注意：
        // 此时还没有执行相机参数修改。
        // ---------------------------------------------------------

        shootingState = shootingState.copy(
            proposalDecision = ProposalDecision.ACCEPTED
        )

        val beforeEv = cameraState.currentEv ?: 0.0

        // Mock 阶段：
        // 模拟建议向上调整一个 EV step
        val targetEv = beforeEv + 1.0

        executionState = ExecutionUiState(
            proposalId = proposal.proposalId,
            beforeEv = beforeEv,
            targetEv = targetEv,
            sdkAck = null,
            readbackEv = null,
            status = ExecutionStatus.IDLE,
            errorMessage = null,
            isMock = true
        )

        // ---------------------------------------------------------
        // 进入执行反馈页面
        // ---------------------------------------------------------

        currentScreen = AppScreen.EXECUTION
    }

    // =========================================================
    // 用户保持当前参数
    // =========================================================

    private fun handleHoldProposal() {

        if (shootingState.proposal == null) {
            return
        }

        shootingState = shootingState.copy(
            proposalDecision = ProposalDecision.HELD
        )

        // HOLD 不进入 Execution 页面。
        //
        // 因为用户已经明确选择：
        // 不执行本次建议。
    }

    // =========================================================
    // Mock 参数执行
    // =========================================================

    private fun handleMockExecution() {

        // ---------------------------------------------------------
        // 第一步：
        // 标记为正在执行
        // ---------------------------------------------------------

        executionState = executionState.copy(
            status = ExecutionStatus.EXECUTING,
            sdkAck = null,
            readbackEv = null,
            errorMessage = null
        )

        // ---------------------------------------------------------
        // TEST / MOCK
        //
        // 当前没有调用 Insta360 SDK。
        //
        // 这里只模拟：
        //
        // SDK ACK = SUCCESS
        // Readback = Target
        //
        // 后续 A 接入真实 SDK 后，
        // 这里必须替换为真实执行流程。
        // ---------------------------------------------------------

        val mockAck = true

        val mockReadback = executionState.targetEv

        // ---------------------------------------------------------
        // 保存 Mock ACK + Readback
        // ---------------------------------------------------------

        executionState = executionState.copy(
            sdkAck = mockAck,
            readbackEv = mockReadback,
            status = ExecutionStatus.SUCCESS
        )

        // ---------------------------------------------------------
        // 非常重要：
        //
        // 当前是 Mock。
        //
        // 所以我们故意不修改：
        //
        // cameraState.currentEv
        //
        // 因为 mockReadback 并不是真实相机 SDK 的回读。
        // ---------------------------------------------------------
    }

    // =========================================================
    // 打开拍摄报告
    // =========================================================

    private fun handleOpenReport() {

        // 必须已经执行成功，才允许生成执行报告
        if (executionState.status != ExecutionStatus.SUCCESS) {
            return
        }

        // ---------------------------------------------------------
        // 把 Execution 的结果转换成 Report
        //
        // 注意：
        //
        // Suggestion
        // Execution
        // Readback
        // Effect
        //
        // 必须严格区分。
        // ---------------------------------------------------------

        reportState = ReportUiState(
            proposalId = executionState.proposalId ?: return,
            beforeEv = executionState.beforeEv,
            targetEv = executionState.targetEv,
            sdkAck = executionState.sdkAck,
            readbackEv = executionState.readbackEv,
            effectObservation = "TEST / MOCK：当前仅完成参数执行与回读流程，尚未进行真实拍摄效果验证。",
            isMock = true
        )

        currentScreen = AppScreen.REPORT
    }

    // =========================================================
    // 完成本次报告
    // =========================================================

    private fun handleFinishReport() {

        // ---------------------------------------------------------
        // 当前阶段：
        //
        // 完成报告以后返回拍摄助手。
        //
        // 后面如果需要，可以改成：
        // 返回首页 / 新建一次拍摄任务。
        // ---------------------------------------------------------

        shootingState = ShootingUiState()

        executionState = ExecutionUiState()

        reportState = ReportUiState()

        currentScreen = AppScreen.SHOOTING
    }
}