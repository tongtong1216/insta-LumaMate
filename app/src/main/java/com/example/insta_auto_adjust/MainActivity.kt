package com.example.insta_auto_adjust

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.insta_auto_adjust.camera.contract.CameraSnapshot
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
import com.example.insta_auto_adjust.camera.insta360.CameraConnectionState
import com.example.insta_auto_adjust.camera.insta360.ConnectionPhase
import com.example.insta_auto_adjust.camera.insta360.Insta360ConnectionController
import com.example.insta_auto_adjust.camera.preview.PreviewUiState
import com.example.insta_auto_adjust.network.DBackendSceneAnalysisClient
import com.example.insta_auto_adjust.network.RealFrameImageReader
import com.example.insta_auto_adjust.network.RealFramePayload
import com.example.insta_auto_adjust.policy.FrontendPolicyBridge
import com.lightpilot.core.contract.v1.AnalyzeSceneIntent
import com.lightpilot.core.contract.v1.AnalyzeSceneMetrics
import com.lightpilot.core.contract.v1.AnalyzeSceneRequest
import com.lightpilot.core.contract.v1.ExposurePriority
import com.lightpilot.core.contract.v1.SceneSemanticCache
import com.lightpilot.core.contract.v1.StabilityPreference
import com.lightpilot.core.contract.v1.V1SceneSemanticDataSource
import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExposureProgram
import com.lightpilot.core.model.FrameSource
import com.lightpilot.core.model.InputSource
import com.lightpilot.core.model.ParameterTarget
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.PolicyProposal
import com.lightpilot.core.model.RecordingState
import com.lightpilot.core.model.RiskLevel
import com.lightpilot.core.model.SafetyDecision
import com.lightpilot.core.model.SafetyReason
import com.lightpilot.core.model.SceneSemantic
import com.lightpilot.core.model.ShutterSpeed
import com.lightpilot.core.policy.PolicyCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val policyBridge = FrontendPolicyBridge()
    private val policyCoordinator = PolicyCoordinator()

    private val cameraController by lazy {
        Insta360ConnectionController(applicationContext)
    }
    private val sceneDataSource = V1SceneSemanticDataSource(
        client = DBackendSceneAnalysisClient(BuildConfig.D_BACKEND_BASE_URL)
    )
    private val semanticCache = SceneSemanticCache()
    private var intentRevision = 0L
    private var confirmedIntentSignature: String? = null
    private var latestRequestedModelFrameId: String? = null
    private var activeCoreProposal: PolicyProposal? = null
    private var activeSafetyDecision: SafetyDecision? = null

    private data class PendingAnalysis(
        val state: ShootingUiState,
        val revision: Long,
        val snapshot: CameraSnapshot,
        val intentSignature: String,
    )

    private fun intentSignature(state: ShootingUiState): String =
        "${state.selectedIntent.name}|${state.intentInputText.trim()}"

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val allGranted = requiredCameraPermissions().all { permission ->
            grantResults[permission] == true ||
                checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            cameraController.initializeAndScan()
        } else {
            cameraState = cameraState.copy(
                connectionStatus = ConnectionStatus.ERROR,
                dataSource = DataSource.UNAVAILABLE,
                errorMessage = "需要附近设备和蓝牙权限才能扫描相机",
            )
        }
    }

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

            val connectionState by cameraController.state.collectAsState()
            val previewState by cameraController.previewState.collectAsState()

            LaunchedEffect(connectionState) {
                cameraState = connectionState.toCameraUiState()
            }

            // GO Ultra's SDK checks authorization after a real Bluetooth/Wi-Fi connection.
            // This remains independent of C's analysis and execution flow.
            LaunchedEffect(connectionState.phase) {
                if (connectionState.phase == ConnectionPhase.CONNECTED) {
                    cameraController.requestBleAuthorization()
                }
            }

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
                                scannedDevices = connectionState.scannedDevices,

                                onConnectClick = {
                                    handleConnectionClick()
                                },

                                onDeviceSelected = { device ->
                                    cameraController.connectViaBluetoothWifi(device)
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
                                        handleRealAnalysis()
                                    }
                                },

                                onAcceptProposal = {
                                    handleAcceptProposal()
                                },

                                onHoldProposal = {
                                    handleHoldProposal()
                                },
                                onBackClick = {
                                    cameraController.stop()
                                    currentScreen = AppScreen.CONNECTION
                                },
                                onPreviewContainerReady = { container ->
                                    cameraController.attach(container)
                                    cameraController.start()
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
                                    cameraController.stop()
                                    cameraController.detach()
                                    /*
                                     * A 集成时：
                                     *
                                     * previewController.stop()
                                     * previewController.detach()
                                     *
                                     * SDK player / pipeline 的实际释放由 A 负责。
                                     */
                                },

                                previewState = previewState,
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
                                    handleRealExecution()
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

                startCameraScan()

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

    private fun startCameraScan() {
        val missingPermissions = requiredCameraPermissions().filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            cameraController.initializeAndScan()
        } else {
            cameraPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun requiredCameraPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun onStart() {
        super.onStart()
        if (currentScreen == AppScreen.SHOOTING && cameraState.isRealCameraConnected) {
            cameraController.start()
        }
    }

    override fun onStop() {
        cameraController.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cameraController.close()
        super.onDestroy()
    }

    private fun handleRealAnalysis() {
        val initialSnapshot = cameraController.state.value.snapshot
        if (cameraController.state.value.phase != ConnectionPhase.CONNECTED || initialSnapshot == null) {
            shootingState = shootingState.copy(
                isAnalyzing = false,
                sceneRisk = "真实相机尚未连接或没有可读参数快照。",
            )
            return
        }
        val signature = intentSignature(shootingState)
        if (signature != confirmedIntentSignature) {
            intentRevision++
            confirmedIntentSignature = signature
        }
        val request = PendingAnalysis(shootingState, intentRevision, initialSnapshot, signature)
        activeCoreProposal = null
        activeSafetyDecision = null
        shootingState = shootingState.copy(
            isAnalyzing = true,
            dataSource = DataSource.REAL,
            visionMetrics = null,
            proposal = null,
            proposalDecision = null,
            proposalExecutable = false,
            proposalBlockReason = null,
            sceneRisk = "正在从当前真实预览帧分析画面并请求后端语义。",
        )
        lifecycleScope.launch {
            try {
                analyzeRealPreview(request)
            } catch (error: Throwable) {
                shootingState = shootingState.copy(
                    isAnalyzing = false,
                    proposalExecutable = false,
                    sceneRisk = "真实画面分析失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private suspend fun analyzeRealPreview(request: PendingAnalysis) {
        val firstFrame = withContext(Dispatchers.IO) { cameraController.awaitLatestAnalysisFrame() }
        val firstPayload = withContext(Dispatchers.Default) {
            RealFrameImageReader.readJpeg(
                firstFrame.jpegBytes,
                firstFrame.frameId,
                firstFrame.capturedAtEpochMs,
                firstFrame.source.toCoreFrameSource(),
            )
        }
        latestRequestedModelFrameId = firstFrame.frameId
        val firstDraft = policyBridge.prepare(
            request.state,
            request.revision,
            firstFrame.frameId,
            firstFrame.capturedAtEpochMs,
            realMetrics = firstPayload.metrics,
            realMetricsUi = firstPayload.metricsUi,
        )
        val semantic = readSceneSemanticFromD(
            request.state,
            request.revision,
            firstFrame.frameId,
            firstFrame.capturedAtEpochMs,
            firstDraft,
            firstPayload.imageBase64,
        )
        cameraController.recordAnalysisDiagnostic(
            "dSemantic",
            "D semantic: status=${semantic.analysisStatus}, available=${semantic.available}, " +
                "reason=${semantic.reason}, uncertainty=${semantic.uncertaintyNotes}",
        )
        if (isStale(request)) return discardStaleAnalysis()
        val freshSnapshot = withContext(Dispatchers.IO) { cameraController.readSnapshot() }
        val coreState = freshSnapshot.toCoreCameraState()
        val coreCapabilities = freshSnapshot.toCoreCapabilities()
        val token = semanticCache.beginRequest(firstFrame.frameId.toLong(), request.revision)
        val cacheAccepted = token != null && semanticCache.complete(
            token,
            semantic,
            firstPayload.metrics,
            intentRevision,
            freshSnapshot.state.capabilityRevision,
            freshSnapshot.state.mode,
            System.currentTimeMillis(),
        )
        cameraController.recordAnalysisDiagnostic(
            "semanticCache",
            "semantic cache: accepted=$cacheAccepted, tokenCreated=${token != null}, " +
                "mode=${freshSnapshot.state.mode}, ev=${freshSnapshot.state.currentEv}, " +
                "supportedEv=${freshSnapshot.capabilities.supportedEv}",
        )

        var payload = firstPayload
        var draft = firstDraft
        var coordinated: com.example.insta_auto_adjust.policy.CoordinatedFrontendResult? = null
        val requiredFrames = if (
            request.state.selectedIntent == com.example.insta_auto_adjust.presentation.ShootingIntent.STABLE_EXPOSURE
        ) 5 else 3
        repeat(requiredFrames) { index ->
            if (index > 0) {
                val frame = withContext(Dispatchers.IO) { cameraController.awaitLatestAnalysisFrame() }
                payload = withContext(Dispatchers.Default) {
                    RealFrameImageReader.readJpeg(
                        frame.jpegBytes,
                        frame.frameId,
                        frame.capturedAtEpochMs,
                        frame.source.toCoreFrameSource(),
                    )
                }
                draft = policyBridge.prepare(
                    request.state,
                    request.revision,
                    payload.metrics.frameId,
                    payload.metrics.capturedAtEpochMs,
                    realMetrics = payload.metrics,
                    realMetricsUi = payload.metricsUi,
                )
            }
            val currentSemantic = if (cacheAccepted) semanticCache.current(
                intentRevision,
                freshSnapshot.state.capabilityRevision,
                freshSnapshot.state.mode,
                payload.metrics,
                System.currentTimeMillis(),
            ) else null
            coordinated = policyBridge.evaluateCoordinated(
                request.state,
                draft,
                currentSemantic ?: unavailableSceneSemantic(
                    payload.metrics.frameId,
                    request.revision,
                    System.currentTimeMillis(),
                    IllegalStateException("semantic_cache_unavailable"),
                ),
                System.currentTimeMillis(),
                coreState,
                coreCapabilities,
                policyCoordinator,
            )
        }
        if (isStale(request)) return discardStaleAnalysis()
        val result = requireNotNull(coordinated)
        val proposal = result.presentation.coreProposal
        val safety = result.cycle.safetyDecision.takeIf { result.cycle.canRequestConfirmation }
        activeCoreProposal = proposal
        activeSafetyDecision = safety
        cameraController.recordAnalysisDiagnostic(
            "policy",
            "policy: action=${proposal.action}, reason=${proposal.reason}, " +
                "temporal=${result.cycle.temporalDecision.reason}, safety=${safety?.reason}",
        )
        shootingState = shootingState.copy(
            userIntent = result.presentation.userIntentUi,
            isAnalyzing = false,
            dataSource = DataSource.REAL,
            visionMetrics = result.presentation.visionMetricsUi,
            sceneRisk = result.presentation.sceneRisk,
            proposal = result.presentation.proposalUi,
            proposalExecutable = result.cycle.canRequestConfirmation && safety?.allowed == true,
            proposalBlockReason = when {
                proposal.action == PolicyAction.HOLD -> proposal.reason
                !result.cycle.temporalDecision.ready -> "本地时序确认未完成：${result.cycle.temporalDecision.reason}"
                safety?.allowed != true -> "建议未通过真实相机安全校验。"
                else -> null
            },
            proposalDecision = if (proposal.action == PolicyAction.HOLD || !result.cycle.canRequestConfirmation) {
                ProposalDecision.HELD
            } else {
                ProposalDecision.PENDING
            },
        )
    }

    private fun isStale(request: PendingAnalysis): Boolean =
        request.revision != intentRevision || request.intentSignature != intentSignature(shootingState)

    private fun discardStaleAnalysis() {
        shootingState = shootingState.copy(
            isAnalyzing = false,
            proposalExecutable = false,
            proposalDecision = ProposalDecision.HELD,
            sceneRisk = "已丢弃过期分析结果：拍摄意图已变化。",
        )
    }

    private suspend fun readSceneSemanticFromD(
        state: ShootingUiState,
        revision: Long,
        frameId: String,
        nowEpochMs: Long,
        draft: com.example.insta_auto_adjust.policy.FrontendPolicyDraft,
        imageBase64: String,
    ): SceneSemantic = withContext(Dispatchers.IO) {
        try {
            cameraController.withBackendNetwork {
                sceneDataSource.readSemantic(
                    AnalyzeSceneRequest(
                        frameId = frameId.toLong(),
                        intentRevision = revision,
                        intent = AnalyzeSceneIntent(
                            exposurePriority = when (state.selectedIntent) {
                                com.example.insta_auto_adjust.presentation.ShootingIntent.SUBJECT_PRIORITY -> ExposurePriority.SUBJECT_DETAIL
                                com.example.insta_auto_adjust.presentation.ShootingIntent.HIGHLIGHT_PRIORITY -> ExposurePriority.HIGHLIGHT_DETAIL
                                else -> ExposurePriority.BALANCED
                            },
                            stabilityPreference = if (
                                state.selectedIntent == com.example.insta_auto_adjust.presentation.ShootingIntent.STABLE_EXPOSURE
                            ) StabilityPreference.HIGH else StabilityPreference.NORMAL,
                            sourceText = draft.userIntent.sourceText ?: state.intentInputText,
                        ),
                        imageBase64 = imageBase64,
                        metrics = AnalyzeSceneMetrics(
                            draft.metrics.subjectBrightness,
                            draft.metrics.backgroundBrightness,
                            draft.metrics.highlightRatio,
                            draft.metrics.darkRatio,
                        ),
                    ),
                    nowEpochMs,
                )
            }
        } catch (error: Throwable) {
            unavailableSceneSemantic(frameId, revision, nowEpochMs, error)
        }
    }

    private fun unavailableSceneSemantic(
        frameId: String,
        revision: Long,
        nowEpochMs: Long,
        error: Throwable,
    ) = SceneSemantic(
        available = false,
        scene = null,
        subjectType = null,
        brightRegionType = null,
        coloredLight = null,
        uncertainty = 1.0f,
        reason = "d_backend_request_failed:${error.javaClass.simpleName}",
        sourceFrameId = frameId,
        receivedAtEpochMs = nowEpochMs,
        expiresAtEpochMs = null,
        intentRevision = revision,
        uncertaintyNotes = listOf("connection_failed"),
        analysisStatus = "unavailable",
    )

    /** Legacy B mock remains only as historical reference; no UI event calls it. */
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
        val proposal = activeCoreProposal ?: return
        val safety = activeSafetyDecision
        if (proposal.action == PolicyAction.HOLD || safety?.allowed != true) {
            shootingState = shootingState.copy(
                proposalDecision = ProposalDecision.HELD,
                proposalExecutable = false,
                proposalBlockReason = "当前建议未通过真实相机安全校验，不能写入参数。",
            )
            return
        }
        shootingState = shootingState.copy(proposalDecision = ProposalDecision.ACCEPTED)
        executionState = ExecutionUiState(
            proposalId = proposal.proposalId,
            beforeEv = cameraController.state.value.snapshot?.state?.currentEv,
            targetEv = (proposal.parameter as? ParameterTarget.Ev)?.value,
            status = ExecutionStatus.IDLE,
            isMock = false,
        )
        currentScreen = AppScreen.EXECUTION
    }

    /** Legacy B mock remains only as historical reference; no UI event calls it. */
    private fun handleLegacyMockAcceptProposal() {

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

    private fun handleRealExecution() {
        val proposal = activeCoreProposal ?: return
        val safety = activeSafetyDecision?.takeIf { it.allowed } ?: return
        executionState = executionState.copy(
            status = ExecutionStatus.EXECUTING,
            sdkAck = null,
            readbackEv = null,
            errorMessage = null,
            isMock = false,
        )
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    cameraController.executeConfirmed(
                        proposal.toCameraProposal(),
                        safety.toCameraSafetyDecision(proposal),
                        userConfirmed = true,
                    )
                }
                executionState = executionState.copy(
                    beforeEv = result.before?.state?.currentEv ?: executionState.beforeEv,
                    targetEv = (result.target as? com.example.insta_auto_adjust.camera.contract.EvTarget)?.value
                        ?: executionState.targetEv,
                    sdkAck = result.sdkAcknowledged,
                    readbackEv = result.readback?.state?.currentEv,
                    status = when (result.status) {
                        com.example.insta_auto_adjust.camera.contract.ExecutionStatus.SUCCEEDED -> ExecutionStatus.SUCCESS
                        com.example.insta_auto_adjust.camera.contract.ExecutionStatus.UNKNOWN -> ExecutionStatus.UNKNOWN
                        else -> ExecutionStatus.FAILED
                    },
                    errorMessage = result.reason?.name,
                    isMock = false,
                )
            } catch (error: Throwable) {
                executionState = executionState.copy(
                    status = ExecutionStatus.FAILED,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                    isMock = false,
                )
            }
        }
    }

    /** Legacy B mock remains only as historical reference; no UI event calls it. */
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
private fun CameraConnectionState.toCameraUiState(): CameraUiState {
    val cameraSnapshot: CameraSnapshot? = snapshot
    val connected = phase == ConnectionPhase.CONNECTED
    return CameraUiState(
        connectionStatus = when (phase) {
            ConnectionPhase.IDLE -> ConnectionStatus.DISCONNECTED
            ConnectionPhase.SCANNING,
            ConnectionPhase.CONNECTING_BLE,
            ConnectionPhase.CONNECTING_WIFI -> ConnectionStatus.CONNECTING
            ConnectionPhase.CONNECTED -> ConnectionStatus.CONNECTED
            ConnectionPhase.FAILED -> ConnectionStatus.ERROR
        },
        connectedCameraName = connectedCameraName,
        mode = cameraSnapshot?.state?.mode,
        currentEv = cameraSnapshot?.state?.currentEv,
        supportedEv = cameraSnapshot?.capabilities?.supportedEv.orEmpty(),
        frameSource = cameraSnapshot?.state?.frameSource?.name,
        dataSource = if (connected) DataSource.REAL else DataSource.UNAVAILABLE,
        errorMessage = if (phase == ConnectionPhase.FAILED) message else null,
    )
}

private fun CameraSnapshot.toCoreCameraState() = CameraState(
    connectionEpoch = state.connectionEpoch,
    mode = state.mode,
    exposureProgram = state.exposureProgram.toCore(),
    currentEv = state.currentEv,
    currentIso = state.currentIso,
    currentShutterSpeed = state.currentShutterSpeed?.let { ShutterSpeed(it.numerator, it.denominator) },
    currentWhiteBalance = state.currentWhiteBalance,
    isWorking = state.isWorking,
    isPreRecording = state.isPreRecording,
    isBusy = state.isBusy,
    recordingState = state.recordingState.toCore(),
    frameSource = state.frameSource.toCore(),
    capabilityRevision = state.capabilityRevision,
)

private fun CameraSnapshot.toCoreCapabilities() = CameraCapabilities(
    supportedEv = capabilities.supportedEv,
    supportedShutterSpeed = capabilities.supportedShutterSpeed.map { ShutterSpeed(it.numerator, it.denominator) },
    supportedIso = capabilities.supportedIso,
    supportedWhiteBalance = capabilities.supportedWhiteBalance,
    supportedExposurePrograms = capabilities.supportedExposurePrograms.map { it.toCore() },
    supportParam = capabilities.supportParam,
    capabilityRevision = capabilities.capabilityRevision,
    capturedAtEpochMs = capabilities.capturedAtEpochMs,
)

private fun com.example.insta_auto_adjust.camera.contract.ExposureProgram.toCore() = when (this) {
    com.example.insta_auto_adjust.camera.contract.ExposureProgram.AUTO -> ExposureProgram.AUTO
    com.example.insta_auto_adjust.camera.contract.ExposureProgram.MANUAL -> ExposureProgram.MANUAL
    com.example.insta_auto_adjust.camera.contract.ExposureProgram.UNKNOWN -> ExposureProgram.UNKNOWN
}

private fun com.example.insta_auto_adjust.camera.contract.RecordingState.toCore() = when (this) {
    com.example.insta_auto_adjust.camera.contract.RecordingState.IDLE -> RecordingState.IDLE
    com.example.insta_auto_adjust.camera.contract.RecordingState.RECORDING -> RecordingState.RECORDING
    com.example.insta_auto_adjust.camera.contract.RecordingState.STARTING -> RecordingState.STARTING
    com.example.insta_auto_adjust.camera.contract.RecordingState.STOPPING -> RecordingState.STOPPING
    com.example.insta_auto_adjust.camera.contract.RecordingState.UNKNOWN -> RecordingState.UNKNOWN
}

private fun com.example.insta_auto_adjust.camera.contract.FrameSource.toCore() = when (this) {
    com.example.insta_auto_adjust.camera.contract.FrameSource.SDK_DECODED -> FrameSource.SDK_DECODED
    com.example.insta_auto_adjust.camera.contract.FrameSource.SDK_RENDERED_PREVIEW -> FrameSource.SDK_RENDERED_PREVIEW
    com.example.insta_auto_adjust.camera.contract.FrameSource.MANUAL_IMPORT -> FrameSource.MANUAL_IMPORT
    com.example.insta_auto_adjust.camera.contract.FrameSource.MOCK -> FrameSource.MOCK
    com.example.insta_auto_adjust.camera.contract.FrameSource.UNKNOWN -> FrameSource.UNKNOWN
}

private fun com.example.insta_auto_adjust.camera.contract.FrameSource.toCoreFrameSource(): FrameSource =
    toCore()

private fun PolicyProposal.toCameraProposal() = com.example.insta_auto_adjust.camera.contract.PolicyProposal(
    proposalId = proposalId,
    intentRevision = intentRevision,
    frameId = frameId,
    action = action.toCameraAction(),
    parameter = parameter.toCameraTarget(),
    reason = reason,
    risk = risk.toCameraRisk(),
    cost = cost,
    validUntilEpochMs = validUntilEpochMs,
    connectionEpoch = connectionEpoch,
    capabilityRevision = capabilityRevision,
    inputSource = inputSource.toCameraSource(),
)

private fun PolicyAction.toCameraAction() = when (this) {
    PolicyAction.HOLD -> com.example.insta_auto_adjust.camera.contract.PolicyAction.HOLD
    PolicyAction.EV_ONE_STEP_UP -> com.example.insta_auto_adjust.camera.contract.PolicyAction.EV_ONE_STEP_UP
    PolicyAction.EV_ONE_STEP_DOWN -> com.example.insta_auto_adjust.camera.contract.PolicyAction.EV_ONE_STEP_DOWN
    PolicyAction.SET_SHUTTER -> com.example.insta_auto_adjust.camera.contract.PolicyAction.SET_SHUTTER
    PolicyAction.SET_ISO -> com.example.insta_auto_adjust.camera.contract.PolicyAction.SET_ISO
    PolicyAction.SET_WHITE_BALANCE -> com.example.insta_auto_adjust.camera.contract.PolicyAction.SET_WHITE_BALANCE
}

private fun ParameterTarget?.toCameraTarget() = when (this) {
    null -> null
    is ParameterTarget.Ev -> com.example.insta_auto_adjust.camera.contract.EvTarget(value)
    is ParameterTarget.Iso -> com.example.insta_auto_adjust.camera.contract.IsoTarget(value)
    is ParameterTarget.Shutter -> com.example.insta_auto_adjust.camera.contract.ShutterSpeedTarget(
        com.example.insta_auto_adjust.camera.contract.ShutterSpeed(value.numerator, value.denominator),
    )
    is ParameterTarget.WhiteBalance -> com.example.insta_auto_adjust.camera.contract.WhiteBalanceTarget(value)
}

private fun RiskLevel.toCameraRisk() = when (this) {
    RiskLevel.LOW -> com.example.insta_auto_adjust.camera.contract.RiskLevel.LOW
    RiskLevel.MEDIUM -> com.example.insta_auto_adjust.camera.contract.RiskLevel.MEDIUM
    RiskLevel.HIGH -> com.example.insta_auto_adjust.camera.contract.RiskLevel.HIGH
    RiskLevel.UNKNOWN -> com.example.insta_auto_adjust.camera.contract.RiskLevel.UNKNOWN
}

private fun InputSource.toCameraSource() = when (this) {
    InputSource.REAL -> com.example.insta_auto_adjust.camera.contract.InputSource.REAL_CAMERA
    InputSource.MOCK -> com.example.insta_auto_adjust.camera.contract.InputSource.MOCK
}

private fun SafetyDecision.toCameraSafetyDecision(proposal: PolicyProposal) =
    com.example.insta_auto_adjust.camera.contract.SafetyDecision(
        allowed = allowed,
        reasonCode = reason.toCameraReason(),
        checkedProposalId = checkedProposalId ?: proposal.proposalId,
        checkedAtEpochMs = checkedAtEpochMs,
    )

private fun SafetyReason.toCameraReason() = when (this) {
    SafetyReason.ALLOWED -> com.example.insta_auto_adjust.camera.contract.SafetyReason.ALLOWED
    SafetyReason.STALE_FRAME -> com.example.insta_auto_adjust.camera.contract.SafetyReason.STALE_FRAME
    SafetyReason.STALE_INTENT -> com.example.insta_auto_adjust.camera.contract.SafetyReason.STALE_INTENT
    SafetyReason.STALE_CAPABILITY -> com.example.insta_auto_adjust.camera.contract.SafetyReason.STALE_CAPABILITY
    SafetyReason.CAMERA_BUSY -> com.example.insta_auto_adjust.camera.contract.SafetyReason.CAMERA_BUSY
    SafetyReason.RECORDING -> com.example.insta_auto_adjust.camera.contract.SafetyReason.RECORDING
    SafetyReason.UNKNOWN_CAMERA_STATE -> com.example.insta_auto_adjust.camera.contract.SafetyReason.UNKNOWN_CAMERA_STATE
    SafetyReason.ILLEGAL_TARGET -> com.example.insta_auto_adjust.camera.contract.SafetyReason.ILLEGAL_TARGET
    SafetyReason.DUPLICATE_COMMAND -> com.example.insta_auto_adjust.camera.contract.SafetyReason.DUPLICATE_COMMAND
    SafetyReason.MODEL_UNAVAILABLE -> com.example.insta_auto_adjust.camera.contract.SafetyReason.MODEL_UNAVAILABLE
    SafetyReason.USER_LOCKED -> com.example.insta_auto_adjust.camera.contract.SafetyReason.USER_LOCKED
    SafetyReason.CONNECTION_CHANGED -> com.example.insta_auto_adjust.camera.contract.SafetyReason.CONNECTION_CHANGED
    SafetyReason.UNSUPPORTED_PARAMETER -> com.example.insta_auto_adjust.camera.contract.SafetyReason.UNSUPPORTED_PARAMETER
    SafetyReason.NO_ACTION, SafetyReason.EXPIRED_PROPOSAL, SafetyReason.MISSING_COMMAND_ID ->
        com.example.insta_auto_adjust.camera.contract.SafetyReason.ILLEGAL_TARGET
}
