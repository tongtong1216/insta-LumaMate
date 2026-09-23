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
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.insta_auto_adjust.camera.contract.CameraSnapshot
import com.example.insta_auto_adjust.camera.insta360.ConnectionPhase
import com.example.insta_auto_adjust.camera.insta360.Insta360ConnectionController
import com.example.insta_auto_adjust.camera.insta360.Insta360RealtimePreviewFrameSource
import com.example.insta_auto_adjust.network.DBackendSceneAnalysisClient
import com.example.insta_auto_adjust.network.RealFrameImageReader
import com.example.insta_auto_adjust.network.RealFramePayload
import com.example.insta_auto_adjust.policy.FrontendPolicyBridge
import com.example.insta_auto_adjust.presentation.AppScreen
import com.example.insta_auto_adjust.presentation.CameraDeviceUi
import com.example.insta_auto_adjust.presentation.CameraUiState
import com.example.insta_auto_adjust.presentation.ConnectionStatus
import com.example.insta_auto_adjust.presentation.DataSource
import com.example.insta_auto_adjust.presentation.ExecutionStatus
import com.example.insta_auto_adjust.presentation.ExecutionUiState
import com.example.insta_auto_adjust.presentation.ProposalDecision
import com.example.insta_auto_adjust.presentation.ReportUiState
import com.example.insta_auto_adjust.presentation.ShootingIntent
import com.example.insta_auto_adjust.presentation.ShootingUiState
import com.example.insta_auto_adjust.ui.screen.ConnectionScreen
import com.example.insta_auto_adjust.ui.screen.ExecutionScreen
import com.example.insta_auto_adjust.ui.screen.ReportScreen
import com.example.insta_auto_adjust.ui.screen.ShootingScreen
import com.example.insta_auto_adjust.ui.theme.InstaAutoAdjustTheme
import com.lightpilot.core.contract.v1.AnalyzeSceneMetrics
import com.lightpilot.core.contract.v1.AnalyzeSceneRequest
import com.lightpilot.core.contract.v1.V1SceneSemanticDataSource
import com.lightpilot.core.model.CameraCapabilities
import com.lightpilot.core.model.CameraState
import com.lightpilot.core.model.ExecutionMode
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
import com.lightpilot.core.policy.SafetyGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val policyBridge = FrontendPolicyBridge()
    private val safetyGuard = SafetyGuard(
        maxFrameAgeMs = FrontendPolicyBridge.REAL_ANALYSIS_WINDOW_MS
    )
    private val cameraController by lazy { Insta360ConnectionController(this) }
    private val previewFrameSource by lazy {
        Insta360RealtimePreviewFrameSource(
            context = this,
            cameraDeviceProvider = { cameraController.connectedSdkDevice() },
            onDecodedFrameSource = { cameraController.markDecodedPreviewAvailable() }
        )
    }
    private val sceneDataSource = V1SceneSemanticDataSource(
        client = DBackendSceneAnalysisClient(D_BACKEND_BASE_URL)
    )

    private var currentScreen by mutableStateOf(AppScreen.CONNECTION)
    private var intentRevision = 0L
    private var frameSequence = 0L
    private var activeCoreProposal: PolicyProposal? = null
    private var activeSafetyDecision: SafetyDecision? = null

    private var cameraState by mutableStateOf(CameraUiState())
    private var shootingState by mutableStateOf(ShootingUiState())
    private var executionState by mutableStateOf(ExecutionUiState())
    private var reportState by mutableStateOf(ReportUiState())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                cameraController.initializeAndScan()
            } else {
                cameraState = cameraState.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    dataSource = DataSource.REAL,
                    errorMessage = "Bluetooth/Wi-Fi permissions are required for GO Ultra.",
                    statusMessage = "Please grant Nearby devices permission and retry."
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            cameraController.state.collectLatest(::publishCameraConnectionState)
        }

        setContent {
            InstaAutoAdjustTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        AppScreen.CONNECTION -> ConnectionScreen(
                            cameraState = cameraState,
                            onConnectClick = ::handleConnectionClick,
                            onDeviceSelected = ::handleDeviceSelected,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )

                        AppScreen.SHOOTING -> ShootingScreen(
                            shootingState = shootingState,
                            onIntentSelected = ::handleIntentSelected,
                            onIntentTextChange = ::handleIntentTextChange,
                            onAnalyzeClick = ::handleAnalysis,
                            onAcceptProposal = ::handleAcceptProposal,
                            onHoldProposal = ::handleHoldProposal,
                            previewController = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )

                        AppScreen.EXECUTION -> ExecutionScreen(
                            executionState = executionState,
                            onExecuteClick = ::handleRealExecution,
                            onReportClick = ::handleOpenReport,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )

                        AppScreen.REPORT -> ReportScreen(
                            reportState = reportState,
                            onFinishClick = ::handleFinishReport,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    private fun handleConnectionClick() {
        when (cameraController.state.value.phase) {
            ConnectionPhase.CONNECTED -> {
                shootingState = shootingState.copy(dataSource = DataSource.REAL)
                previewFrameSource.start()
                currentScreen = AppScreen.SHOOTING
            }
            ConnectionPhase.SCANNING,
            ConnectionPhase.CONNECTING_BLE,
            ConnectionPhase.CONNECTING_WIFI -> Unit
            ConnectionPhase.IDLE,
            ConnectionPhase.FAILED -> requestCameraPermissionsAndScan()
        }
    }

    private fun handleDeviceSelected(address: String) {
        val device = cameraController.state.value.scannedDevices
            .firstOrNull { it.address == address }
        if (device == null) {
            cameraState = cameraState.copy(
                connectionStatus = ConnectionStatus.ERROR,
                errorMessage = "Selected camera is no longer available."
            )
            return
        }
        cameraController.connectViaBluetoothWifi(device)
    }

    private fun requestCameraPermissionsAndScan() {
        val missing = requiredCameraPermissions()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            cameraController.initializeAndScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredCameraPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.BLUETOOTH
            permissions += Manifest.permission.BLUETOOTH_ADMIN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return permissions.toTypedArray()
    }

    private fun publishCameraConnectionState(state: com.example.insta_auto_adjust.camera.insta360.CameraConnectionState) {
        val snapshot = state.snapshot
        val connectionStatus = when (state.phase) {
            ConnectionPhase.CONNECTED -> ConnectionStatus.CONNECTED
            ConnectionPhase.FAILED -> ConnectionStatus.ERROR
            ConnectionPhase.IDLE -> ConnectionStatus.DISCONNECTED
            ConnectionPhase.SCANNING,
            ConnectionPhase.CONNECTING_BLE,
            ConnectionPhase.CONNECTING_WIFI -> ConnectionStatus.CONNECTING
        }
        cameraState = CameraUiState(
            connectionStatus = connectionStatus,
            mode = snapshot?.state?.mode,
            currentEv = snapshot?.state?.currentEv,
            supportedEv = snapshot?.capabilities?.supportedEv.orEmpty(),
            dataSource = DataSource.REAL,
            errorMessage = if (state.phase == ConnectionPhase.FAILED) state.message else null,
            statusMessage = state.message,
            scannedDevices = state.scannedDevices.map { device ->
                CameraDeviceUi(device.name, device.address)
            }
        )
    }

    private fun handleIntentSelected(intent: ShootingIntent) {
        shootingState = shootingState.copy(
            selectedIntent = intent,
            userIntent = null,
            isAnalyzing = false,
            visionMetrics = null,
            sceneRisk = null,
            proposal = null,
            proposalExecutable = false,
            proposalBlockReason = null,
            proposalDecision = null,
            dataSource = DataSource.REAL
        )
        activeCoreProposal = null
        activeSafetyDecision = null
    }

    private fun handleIntentTextChange(text: String) {
        shootingState = shootingState.copy(
            intentInputText = text,
            userIntent = null,
            isAnalyzing = false,
            visionMetrics = null,
            sceneRisk = null,
            proposal = null,
            proposalExecutable = false,
            proposalBlockReason = null,
            proposalDecision = null,
            dataSource = DataSource.REAL
        )
        activeCoreProposal = null
        activeSafetyDecision = null
    }

    private fun handleAnalysis() {
        val snapshot = cameraController.state.value.snapshot
        if (cameraController.state.value.phase != ConnectionPhase.CONNECTED || snapshot == null) {
            shootingState = shootingState.copy(
                isAnalyzing = false,
                sceneRisk = "真实相机尚未连接或没有可读快照。"
            )
            return
        }

        val now = System.currentTimeMillis()
        val request = PendingAnalysis(
            state = shootingState,
            revision = ++intentRevision,
            frameId = (++frameSequence).toString(),
            nowEpochMs = now,
            snapshot = snapshot
        )
        shootingState = shootingState.copy(
            isAnalyzing = true,
            dataSource = DataSource.REAL,
            sceneRisk = "相机已连接；正在从 GO Ultra 实时预览流截取当前帧并发送给 D。手机不显示预览也不影响分析。"
        )
        previewFrameSource.start()
        lifecycleScope.launch {
            try {
                val previewFrame = withContext(Dispatchers.IO) {
                    previewFrameSource.awaitLatestFrame()
                }
                val payload = withContext(Dispatchers.Default) {
                    RealFrameImageReader.readJpeg(
                        jpegBytes = previewFrame.jpegBytes,
                        frameId = previewFrame.frameId,
                        nowEpochMs = previewFrame.capturedAtEpochMs,
                        source = com.lightpilot.core.model.FrameSource.SDK_DECODED
                    )
                }
                analyzeRealFrame(
                    request.copy(
                        frameId = previewFrame.frameId,
                        nowEpochMs = previewFrame.capturedAtEpochMs
                    ),
                    payload
                )
            } catch (error: Throwable) {
                shootingState = shootingState.copy(
                    isAnalyzing = false,
                    sceneRisk = "实时预览帧读取失败：${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun analyzeRealFrame(
        request: PendingAnalysis,
        frame: RealFramePayload
    ) {
        val draft = policyBridge.prepare(
            shootingState = request.state,
            revision = request.revision,
            frameId = request.frameId,
            nowEpochMs = request.nowEpochMs,
            realMetrics = frame.metrics,
            realMetricsUi = frame.metricsUi
        )
        val semantic = withContext(Dispatchers.IO) {
            readSceneSemanticFromD(
                state = request.state,
                revision = request.revision,
                frameId = request.frameId,
                nowEpochMs = request.nowEpochMs,
                draft = draft,
                imageBase64 = frame.imageBase64
            )
        }
        val freshSnapshot = withContext(Dispatchers.IO) {
            cameraController.readSnapshot()
        }
        val coreState = freshSnapshot.toCoreCameraState()
        val coreCapabilities = freshSnapshot.toCoreCapabilities()
        val analysis = policyBridge.propose(
            shootingState = request.state,
            draft = draft,
            semantic = semantic,
            nowEpochMs = System.currentTimeMillis(),
            supportedEv = coreCapabilities.supportedEv,
            cameraState = coreState,
            capabilities = coreCapabilities,
            inputSource = InputSource.REAL,
            executionMode = ExecutionMode.REAL
        )
        val proposal = analysis.coreProposal
        val safety = if (proposal.action == PolicyAction.HOLD) {
            null
        } else {
            safetyGuard.evaluate(
                proposal = proposal,
                currentState = coreState,
                capabilities = coreCapabilities,
                currentMetrics = frame.metrics,
                currentIntent = draft.userIntent,
                nowEpochMs = System.currentTimeMillis(),
                commandId = "command-${proposal.proposalId}",
                userLocked = request.state.userLocked
            )
        }
        activeCoreProposal = proposal
        activeSafetyDecision = safety

        val safetyText = safety?.let {
            "；安全检查=${if (it.allowed) "允许真实执行" else "拒绝真实执行"}(${it.reason})"
        }.orEmpty()
        shootingState = shootingState.copy(
            userIntent = analysis.userIntentUi,
            isAnalyzing = false,
            visionMetrics = analysis.visionMetricsUi,
            sceneRisk = analysis.sceneRisk + safetyText,
            proposal = analysis.proposalUi,
            proposalExecutable = safety?.allowed == true,
            proposalBlockReason = safety
                ?.takeUnless { it.allowed }
                ?.let { "实时预览帧对应的建议未通过安全检查：${it.reason}" },
            proposalDecision = if (proposal.action == PolicyAction.HOLD) {
                ProposalDecision.HELD
            } else {
                ProposalDecision.PENDING
            },
            dataSource = DataSource.REAL
        )
    }

    private fun readSceneSemanticFromD(
        state: ShootingUiState,
        revision: Long,
        frameId: String,
        nowEpochMs: Long,
        draft: com.example.insta_auto_adjust.policy.FrontendPolicyDraft,
        imageBase64: String
    ): SceneSemantic {
        return try {
            val request = AnalyzeSceneRequest(
                frameId = frameId.toLong(),
                intentRevision = revision,
                intent = draft.userIntent.sourceText ?: currentIntentText(state),
                imageBase64 = imageBase64,
                metrics = AnalyzeSceneMetrics(
                    subjectBrightness = draft.metrics.subjectBrightness,
                    highlightRatio = draft.metrics.highlightRatio,
                    darkRatio = draft.metrics.darkRatio
                )
            )
            sceneDataSource.readSemantic(request, nowEpochMs)
        } catch (error: RuntimeException) {
            unavailableSceneSemantic(frameId, revision, nowEpochMs, error)
        } catch (error: java.io.IOException) {
            unavailableSceneSemantic(frameId, revision, nowEpochMs, error)
        }
    }

    private fun unavailableSceneSemantic(
        frameId: String,
        revision: Long,
        nowEpochMs: Long,
        error: Throwable
    ): SceneSemantic {
        return SceneSemantic(
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
            analysisStatus = "unavailable"
        )
    }

    private fun handleAcceptProposal() {
        val proposal = activeCoreProposal ?: return
        val safety = activeSafetyDecision
        if (proposal.action == PolicyAction.HOLD) {
            handleHoldProposal()
            return
        }
        if (safety?.allowed != true) {
            shootingState = shootingState.copy(
                proposalDecision = ProposalDecision.PENDING,
                proposalExecutable = false,
                proposalBlockReason = safety
                    ?.let { "当前未接入实时预览帧，安全检查暂不允许真实执行：${it.reason}" }
                    ?: "安全检查未通过，当前建议不能真实执行。"
            )
            return
        }
        shootingState = shootingState.copy(proposalDecision = ProposalDecision.ACCEPTED)
        val targetEv = (proposal.parameter as? ParameterTarget.Ev)?.value
        executionState = ExecutionUiState(
            proposalId = proposal.proposalId,
            beforeEv = cameraController.state.value.snapshot?.state?.currentEv,
            targetEv = targetEv,
            sdkAck = null,
            readbackEv = null,
            status = ExecutionStatus.IDLE,
            errorMessage = null,
            isMock = false
        )
        currentScreen = AppScreen.EXECUTION
    }

    private fun handleHoldProposal() {
        shootingState = shootingState.copy(proposalDecision = ProposalDecision.HELD)
    }

    private fun handleRealExecution() {
        val proposal = activeCoreProposal ?: return
        val safety = activeSafetyDecision ?: return
        if (!safety.allowed) return
        executionState = executionState.copy(
            status = ExecutionStatus.EXECUTING,
            isMock = false,
            errorMessage = null
        )
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                cameraController.executeConfirmed(
                    proposal = proposal.toCameraProposal(),
                    safetyDecision = safety.toCameraSafetyDecision(proposal),
                    userConfirmed = true
                )
            }
            val readback = result.readback?.state?.currentEv
            executionState = executionState.copy(
                beforeEv = result.before?.state?.currentEv ?: executionState.beforeEv,
                targetEv = (result.target as? com.example.insta_auto_adjust.camera.contract.EvTarget)?.value
                    ?: executionState.targetEv,
                sdkAck = result.sdkAcknowledged,
                readbackEv = readback,
                status = when (result.status) {
                    com.example.insta_auto_adjust.camera.contract.ExecutionStatus.SUCCEEDED ->
                        ExecutionStatus.SUCCESS
                    com.example.insta_auto_adjust.camera.contract.ExecutionStatus.UNKNOWN ->
                        ExecutionStatus.UNKNOWN
                    else -> ExecutionStatus.FAILED
                },
                errorMessage = result.reason?.name,
                isMock = false
            )
        }
    }

    private fun handleOpenReport() {
        reportState = ReportUiState(
            proposalId = executionState.proposalId.orEmpty(),
            intentText = currentIntentText(shootingState),
            beforeEv = executionState.beforeEv,
            targetEv = executionState.targetEv,
            sdkAck = executionState.sdkAck,
            readbackEv = executionState.readbackEv,
            isMock = false,
            effectObservation = if (executionState.status == ExecutionStatus.SUCCESS) {
                "真实 SDK 已返回 ACK，并完成相机参数回读。画面质量仍需人工拍后对比。"
            } else {
                "真实相机执行未完成，请查看执行错误和 SDK 回读结果。"
            }
        )
        currentScreen = AppScreen.REPORT
    }

    private fun handleFinishReport() {
        shootingState = ShootingUiState(dataSource = DataSource.REAL)
        executionState = ExecutionUiState()
        reportState = ReportUiState()
        activeCoreProposal = null
        activeSafetyDecision = null
        currentScreen = AppScreen.SHOOTING
    }

    private fun currentIntentText(state: ShootingUiState = shootingState): String {
        return state.intentInputText.ifBlank {
            when (state.selectedIntent) {
                ShootingIntent.SUBJECT_PRIORITY -> "主体优先"
                ShootingIntent.BALANCED -> "整体平衡"
                ShootingIntent.HIGHLIGHT_PRIORITY -> "高光优先"
                ShootingIntent.STABLE_EXPOSURE -> "稳定曝光"
            }
        }
    }

    override fun onDestroy() {
        previewFrameSource.close()
        cameraController.close()
        super.onDestroy()
    }

    private data class PendingAnalysis(
        val state: ShootingUiState,
        val revision: Long,
        val frameId: String,
        val nowEpochMs: Long,
        val snapshot: CameraSnapshot
    )

    private companion object {
        const val D_BACKEND_BASE_URL = "http://127.0.0.1:8000"
    }

}

private fun CameraSnapshot.toCoreCameraState(): CameraState {
    return CameraState(
        connectionEpoch = state.connectionEpoch,
        mode = state.mode,
        exposureProgram = state.exposureProgram.toCore(),
        currentEv = state.currentEv,
        currentIso = state.currentIso,
        currentShutterSpeed = state.currentShutterSpeed?.let {
            ShutterSpeed(it.numerator, it.denominator)
        },
        currentWhiteBalance = state.currentWhiteBalance,
        isWorking = state.isWorking,
        isPreRecording = state.isPreRecording,
        isBusy = state.isBusy,
        recordingState = state.recordingState.toCore(),
        frameSource = state.frameSource.toCore(),
        capabilityRevision = state.capabilityRevision
    )
}

private fun CameraSnapshot.toCoreCapabilities(): CameraCapabilities {
    return CameraCapabilities(
        supportedEv = capabilities.supportedEv,
        supportedShutterSpeed = capabilities.supportedShutterSpeed.map {
            ShutterSpeed(it.numerator, it.denominator)
        },
        supportedIso = capabilities.supportedIso,
        supportedWhiteBalance = capabilities.supportedWhiteBalance,
        supportedExposurePrograms = capabilities.supportedExposurePrograms.map { it.toCore() },
        supportParam = capabilities.supportParam,
        capabilityRevision = capabilities.capabilityRevision,
        capturedAtEpochMs = capabilities.capturedAtEpochMs
    )
}

private fun com.example.insta_auto_adjust.camera.contract.ExposureProgram.toCore(): ExposureProgram =
    when (this) {
        com.example.insta_auto_adjust.camera.contract.ExposureProgram.AUTO -> ExposureProgram.AUTO
        com.example.insta_auto_adjust.camera.contract.ExposureProgram.MANUAL -> ExposureProgram.MANUAL
        com.example.insta_auto_adjust.camera.contract.ExposureProgram.UNKNOWN -> ExposureProgram.UNKNOWN
    }

private fun com.example.insta_auto_adjust.camera.contract.RecordingState.toCore(): RecordingState =
    when (this) {
        com.example.insta_auto_adjust.camera.contract.RecordingState.IDLE -> RecordingState.IDLE
        com.example.insta_auto_adjust.camera.contract.RecordingState.RECORDING -> RecordingState.RECORDING
        com.example.insta_auto_adjust.camera.contract.RecordingState.STARTING -> RecordingState.STARTING
        com.example.insta_auto_adjust.camera.contract.RecordingState.STOPPING -> RecordingState.STOPPING
        com.example.insta_auto_adjust.camera.contract.RecordingState.UNKNOWN -> RecordingState.UNKNOWN
    }

private fun com.example.insta_auto_adjust.camera.contract.FrameSource.toCore(): FrameSource =
    when (this) {
        com.example.insta_auto_adjust.camera.contract.FrameSource.SDK_DECODED -> FrameSource.SDK_DECODED
        com.example.insta_auto_adjust.camera.contract.FrameSource.SDK_RENDERED_PREVIEW ->
            FrameSource.SDK_RENDERED_PREVIEW
        com.example.insta_auto_adjust.camera.contract.FrameSource.MANUAL_IMPORT -> FrameSource.MANUAL_IMPORT
        com.example.insta_auto_adjust.camera.contract.FrameSource.MOCK -> FrameSource.MOCK
        com.example.insta_auto_adjust.camera.contract.FrameSource.UNKNOWN -> FrameSource.UNKNOWN
    }

private fun PolicyProposal.toCameraProposal(): com.example.insta_auto_adjust.camera.contract.PolicyProposal {
    return com.example.insta_auto_adjust.camera.contract.PolicyProposal(
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
        inputSource = inputSource.toCameraSource()
    )
}

private fun PolicyAction.toCameraAction(): com.example.insta_auto_adjust.camera.contract.PolicyAction =
    when (this) {
        PolicyAction.HOLD -> com.example.insta_auto_adjust.camera.contract.PolicyAction.HOLD
        PolicyAction.EV_ONE_STEP_UP -> com.example.insta_auto_adjust.camera.contract.PolicyAction.EV_ONE_STEP_UP
        PolicyAction.EV_ONE_STEP_DOWN -> com.example.insta_auto_adjust.camera.contract.PolicyAction.EV_ONE_STEP_DOWN
        PolicyAction.SET_SHUTTER -> com.example.insta_auto_adjust.camera.contract.PolicyAction.SET_SHUTTER
        PolicyAction.SET_ISO -> com.example.insta_auto_adjust.camera.contract.PolicyAction.SET_ISO
        PolicyAction.SET_WHITE_BALANCE -> com.example.insta_auto_adjust.camera.contract.PolicyAction.SET_WHITE_BALANCE
    }

private fun ParameterTarget?.toCameraTarget(): com.example.insta_auto_adjust.camera.contract.ParameterTarget? =
    when (this) {
        null -> null
        is ParameterTarget.Ev -> com.example.insta_auto_adjust.camera.contract.EvTarget(value)
        is ParameterTarget.Iso -> com.example.insta_auto_adjust.camera.contract.IsoTarget(value)
        is ParameterTarget.Shutter -> com.example.insta_auto_adjust.camera.contract.ShutterSpeedTarget(
            com.example.insta_auto_adjust.camera.contract.ShutterSpeed(value.numerator, value.denominator)
        )
        is ParameterTarget.WhiteBalance ->
            com.example.insta_auto_adjust.camera.contract.WhiteBalanceTarget(value)
    }

private fun RiskLevel.toCameraRisk(): com.example.insta_auto_adjust.camera.contract.RiskLevel =
    when (this) {
        RiskLevel.LOW -> com.example.insta_auto_adjust.camera.contract.RiskLevel.LOW
        RiskLevel.MEDIUM -> com.example.insta_auto_adjust.camera.contract.RiskLevel.MEDIUM
        RiskLevel.HIGH -> com.example.insta_auto_adjust.camera.contract.RiskLevel.HIGH
        RiskLevel.UNKNOWN -> com.example.insta_auto_adjust.camera.contract.RiskLevel.UNKNOWN
    }

private fun InputSource.toCameraSource(): com.example.insta_auto_adjust.camera.contract.InputSource =
    when (this) {
        InputSource.REAL -> com.example.insta_auto_adjust.camera.contract.InputSource.REAL_CAMERA
        InputSource.MOCK -> com.example.insta_auto_adjust.camera.contract.InputSource.MOCK
    }

private fun SafetyDecision.toCameraSafetyDecision(
    proposal: PolicyProposal
): com.example.insta_auto_adjust.camera.contract.SafetyDecision {
    return com.example.insta_auto_adjust.camera.contract.SafetyDecision(
        allowed = allowed,
        reasonCode = reason.toCameraReason(),
        checkedProposalId = checkedProposalId ?: proposal.proposalId,
        checkedAtEpochMs = checkedAtEpochMs
    )
}

private fun SafetyReason.toCameraReason(): com.example.insta_auto_adjust.camera.contract.SafetyReason =
    when (this) {
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
        SafetyReason.NO_ACTION,
        SafetyReason.EXPIRED_PROPOSAL,
        SafetyReason.MISSING_COMMAND_ID -> com.example.insta_auto_adjust.camera.contract.SafetyReason.ILLEGAL_TARGET
    }
