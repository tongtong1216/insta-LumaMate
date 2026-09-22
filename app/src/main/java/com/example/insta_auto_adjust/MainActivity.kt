package com.example.insta_auto_adjust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.insta_auto_adjust.network.DBackendSceneAnalysisClient
import com.example.insta_auto_adjust.network.DebugFrameImageFactory
import com.example.insta_auto_adjust.policy.FrontendPolicyBridge
import com.example.insta_auto_adjust.presentation.AppScreen
import com.example.insta_auto_adjust.presentation.CameraUiState
import com.example.insta_auto_adjust.presentation.ConnectionStatus
import com.example.insta_auto_adjust.presentation.DataSource
import com.example.insta_auto_adjust.presentation.ExecutionStatus
import com.example.insta_auto_adjust.presentation.ExecutionUiState
import com.example.insta_auto_adjust.presentation.PolicyProposalUi
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
import com.lightpilot.core.model.ParameterTarget
import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.model.PolicyProposal
import com.lightpilot.core.model.SceneSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val policyBridge = FrontendPolicyBridge()
    private val sceneDataSource = V1SceneSemanticDataSource(
        client = DBackendSceneAnalysisClient(D_BACKEND_BASE_URL)
    )

    private var currentScreen by mutableStateOf(AppScreen.CONNECTION)
    private var intentRevision = 0L
    private var frameSequence = 0L
    private var activeCoreProposal: PolicyProposal? = null

    private var cameraState by mutableStateOf(CameraUiState())
    private var shootingState by mutableStateOf(ShootingUiState())
    private var executionState by mutableStateOf(ExecutionUiState())
    private var reportState by mutableStateOf(ReportUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            InstaAutoAdjustTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        AppScreen.CONNECTION -> ConnectionScreen(
                            cameraState = cameraState,
                            onConnectClick = ::handleConnectionClick,
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
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )

                        AppScreen.EXECUTION -> ExecutionScreen(
                            executionState = executionState,
                            onExecuteClick = ::handleMockExecution,
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
        cameraState = CameraUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            mode = "VIDEO",
            currentEv = 0.0,
            supportedEv = listOf(-2.0, -1.0, 0.0, 1.0, 2.0),
            dataSource = DataSource.MOCK,
            errorMessage = null
        )
        currentScreen = AppScreen.SHOOTING
    }

    private fun handleIntentSelected(intent: ShootingIntent) {
        shootingState = shootingState.copy(
            selectedIntent = intent,
            userIntent = null,
            isAnalyzing = false,
            visionMetrics = null,
            sceneRisk = null,
            proposal = null,
            proposalDecision = null
        )
        activeCoreProposal = null
    }

    private fun handleIntentTextChange(text: String) {
        shootingState = shootingState.copy(
            intentInputText = text,
            userIntent = null,
            isAnalyzing = false,
            visionMetrics = null,
            sceneRisk = null,
            proposal = null,
            proposalDecision = null
        )
        activeCoreProposal = null
    }

    private fun handleAnalysis() {
        shootingState = shootingState.copy(isAnalyzing = true)

        val now = System.currentTimeMillis()
        val revision = ++intentRevision
        val frameId = (++frameSequence).toString()
        val stateSnapshot = shootingState
        val draft = policyBridge.prepare(
            shootingState = stateSnapshot,
            revision = revision,
            frameId = frameId,
            nowEpochMs = now
        )

        lifecycleScope.launch {
            val semantic = withContext(Dispatchers.IO) {
                readSceneSemanticFromD(
                    shootingState = stateSnapshot,
                    revision = revision,
                    frameId = frameId,
                    nowEpochMs = now,
                    draft = draft
                )
            }
            val analysis = policyBridge.propose(
                shootingState = stateSnapshot,
                draft = draft,
                semantic = semantic,
                nowEpochMs = now,
                supportedEv = cameraState.supportedEv
            )
            val proposal = analysis.coreProposal
            activeCoreProposal = proposal

            shootingState = shootingState.copy(
                userIntent = analysis.userIntentUi,
                isAnalyzing = false,
                visionMetrics = analysis.visionMetricsUi,
                sceneRisk = analysis.sceneRisk,
                proposal = analysis.proposalUi,
                proposalDecision = if (proposal.action == PolicyAction.HOLD) {
                    ProposalDecision.HELD
                } else {
                    ProposalDecision.PENDING
                }
            )
        }
    }

    private fun readSceneSemanticFromD(
        shootingState: ShootingUiState,
        revision: Long,
        frameId: String,
        nowEpochMs: Long,
        draft: com.example.insta_auto_adjust.policy.FrontendPolicyDraft
    ): SceneSemantic {
        return try {
            val request = AnalyzeSceneRequest(
                frameId = frameId.toLong(),
                intentRevision = revision,
                intent = draft.userIntent.sourceText ?: currentIntentText(),
                imageBase64 = DebugFrameImageFactory.createBase64Jpeg(
                    shootingState.selectedIntent
                ),
                metrics = AnalyzeSceneMetrics(
                    subjectBrightness = draft.metrics.subjectBrightness,
                    highlightRatio = draft.metrics.highlightRatio,
                    darkRatio = draft.metrics.darkRatio
                )
            )
            sceneDataSource.readSemantic(
                request = request,
                nowEpochMs = nowEpochMs
            )
        } catch (error: RuntimeException) {
            unavailableSceneSemantic(
                frameId = frameId,
                revision = revision,
                nowEpochMs = nowEpochMs,
                reason = "d_backend_request_failed:${error.javaClass.simpleName}"
            )
        } catch (error: java.io.IOException) {
            unavailableSceneSemantic(
                frameId = frameId,
                revision = revision,
                nowEpochMs = nowEpochMs,
                reason = "d_backend_request_failed:${error.javaClass.simpleName}"
            )
        }
    }

    private fun unavailableSceneSemantic(
        frameId: String,
        revision: Long,
        nowEpochMs: Long,
        reason: String
    ): SceneSemantic {
        return SceneSemantic(
            available = false,
            scene = null,
            subjectType = null,
            brightRegionType = null,
            coloredLight = null,
            uncertainty = 1.0f,
            reason = reason,
            sourceFrameId = frameId,
            receivedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = null,
            intentRevision = revision,
            uncertaintyNotes = listOf("android_http_failed"),
            analysisStatus = "unavailable"
        )
    }

    private fun handleMockAnalysis() {
        val now = System.currentTimeMillis()
        val analysis = policyBridge.analyze(
            shootingState = shootingState,
            revision = ++intentRevision,
            frameId = (++frameSequence).toString(),
            supportedEv = cameraState.supportedEv,
            nowEpochMs = now
        )
        val proposal = analysis.coreProposal
        activeCoreProposal = proposal

        shootingState = shootingState.copy(
            userIntent = analysis.userIntentUi,
            isAnalyzing = false,
            visionMetrics = analysis.visionMetricsUi,
            sceneRisk = analysis.sceneRisk,
            proposal = analysis.proposalUi,
            proposalDecision = if (proposal.action == PolicyAction.HOLD) {
                ProposalDecision.HELD
            } else {
                ProposalDecision.PENDING
            }
        )
    }

    private fun handleAcceptProposal() {
        val proposal = activeCoreProposal ?: return
        if (proposal.action == PolicyAction.HOLD) {
            handleHoldProposal()
            return
        }

        val targetEv = (proposal.parameter as? ParameterTarget.Ev)?.value
        shootingState = shootingState.copy(
            proposalDecision = ProposalDecision.ACCEPTED
        )
        executionState = ExecutionUiState(
            proposalId = proposal.proposalId,
            beforeEv = FrontendPolicyBridge.MOCK_BASE_EV,
            targetEv = targetEv,
            sdkAck = null,
            readbackEv = null,
            status = ExecutionStatus.IDLE,
            errorMessage = null,
            isMock = true
        )
        currentScreen = AppScreen.EXECUTION
    }

    private fun handleHoldProposal() {
        shootingState = shootingState.copy(
            proposalDecision = ProposalDecision.HELD
        )
    }

    private fun handleMockExecution() {
        val targetEv = executionState.targetEv
        executionState = executionState.copy(
            sdkAck = true,
            readbackEv = targetEv,
            status = ExecutionStatus.SUCCESS,
            errorMessage = null,
            isMock = true
        )
    }

    private fun handleOpenReport() {
        reportState = ReportUiState(
            proposalId = executionState.proposalId.orEmpty(),
            intentText = currentIntentText(),
            beforeEv = executionState.beforeEv,
            targetEv = executionState.targetEv,
            sdkAck = executionState.sdkAck,
            readbackEv = executionState.readbackEv,
            isMock = true,
            effectObservation = "当前为 Mock 执行回读，只验证 B 前端与 C 策略链路。"
        )
        currentScreen = AppScreen.REPORT
    }

    private fun handleFinishReport() {
        shootingState = ShootingUiState()
        executionState = ExecutionUiState()
        reportState = ReportUiState()
        activeCoreProposal = null
        currentScreen = AppScreen.SHOOTING
    }

    private fun currentIntentText(): String {
        return shootingState.intentInputText.ifBlank {
            when (shootingState.selectedIntent) {
                ShootingIntent.SUBJECT_PRIORITY -> "主体优先"
                ShootingIntent.BALANCED -> "整体平衡"
                ShootingIntent.HIGHLIGHT_PRIORITY -> "高光优先"
                ShootingIntent.STABLE_EXPOSURE -> "稳定曝光"
            }
        }
    }

    private companion object {
        const val D_BACKEND_BASE_URL = "http://127.0.0.1:8000"
    }
}
