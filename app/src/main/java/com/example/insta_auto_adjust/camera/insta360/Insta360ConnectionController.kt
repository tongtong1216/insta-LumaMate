package com.example.insta_auto_adjust.camera.insta360

import android.content.Context
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.sdk.camera.InstaCameraSDK
import com.arashivision.sdk.camera.api.CameraCapture
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.param.listener.AuthorizationListener
import com.arashivision.sdk.camera.api.param.listener.CaptureStatusListener
import com.arashivision.sdk.camera.api.param.listener.DisconnectListener
import com.arashivision.sdk.camera.core.callback.BleScanCallback
import com.arashivision.sdk.camera.core.model.ConnectType
import com.arashivision.sdk.camera.core.model.FunctionMode
import com.arashivision.sdk.camera.core.model.authorization.AuthorizationOperationType
import com.arashivision.sdk.camera.core.model.authorization.AuthorizationResult
import com.arashivision.sdk.camera.core.model.capture.CameraCaptureStatus
import com.arashivision.sdk.camera.core.model.option.WiFiData
import com.example.insta_auto_adjust.camera.contract.ExecutionResult
import com.example.insta_auto_adjust.camera.contract.CameraAdapter
import com.example.insta_auto_adjust.camera.contract.CameraSnapshot
import com.example.insta_auto_adjust.camera.contract.FrameSource
import com.example.insta_auto_adjust.camera.contract.PolicyProposal
import com.example.insta_auto_adjust.camera.contract.RecordingState
import com.example.insta_auto_adjust.camera.contract.SafetyDecision
import com.example.insta_auto_adjust.camera.preview.CameraPreviewController
import com.example.insta_auto_adjust.camera.preview.PreviewUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class ConnectionPhase {
    IDLE,
    SCANNING,
    CONNECTING_BLE,
    CONNECTING_WIFI,
    CONNECTED,
    FAILED,
}

/** Connection data for the app/B layer. It is state, not a UI implementation. */
data class CameraConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val message: String = "等待连接",
    val scannedDevices: List<BleCameraDevice> = emptyList(),
    val connectedCameraName: String? = null,
    val authorizationMessage: String? = null,
    val snapshot: CameraSnapshot? = null,
)

data class BleCameraDevice(
    val name: String,
    val address: String,
    internal val sdkDevice: BleDeviceCore,
)

/**
 * Minimal, real GO Ultra connection flow. It owns the SDK device and exposes snapshots only from
 * [Insta360CameraSnapshotReader], never from a presentation-layer cache.
 */
class Insta360ConnectionController(context: Context) : CameraAdapter, CameraPreviewController {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionContext = CameraSessionContext()
    private var initialized = false
    private var cameraDevice: CameraDevice? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var scanDevice: CameraDevice? = null
    private var connectionJob: Job? = null
    private var snapshotPollingJob: Job? = null
    private var snapshotReadInProgress = false
    private var attachedCapture: CameraCapture? = null
    private var captureRuntimeState = CaptureRuntimeState()
    private val snapshotReader = Insta360CameraSnapshotReader(
        deviceProvider = { cameraDevice },
        sessionContext = sessionContext,
        captureRuntimeProvider = { captureRuntimeState },
    )
    private val commandExecutor = Insta360CameraCommandExecutor(
        deviceProvider = { cameraDevice },
        snapshotReader = snapshotReader,
    )
    private val previewController = Insta360CameraPreviewController(
        application = appContext as Application,
        deviceProvider = { cameraDevice },
        sessionContext = sessionContext,
    )

    private val _state = MutableStateFlow(CameraConnectionState())
    val state: StateFlow<CameraConnectionState> = _state.asStateFlow()

    override val previewState: StateFlow<PreviewUiState>
        get() = previewController.previewState

