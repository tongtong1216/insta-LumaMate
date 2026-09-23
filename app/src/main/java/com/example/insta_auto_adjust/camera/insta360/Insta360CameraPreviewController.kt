package com.example.insta_auto_adjust.camera.insta360

import android.app.Application
import android.util.Log
import android.view.ViewGroup
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.preview.CameraStreamListener
import com.arashivision.sdk.camera.api.preview.PreviewStreamParamsUpdate
import com.arashivision.sdk.common.exception.InstaException
import com.arashivision.sdk.media.api.listener.PlayerViewListener
import com.arashivision.sdk.media.api.params.PreviewParams
import com.arashivision.sdk.media.player.preview.InstaCapturePlayerView
import com.example.insta_auto_adjust.camera.contract.FrameSource
import com.example.insta_auto_adjust.camera.preview.CameraPreviewController
import com.example.insta_auto_adjust.camera.preview.PreviewPhase
import com.example.insta_auto_adjust.camera.preview.PreviewUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SDK-owned renderer for the UI preview contract.
 *
 * The only supported output is the SDK player hosted in the ViewGroup supplied by B. C never sees
 * the player, its pipeline, or the stream callbacks. The nonce guards asynchronous SDK callbacks
 * that arrive after a view is detached, a stream is stopped, or a camera session changes.
 */
