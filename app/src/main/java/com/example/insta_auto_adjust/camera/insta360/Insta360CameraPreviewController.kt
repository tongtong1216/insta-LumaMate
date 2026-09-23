package com.example.insta_auto_adjust.camera.insta360

import android.app.Application
import android.util.Log
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.preview.CameraStreamListener
import com.arashivision.sdk.camera.api.preview.PreviewStreamParamsUpdate
import com.arashivision.sdk.common.exception.InstaException
import com.arashivision.sdk.media.api.listener.PlayerViewListener
import com.arashivision.sdk.media.api.params.PreviewParams
import com.arashivision.sdk.media.player.preview.InstaCapturePlayerView
import com.example.insta_auto_adjust.camera.contract.FrameSource
import com.example.insta_auto_adjust.camera.diagnostics.CameraDiagnosticLogger
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
    private val diagnostics: CameraDiagnosticLogger,
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

        diagnostics.info("preview.attach", "host=${container.javaClass.simpleName}")
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
            val lifecycleOwner = container.context as? LifecycleOwner
            if (lifecycleOwner == null) {
                diagnostics.warn("preview.player.lifecycleUnavailable", container.context.javaClass.name)
            } else {
                // Required by the SDK player: without a Lifecycle it can prepare/play but never
                // finish loading, so no pipeline or first frame callback is delivered.
                previewPlayer.setLifecycle(lifecycleOwner.lifecycle)
                diagnostics.info("preview.player.lifecycleBound")
            }
        }
    }

    override fun start() {
        if (streamStarted) {
            diagnostics.info("preview.start.ignored", "stream already running")
            return
        }
        diagnostics.info("preview.start.requested")
        val previewPlayer = player ?: return fail("预览容器尚未绑定")
        val device = deviceProvider()
            ?.takeIf { it.isConnected() }
            ?: return disconnected("相机未连接，无法启动预览")

        val requestGeneration = ++generation
        activeDevice = device
        _previewState.value = PreviewUiState(phase = PreviewPhase.STARTING)
        runCatching {
            diagnostics.info("preview.stream.init")
            device.preview.init(application)
            device.preview.registerCameraStreamListener(streamListener)
            streamStarted = true
            device.preview.startStream()
            diagnostics.info("preview.stream.startRequested", "generation=$requestGeneration")
        }.onFailure { error ->
            diagnostics.error("preview.stream.startFailed", error)
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
        diagnostics.info("preview.stop.requested", "streamStarted=$streamStarted")
        stopInternal(
            terminalState = PreviewUiState(),
            clearFrameSource = true,
        )
    }

    override fun detach() {
        diagnostics.info("preview.detach.requested")
        stop()
        generation += 1
        player?.let { previewPlayer ->
            previewPlayer.setListener(null)
            runCatching { previewPlayer.destroy() }
                .onFailure { error -> diagnostics.error("preview.player.destroyFailed", error) }
            (previewPlayer.parent as? ViewGroup)?.removeView(previewPlayer)
        }
        player = null
        host = null
    }

    /** Called by the connection owner before its CameraDevice is released. */
    fun onCameraDisconnected(message: String) {
        diagnostics.warn("preview.cameraDisconnected", message)
        stopInternal(
            terminalState = PreviewUiState(
                phase = PreviewPhase.DISCONNECTED,
                message = message,
            ),
            clearFrameSource = true,
        )
    }

    private val streamListener = object : CameraStreamListener {
        override fun onOpening() {
            diagnostics.info("preview.stream.opening")
        }

        override fun onOpened() {
            diagnostics.info("preview.stream.opened")
            val callbackGeneration = generation
            val device = activeDevice ?: return
            val previewPlayer = player ?: return
            previewPlayer.post {
                if (!isCurrent(callbackGeneration, device, previewPlayer)) return@post
                runCatching {
                    diagnostics.info("preview.player.prepare")
                    previewPlayer.destroyRender()
                    previewPlayer.setListener(playerListener(callbackGeneration, device, previewPlayer))
                    previewPlayer.prepare(PreviewParams())
                    previewPlayer.play()
                    device.preview.requestStreamIframe()
                    diagnostics.info("preview.player.playRequested")
                    previewPlayer.postDelayed({
                        if (isCurrent(callbackGeneration, device, previewPlayer) &&
                            _previewState.value.phase == PreviewPhase.STARTING
                        ) {
                            diagnostics.warn("preview.player.loadingTimeout", "10 seconds without pipeline")
                        }
                    }, PLAYER_LOADING_TIMEOUT_MS)
                }.onFailure { error ->
                    diagnostics.error("preview.player.prepareFailed", error)
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
            diagnostics.info(
                "preview.stream.params",
                "${paramsUpdate.previewWidth}x${paramsUpdate.previewHeight}@${paramsUpdate.previewFps}",
            )
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
                ?: run {
                    diagnostics.warn("preview.pipeline.unavailable")
                    return fail("预览渲染管线不可用")
                }
            runCatching {
                device.preview.setPipeline(pipeline)
                device.preview.requestStreamIframe()
                diagnostics.info("preview.pipeline.bound")
            }.onFailure { error ->
                diagnostics.error("preview.pipeline.bindFailed", error)
                if (isCurrent(callbackGeneration, device, previewPlayer)) {
                    fail("绑定预览渲染管线失败", error)
                }
            }
        }

        override fun onFail(exception: InstaException) {
            diagnostics.error("preview.player.failed", exception)
            if (isCurrent(callbackGeneration, device, previewPlayer)) {
                fail("预览渲染失败", exception)
            }
        }

        override fun onFirstFrameRendered() {
            if (!isCurrent(callbackGeneration, device, previewPlayer)) return
            sessionContext.updateFrameSource(FrameSource.SDK_RENDERED_PREVIEW)
            diagnostics.info("preview.firstFrameRendered")
            _previewState.value = PreviewUiState(
                phase = PreviewPhase.RENDERING,
                renderedAtEpochMs = clock(),
            )
        }

        override fun onReleaseCameraPipeline() {
            if (isCurrent(callbackGeneration, device, previewPlayer)) {
                runCatching { device.preview.setPipeline(null) }
                    .onFailure { error -> diagnostics.error("preview.pipeline.releaseFailed", error) }
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
        diagnostics.info("preview.stop.executing", "wasStreaming=$wasStreaming")
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
            diagnostics.error("preview.stream.stopFailed", error)
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
        diagnostics.warn("preview.disconnected", message)
        sessionContext.updateFrameSource(FrameSource.UNKNOWN)
        _previewState.value = PreviewUiState(phase = PreviewPhase.DISCONNECTED, message = message)
    }

    private fun fail(message: String, error: Throwable? = null) {
        if (error != null) Log.w(LOG_TAG, message, error)
        diagnostics.error("preview.failed", error, message)
        stopInternal(
            terminalState = PreviewUiState(phase = PreviewPhase.FAILED, message = message),
            clearFrameSource = true,
        )
    }

    private companion object {
        const val LOG_TAG = "InstaAutoCamera"
        const val PLAYER_LOADING_TIMEOUT_MS = 10_000L
    }
}