    private val captureStatusListener = object : CaptureStatusListener {
        override fun onCaptureStarting(functionMode: FunctionMode) =
            updateRecordingState(functionMode.toRecordingState(RecordingState.STARTING))

        override fun onCaptureWorking(functionMode: FunctionMode) =
            updateRecordingState(functionMode.toRecordingState(RecordingState.RECORDING))

        override fun onCaptureStopping(functionMode: FunctionMode) =
            updateRecordingState(functionMode.toRecordingState(RecordingState.STOPPING))

        override fun onCaptureFinish(functionMode: FunctionMode, filePaths: List<String>) =
            updateRecordingState(RecordingState.IDLE)

        override fun onCaptureError(functionMode: FunctionMode, throwable: Throwable) =
            updateRecordingState(RecordingState.UNKNOWN)

        override fun onCaptureTimeChanged(functionMode: FunctionMode, captureTime: Long) = Unit

        override fun onCaptureCountChanged(functionMode: FunctionMode, captureCount: Int) = Unit

        override fun onCaptureSubStatusChanged(
            functionMode: FunctionMode,
            subStatus: CameraCaptureStatus.SubStatus,
        ) = Unit
    }

    private val disconnectListener = object : DisconnectListener {
        override fun onDisconnect(throwable: Throwable?) {
            handleDisconnected(throwable?.message ?: "相机已断开连接")
        }
    }

    private val authorizationListener = object : AuthorizationListener {
        override fun onAuthorizationResult(
            operationType: AuthorizationOperationType,
            result: AuthorizationResult,
        ) {
            _state.update {
                it.copy(authorizationMessage = "蓝牙授权结果：$result（$operationType）")
            }
        }
    }

    /** Must be called only after the runtime Bluetooth permissions were granted. */
    fun initializeAndScan() {
        if (!initialized) {
            InstaCameraSDK.init(appContext as Application) {
                cacheDir = appContext.externalCacheDir?.absolutePath
            }
            initialized = true
        }
        scan()
    }

    private fun scan() {
        scanDevice?.stopScan()
        val scanner = CameraDevice.get(ConnectType.BLE)
        scanDevice = scanner
        _state.value = CameraConnectionState(
            phase = ConnectionPhase.SCANNING,
            message = "正在扫描蓝牙相机…",
        )
        scanner.scan(
            SCAN_TIMEOUT_MS,
            object : BleScanCallback {
                override fun onStarted() = Unit

                override fun onScanning(bleDevice: BleDeviceCore) {
                    addScannedDevice(bleDevice)
                }

                override fun onFinished(bleDeviceList: List<BleDeviceCore>) {
                    _state.update {
                        // The user may have selected a camera before the scan timeout. Do not
                        // let this late callback overwrite a connection that is already running.
                        if (it.phase != ConnectionPhase.SCANNING) {
                            it
                        } else {
                            it.copy(
                                phase = ConnectionPhase.IDLE,
                                message = "扫描完成，请选择 GO Ultra",
                                scannedDevices = bleDeviceList.map { device -> device.toDiscoveredDevice() },
                            )
                        }
                    }
                }

                override fun onError(throwable: Throwable) {
                    _state.update {
                        it.copy(
                            phase = ConnectionPhase.FAILED,
                            message = "蓝牙扫描失败：${throwable.message ?: "未知错误"}",
                        )
                    }
                }
            },
        )
    }