internal class Insta360CameraPreviewController(
    private val application: Application,
    private val deviceProvider: () -> CameraDevice?,
    private val sessionContext: CameraSessionContext,
    private val clock: () -> Long = System::currentTimeMillis,
) : CameraPreviewController {
    private val _previewState = MutableStateFlow(PreviewUiState())
    override val previewState: StateFlow<PreviewUiState> = _previewState.asStateFlow()

    private var host: ViewGroup? = null
    private var player: InstaCapturePlayerView? = null
    private var activeDevice: CameraDevice? = null
    private var streamStarted = false
    private var generation = 0L

    override fun attach(container: ViewGroup) {
        if (host === container && player != null) return

        detach()
        host = container
        player = InstaCapturePlayerView(container.context).also { previewPlayer ->
            container.addView(
                previewPlayer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    override fun start() {
        if (streamStarted) return
        val previewPlayer = player ?: return fail("预览容器尚未绑定")
        val device = deviceProvider()
            ?.takeIf { it.isConnected() }
            ?: return disconnected("相机未连接，无法启动预览")

        val requestGeneration = ++generation
        activeDevice = device
        _previewState.value = PreviewUiState(phase = PreviewPhase.STARTING)
        runCatching {
            device.preview.init(application)
            device.preview.registerCameraStreamListener(streamListener)
            streamStarted = true
            device.preview.startStream()
        }.onFailure { error ->
            streamStarted = false
            activeDevice = null
            releaseStream(device)
            fail("启动相机预览失败", error)
        }

        // The stream callback remains asynchronous. Referencing this value here makes the
        // generation created for this request explicit and prevents accidental reuse later.
        check(requestGeneration == generation || !streamStarted)
        check(previewPlayer === player || !streamStarted)
    }

    override fun stop() {
        if (!streamStarted && _previewState.value.phase == PreviewPhase.DISCONNECTED) return
        stopInternal(
            terminalState = PreviewUiState(),
            clearFrameSource = true,
        )
    }

    override fun detach() {
        stop()
        generation += 1
        player?.let { previewPlayer ->
            previewPlayer.setListener(null)
            runCatching { previewPlayer.destroy() }
            (previewPlayer.parent as? ViewGroup)?.removeView(previewPlayer)
        }
        player = null
        host = null
    }

    /** Called by the connection owner before its CameraDevice is released. */
    fun onCameraDisconnected(message: String) {
        stopInternal(
            terminalState = PreviewUiState(
                phase = PreviewPhase.DISCONNECTED,
                message = message,
            ),
            clearFrameSource = true,
        )
    }

    private val streamListener = object : CameraStreamListener {
        override fun onOpening() = Unit

        override fun onOpened() {
            val callbackGeneration = generation
            val device = activeDevice ?: return
            val previewPlayer = player ?: return
            previewPlayer.post {
                if (!isCurrent(callbackGeneration, device, previewPlayer)) return@post
                runCatching {
                    previewPlayer.destroyRender()
                    previewPlayer.setListener(playerListener(callbackGeneration, device, previewPlayer))
                    previewPlayer.prepare(PreviewParams())
                    previewPlayer.play()
                    device.preview.requestStreamIframe()
                }.onFailure { error ->
                    if (isCurrent(callbackGeneration, device, previewPlayer)) {
                        fail("初始化预览渲染器失败", error)
                    }
                }
            }
        }

        override fun onIdle() = Unit

        override fun onParamsChanged(paramsUpdate: PreviewStreamParamsUpdate) {
            val previewPlayer = player ?: return
            if (!streamStarted || paramsUpdate.previewWidth <= 0 ||
                paramsUpdate.previewHeight <= 0 || paramsUpdate.previewFps <= 0
            ) {
                return
            }
            previewPlayer.setPreviewResolution(paramsUpdate.previewWidth, paramsUpdate.previewHeight)
            previewPlayer.setFps(paramsUpdate.previewFps)
        }
    }

    private fun playerListener(
        callbackGeneration: Long,
        device: CameraDevice,
        previewPlayer: InstaCapturePlayerView,
    ) = object : PlayerViewListener {
        override fun onLoadingStatusChanged(isLoading: Boolean) = Unit

        override fun onLoadingFinish() {
            if (!isCurrent(callbackGeneration, device, previewPlayer)) return
            val pipeline = previewPlayer.getPipeline()
                ?: return fail("预览渲染管线不可用")
            runCatching {
                device.preview.setPipeline(pipeline)
                device.preview.requestStreamIframe()
            }.onFailure { error ->
                if (isCurrent(callbackGeneration, device, previewPlayer)) {
                    fail("绑定预览渲染管线失败", error)
                }
            }
        }

        override fun onFail(exception: InstaException) {
            if (isCurrent(callbackGeneration, device, previewPlayer)) {
                fail("预览渲染失败", exception)
            }
        }

        override fun onFirstFrameRendered() {
            if (!isCurrent(callbackGeneration, device, previewPlayer)) return
            sessionContext.updateFrameSource(FrameSource.SDK_RENDERED_PREVIEW)
            _previewState.value = PreviewUiState(
                phase = PreviewPhase.RENDERING,
                renderedAtEpochMs = clock(),
            )
        }

        override fun onReleaseCameraPipeline() {
            if (isCurrent(callbackGeneration, device, previewPlayer)) {
                runCatching { device.preview.setPipeline(null) }
            }
        }
    }

    private fun stopInternal(
        terminalState: PreviewUiState,
        clearFrameSource: Boolean,
    ) {
        generation += 1
        val device = activeDevice ?: deviceProvider()
        val wasStreaming = streamStarted
        streamStarted = false
        activeDevice = null
        if (clearFrameSource) sessionContext.updateFrameSource(FrameSource.UNKNOWN)

        if (wasStreaming) _previewState.value = PreviewUiState(phase = PreviewPhase.STOPPING)
        device?.let(::releaseStream)
        runCatching { player?.destroyRender() }
        _previewState.value = terminalState
    }

    private fun releaseStream(device: CameraDevice) {
        runCatching {
            device.preview.unregisterCameraStreamListener(streamListener)
            device.preview.setPipeline(null)
            device.preview.stopStream()
        }.onFailure { error ->
            Log.w(LOG_TAG, "Stop preview stream failed", error)
        }
    }

    private fun isCurrent(
        callbackGeneration: Long,
        device: CameraDevice,
        previewPlayer: InstaCapturePlayerView,
    ): Boolean =
        streamStarted &&
            generation == callbackGeneration &&
            activeDevice === device &&
            player === previewPlayer

    private fun disconnected(message: String) {
        sessionContext.updateFrameSource(FrameSource.UNKNOWN)
        _previewState.value = PreviewUiState(phase = PreviewPhase.DISCONNECTED, message = message)
    }

    private fun fail(message: String, error: Throwable? = null) {
        if (error != null) Log.w(LOG_TAG, message, error)
        stopInternal(
            terminalState = PreviewUiState(phase = PreviewPhase.FAILED, message = message),
            clearFrameSource = true,
        )
    }

    private companion object {
        const val LOG_TAG = "InstaAutoCamera"
    }
}
