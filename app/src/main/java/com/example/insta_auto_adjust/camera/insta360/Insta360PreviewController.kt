package com.example.insta_auto_adjust.camera.insta360

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.preview.CameraStreamListener
import com.arashivision.sdk.camera.api.preview.PreviewStreamParamsUpdate
import com.arashivision.sdk.media.api.params.PreviewParams
import com.arashivision.sdk.media.player.preview.InstaCapturePlayerView
import com.arashivision.sdk.common.exception.InstaException
import com.arashivision.sdk.media.api.listener.PlayerViewListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PreviewPhase {
    IDLE,
    STARTING,
    STREAM_OPEN,
    RENDERING,
    ERROR,
}

data class PreviewUiState(
    val phase: PreviewPhase = PreviewPhase.IDLE,
    val message: String = "Real camera preview is not started",
)

/**
 * Owns the official SDK preview stream and its media pipeline.
 *
 * The SDK preview is a rendered GL view, not a synthetic Bitmap. The view is
 * attached by Compose and the controller only owns the stream lifecycle.
 */
class Insta360PreviewController(
    context: Context,
    private val cameraDeviceProvider: () -> CameraDevice?,
) {
    private val application = context.applicationContext as Application
    private val _state = MutableStateFlow(PreviewUiState())
    val state: StateFlow<PreviewUiState> = _state.asStateFlow()

    private var playerView: InstaCapturePlayerView? = null
    private var previewStarted = false
    private var previewWidth = 1280
    private var previewHeight = 960
    private var previewFps = 30
    private var prepareNonce = 0L

    private val streamListener = object : CameraStreamListener {
        override fun onOpening() {
            update(PreviewPhase.STARTING, "正在打开 GO Ultra 实时预览...")
        }

        override fun onOpened() {
            val device = cameraDeviceProvider() ?: return
            update(PreviewPhase.STREAM_OPEN, "预览流已打开，正在绑定渲染管线...")
            runCatching { device.preview.requestStreamIframe() }
            playerView?.post {
                if (previewStarted) {
                    prepareAndPlay()
                }
            }
        }

        override fun onIdle() {
            if (previewStarted) {
                update(PreviewPhase.STREAM_OPEN, "预览流空闲，等待相机画面...")
            }
        }

        override fun onParamsChanged(paramsUpdate: PreviewStreamParamsUpdate) {
            if (paramsUpdate.previewWidth > 0 && paramsUpdate.previewHeight > 0) {
                previewWidth = paramsUpdate.previewWidth
                previewHeight = paramsUpdate.previewHeight
            }
            if (paramsUpdate.previewFps > 0) {
                previewFps = paramsUpdate.previewFps
            }
            playerView?.setPreviewResolution(previewWidth, previewHeight)
            playerView?.setFps(previewFps)
        }
    }

    fun start(view: InstaCapturePlayerView, lifecycle: Lifecycle) {
        if (playerView === view && previewStarted) return
        stop()
        playerView = view
        view.setLifecycle(lifecycle)
        update(PreviewPhase.STARTING, "正在连接 GO Ultra 预览流...")

        val device = cameraDeviceProvider()
        if (device == null || !device.isConnected()) {
            update(PreviewPhase.ERROR, "相机未连接，无法启动实时预览")
            return
        }

        runCatching {
            device.preview.init(application)
            device.preview.registerCameraStreamListener(streamListener)
            previewStarted = true
            device.preview.startStream()
        }.onFailure { error ->
            previewStarted = false
            runCatching {
                device.preview.unregisterCameraStreamListener(streamListener)
                device.preview.setPipeline(null)
            }
            update(PreviewPhase.ERROR, "预览启动失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun stop() {
        val device = cameraDeviceProvider()
        if (device != null && previewStarted) {
            runCatching {
                device.preview.setPipeline(null)
                device.preview.unregisterCameraStreamListener(streamListener)
                device.preview.stopStream()
            }
        }
        previewStarted = false
        prepareNonce++
        playerView?.setListener(null)
        playerView?.destroy()
        playerView = null
        update(PreviewPhase.IDLE, "实时预览已停止")
    }

    private fun prepareAndPlay() {
        val view = playerView ?: return
        val device = cameraDeviceProvider() ?: return
        if (!previewStarted) return

        val expected = ++prepareNonce
        view.setListener(
            object : PlayerViewListener {
                override fun onLoadingStatusChanged(isLoading: Boolean) = Unit

                override fun onLoadingFinish() {
                    if (expected != prepareNonce || !previewStarted) return
                    val pipeline = view.getPipeline()
                    if (pipeline == null) {
                        update(PreviewPhase.ERROR, "预览渲染管线为空")
                        return
                    }
                    device.preview.setPipeline(pipeline)
                    device.preview.requestStreamIframe()
                    update(PreviewPhase.STREAM_OPEN, "已绑定预览管线，等待首帧...")
                }

                override fun onFail(exception: InstaException) {
                    if (expected != prepareNonce) return
                    update(PreviewPhase.ERROR, "预览渲染失败：${exception.message ?: "SDK error"}")
                }

                override fun onFirstFrameRendered() {
                    if (expected == prepareNonce && previewStarted) {
                        update(PreviewPhase.RENDERING, "实时预览 · GO Ultra")
                    }
                }

                override fun onReleaseCameraPipeline() {
                    if (expected == prepareNonce) {
                        cameraDeviceProvider()?.preview?.setPipeline(null)
                    }
                }
            },
        )
        view.prepare(
            PreviewParams(
                width = previewWidth,
                height = previewHeight,
                fps = previewFps,
                isGestureEnabled = false,
                renderModel = null,
            ),
        )
        view.play()
    }

    private fun update(phase: PreviewPhase, message: String) {
        _state.value = PreviewUiState(phase, message)
    }
}