    fun connectViaBluetoothWifi(device: BleCameraDevice) {
        connectionJob?.cancel()
        scanDevice?.stopScan()
        scanDevice = null
        connectionJob = scope.launch {
            _state.update {
                it.copy(
                    phase = ConnectionPhase.CONNECTING_BLE,
                    message = "正在通过蓝牙连接 ${device.name}…",
                    authorizationMessage = null,
                    snapshot = null,
                )
            }
            val bleCamera = CameraDevice.get(ConnectType.BLE)
            try {
                bleCamera.connect(device.sdkDevice, false).getOrThrow()
                ensureAccessPointMode(bleCamera)
                val wifiData = bleCamera.system.getWifiData().getOrThrow()
                _state.update { it.copy(phase = ConnectionPhase.CONNECTING_WIFI, message = "正在连接相机 Wi‑Fi…") }
                val network = requestCameraNetwork(wifiData.ssid, wifiData.pwd)
                    ?: error("系统未能连接到相机 Wi‑Fi")
                if (!connectivityManager.bindProcessToNetwork(network)) {
                    error("无法将应用网络绑定到相机 Wi‑Fi")
                }
                bleCamera.release()

                val wifiCamera = CameraDevice.get(ConnectType.WIFI)
                wifiCamera.connect(network.networkHandle).getOrThrow()
                attachConnectedCamera(wifiCamera, device.name)
            } catch (error: Throwable) {
                runCatching { bleCamera.release() }
                clearNetworkBinding()
                _state.update {
                    it.copy(
                        phase = ConnectionPhase.FAILED,
                        message = "连接失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /** Requests the authorization flow required by GO 3S and GO Ultra. */
    fun requestBleAuthorization() {
        val device = cameraDevice ?: return
        scope.launch {
            device.registerAuthorizationListener(authorizationListener)
            val result = device.checkAuthorization()
            _state.update {
                it.copy(authorizationMessage = "蓝牙授权状态：${result.getOrNull() ?: result.exceptionOrNull()?.message}")
            }
            refreshSnapshot()
        }
    }

    fun refreshSnapshot() {
        scope.launch {
            readAndPublishSnapshot()
        }
    }

    /** Reads the camera directly and publishes the same fresh snapshot for B if it is observing. */
    override suspend fun readSnapshot(): CameraSnapshot =
        snapshotReader.readSnapshot().also(::publishSnapshot)

    /**
     * Formal C → B → A boundary. B must pass true only after the user confirms the proposal.
     * The adapter revalidates expiration, session epoch, and capability revision before writing.
     */
    override suspend fun executeConfirmed(
        proposal: PolicyProposal,
        safetyDecision: SafetyDecision,
        userConfirmed: Boolean,
    ): ExecutionResult = commandExecutor
        .executeConfirmed(proposal, safetyDecision, userConfirmed)
        .also(::publishExecutionReadback)

    private fun publishExecutionReadback(result: ExecutionResult) {
        result.readback?.let(::publishSnapshot)
    }

    private fun publishSnapshot(snapshot: CameraSnapshot) {
        _state.update { it.copy(snapshot = snapshot) }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        snapshotPollingJob?.cancel()
        snapshotPollingJob = null
        previewController.onCameraDisconnected("已断开连接")
        val device = cameraDevice
        cameraDevice = null
        detachCaptureStatusListener()
        sessionContext.markDisconnected()
        _state.value = CameraConnectionState(message = "已断开连接")
        scope.launch {
            device?.unregisterAuthorizationListener(authorizationListener)
            device?.unregisterDisconnectListener(disconnectListener)
            runCatching { device?.release() }
            clearNetworkBinding()
        }
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    override fun attach(container: android.view.ViewGroup) = previewController.attach(container)

    override fun start() = previewController.start()

    override fun stop() = previewController.stop()

    override fun detach() = previewController.detach()

    private suspend fun ensureAccessPointMode(bleCamera: CameraDevice) {
        val currentMode = bleCamera.system.fetchWifiData().getOrNull()?.mode
        if (currentMode == WiFiData.Mode.AP) return
        bleCamera.system.setWifiMode(WiFiData.Mode.AP, "").getOrThrow()
        repeat(AP_MODE_POLL_COUNT) {
            if (bleCamera.system.fetchWifiData().getOrNull()?.mode == WiFiData.Mode.AP) return
            delay(AP_MODE_POLL_INTERVAL_MS)
        }
        error("相机未能切换到 AP Wi‑Fi 模式")
    }

    private suspend fun requestCameraNetwork(ssid: String, password: String): Network? {
        if (!wifiManager.isWifiEnabled) error("手机 Wi‑Fi 未开启")
        clearNetworkBinding()
        return suspendCancellableCoroutine { continuation ->
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(
                    WifiNetworkSpecifier.Builder()
                        .setSsid(ssid)
                        .setWpa2Passphrase(password)
                        .build(),
                )
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var completed = false

                private fun resumeOnce(network: Network?) {
                    if (completed) return
                    completed = true
                    continuation.resume(network)
                }

                override fun onAvailable(network: Network) = resumeOnce(network)

                override fun onUnavailable() = resumeOnce(null)
            }
            wifiNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback)
            continuation.invokeOnCancellation { clearNetworkBinding() }
        }
    }

    private fun attachConnectedCamera(device: CameraDevice, deviceName: String) {
        cameraDevice?.unregisterDisconnectListener(disconnectListener)
        cameraDevice = device
        device.registerDisconnectListener(disconnectListener)
        // GO Ultra 2.1.5 re-enters its capture dispatcher when this listener is registered
        // alongside parameter polling. Keep recordingState UNKNOWN until this SDK path can be
        // validated independently; C's contract explicitly treats UNKNOWN as the safe value.
        captureRuntimeState = CaptureRuntimeState()
        sessionContext.beginConnection(FrameSource.UNKNOWN)
        _state.update {
            it.copy(
                phase = ConnectionPhase.CONNECTED,
                message = "已连接：$deviceName",
                connectedCameraName = deviceName,
                scannedDevices = emptyList(),
            )
        }
        refreshSnapshot()
        startRealtimeSnapshotPolling()
    }

    private fun handleDisconnected(message: String) {
        snapshotPollingJob?.cancel()
        snapshotPollingJob = null
        previewController.onCameraDisconnected(message)
        cameraDevice = null
        detachCaptureStatusListener()
        sessionContext.markDisconnected()
        clearNetworkBinding()
        _state.value = CameraConnectionState(
            phase = ConnectionPhase.FAILED,
            message = message,
        )
    }

    private fun attachCaptureStatusListener(capture: CameraCapture) {
        if (attachedCapture === capture) return
        detachCaptureStatusListener()
        captureRuntimeState = CaptureRuntimeState()
        attachedCapture = capture
        capture.registerCaptureStatusListener(captureStatusListener)
    }

    private fun detachCaptureStatusListener() {
        attachedCapture?.unregisterCaptureStatusListener(captureStatusListener)
        attachedCapture = null
        captureRuntimeState = CaptureRuntimeState()
    }

    private fun updateRecordingState(recordingState: RecordingState) {
        captureRuntimeState = captureRuntimeState.copy(recordingState = recordingState)
        // syncAllParams() can itself produce capture callbacks. Refreshing synchronously from
        // here would recurse back into syncAllParams and eventually overflow the stack. The
        // regular polling pass publishes this callback state within at most two seconds.
    }

    private fun FunctionMode.toRecordingState(state: RecordingState): RecordingState =
        if (name.startsWith("VIDEO")) state else RecordingState.UNKNOWN

    /**
     * The SDK exposes parameter reads as queries rather than a universal parameter-change
     * listener. Polling therefore performs a new SDK `getValue()` read on each pass;
     * it does not replay the Compose/UI value.
     */
    private fun startRealtimeSnapshotPolling() {
        snapshotPollingJob?.cancel()
        snapshotPollingJob = scope.launch {
            while (isActive && cameraDevice?.isConnected() == true) {
                readAndPublishSnapshot()
                delay(SNAPSHOT_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun readAndPublishSnapshot() {
        if (snapshotReadInProgress) return
        snapshotReadInProgress = true
        try {
            val snapshot = runCatching { snapshotReader.readSnapshot() }.getOrElse { error ->
                Log.e(LOG_TAG, "Camera snapshot read failed", error)
                _state.update {
                    it.copy(authorizationMessage = "实时读取失败：${error.message ?: "未知错误"}")
                }
                return
            }
            _state.update { it.copy(snapshot = snapshot) }
        } finally {
            snapshotReadInProgress = false
        }
    }

    private fun addScannedDevice(device: BleDeviceCore) {
        _state.update { current ->
            if (current.scannedDevices.any { it.address == device.address }) current
            else current.copy(scannedDevices = current.scannedDevices + device.toDiscoveredDevice())
        }
    }

    private fun BleDeviceCore.toDiscoveredDevice() = BleCameraDevice(
        name = name?.takeIf { it.isNotBlank() } ?: "未命名相机",
        address = address,
        sdkDevice = this,
    )

    private fun clearNetworkBinding() {
        runCatching { connectivityManager.bindProcessToNetwork(null) }
        wifiNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        wifiNetworkCallback = null
    }

    private companion object {
        const val LOG_TAG = "InstaAutoCamera"
        const val SCAN_TIMEOUT_MS = 10_000L
        const val AP_MODE_POLL_COUNT = 10
        const val AP_MODE_POLL_INTERVAL_MS = 500L
        const val SNAPSHOT_POLL_INTERVAL_MS = 2_000L
    }
}
