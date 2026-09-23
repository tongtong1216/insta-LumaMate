package com.example.insta_auto_adjust.camera.preview

import android.view.ViewGroup
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle state of the SDK-rendered camera preview exposed to the UI layer.
 *
 * [RENDERING] is emitted only after the rendering pipeline has been bound and the SDK has
 * confirmed a real first frame. It is never inferred merely from a successful start request.
 */
enum class PreviewPhase {
    IDLE,
    STARTING,
    RENDERING,
    STOPPING,
    FAILED,
    DISCONNECTED,
}

/** UI-safe preview status. Errors must not contain credentials, Wi-Fi data, or SDK objects. */
data class PreviewUiState(
    val phase: PreviewPhase = PreviewPhase.IDLE,
    val message: String? = null,
    val renderedAtEpochMs: Long? = null,
)

/**
 * Android UI boundary for a real camera preview.
 *
 * A supplies the implementation and owns all Insta360 SDK objects. B supplies one [ViewGroup]
 * host (for example from Compose AndroidView) and translates [previewState] into presentation.
 * C must not depend on this interface or consume the rendered UI stream as decoded frame data.
 *
 * All mutation methods must be called on the main thread. They are required to be idempotent so
 * Compose recomposition and lifecycle callbacks cannot create duplicate players or streams.
 */
interface CameraPreviewController {
    val previewState: StateFlow<PreviewUiState>

    /** Attaches the sole UI host. A new host replaces a previously attached host safely. */
    fun attach(container: ViewGroup)

    /** Starts a preview only after a connected camera and host are available. */
    fun start()

    /** Stops the stream and releases the camera pipeline while retaining an attached host. */
    fun stop()

    /** Stops the stream, destroys the SDK player, and removes it from its host. */
    fun detach()
}
