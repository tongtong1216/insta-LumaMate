package com.example.insta_auto_adjust.camera.insta360

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.graphics.PixelFormat
import android.view.Surface
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.preview.CameraStreamListener
import com.arashivision.sdk.camera.api.preview.PreviewStreamFrame
import com.arashivision.sdk.camera.api.preview.PreviewStreamParamsUpdate
import com.arashivision.sdk.camera.core.model.option.VideoEncode
import com.example.insta_auto_adjust.camera.contract.FrameSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

enum class RealtimeFramePhase {
    IDLE,
    STARTING,
    DECODING,
    READY,
    ERROR,
}

data class RealtimeFrameState(
    val phase: RealtimeFramePhase = RealtimeFramePhase.IDLE,
    val message: String = "实时预览帧尚未启动",
    val lastFrameId: String? = null,
)

data class RealtimePreviewFrame(
    val frameId: String,
    val jpegBytes: ByteArray,
    val capturedAtEpochMs: Long,
    val width: Int,
    val height: Int,
    val source: FrameSource = FrameSource.SDK_DECODED,
)

/**
 * Headless GO Ultra preview reader.
 *
 * The official SDK sends encoded H.264/H.265 access units through
 * CameraStreamListener.onStreamDataNotify. This class decodes them off the UI
 * thread and does not require an InstaCapturePlayerView. The phone may therefore
 * hide the preview while the camera stream still supplies frames to D.
 */
class Insta360RealtimePreviewFrameSource(
    context: android.content.Context,
    private val cameraDeviceProvider: () -> CameraDevice?,
    private val onDecodedFrameSource: () -> Unit = {},
) : Closeable {
    private val application = context.applicationContext as Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val framePackets = Channel<PreviewStreamFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val decodedFrames = Channel<RealtimePreviewFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val startStopMutex = Mutex()
    private val frameSequence = AtomicLong(0L)
    private val _state = MutableStateFlow(RealtimeFrameState())
    val state: StateFlow<RealtimeFrameState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var decodeJob: Job? = null
    private var running = false
    private var previewWidth = DEFAULT_WIDTH
    private var previewHeight = DEFAULT_HEIGHT
    private var previewFps = DEFAULT_FPS
    private var encoder: VideoEncode = VideoEncode.ENCODE_H264
    private var decoder: EncodedPreviewDecoder? = null

    private val streamListener = object : CameraStreamListener {
        override fun onOpening() {
            update(RealtimeFramePhase.STARTING, "正在打开 GO Ultra 实时预览流")
        }

        override fun onOpened() {
            update(RealtimeFramePhase.DECODING, "预览流已打开，等待解码帧")
            cameraDeviceProvider()?.preview?.requestStreamIframe()
        }

        override fun onIdle() {
            if (running) {
                update(RealtimeFramePhase.DECODING, "预览流空闲，等待下一帧")
            }
        }

        override fun onParamsChanged(paramsUpdate: PreviewStreamParamsUpdate) {
            if (paramsUpdate.previewWidth > 0) {
                previewWidth = paramsUpdate.previewWidth
            }
            if (paramsUpdate.previewHeight > 0) {
                previewHeight = paramsUpdate.previewHeight
            }
            if (paramsUpdate.previewFps > 0) {
                previewFps = paramsUpdate.previewFps
            }
            decoder?.requestFormat(previewWidth, previewHeight, encoder)
        }

        override fun onStreamDataNotify(streamData: PreviewStreamFrame) {
            if (streamData.type.isVideo) {
                framePackets.trySend(streamData)
            }
        }
    }

    fun start() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            startStopMutex.withLock {
                if (running) return@withLock
                running = true
                update(RealtimeFramePhase.STARTING, "正在连接 GO Ultra 实时预览流")
                runCatching {
                    val device = cameraDeviceProvider()
                        ?: error("真实相机尚未连接")
                    if (!device.isConnected()) {
                        error("真实相机连接已失效")
                    }
                    encoder = device.system.fetchVideoEncodeType()
                        .getOrNull()
                        ?: VideoEncode.ENCODE_H264
                    previewWidth = DEFAULT_WIDTH
                    previewHeight = DEFAULT_HEIGHT
                    decoder = EncodedPreviewDecoder().also {
                        it.requestFormat(previewWidth, previewHeight, encoder)
                    }
                    device.preview.init(application)
                    // This flag tells the SDK decoder/pipeline which encoded stream
                    // is being received by onStreamDataNotify.
                    device.preview.setStreamEncode(encoder == VideoEncode.ENCODE_H265)
                    device.preview.registerCameraStreamListener(streamListener)
                    decodeJob = launch {
                        for (packet in framePackets) {
                            decodePacket(packet)
                        }
                    }
                    device.preview.startStream()
                }.onFailure { error ->
                    running = false
                    update(
                        RealtimeFramePhase.ERROR,
                        "实时预览帧启动失败：${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    suspend fun awaitLatestFrame(timeoutMs: Long = DEFAULT_FRAME_TIMEOUT_MS): RealtimePreviewFrame {
        val minimumCaptureTime = System.currentTimeMillis()
        return withTimeout(timeoutMs) {
            while (true) {
                val frame = decodedFrames.receive()
                if (frame.capturedAtEpochMs >= minimumCaptureTime) {
                    return@withTimeout frame
                }
            }
            error("No decoded preview frame")
        }
    }

    suspend fun stop() {
        startStopMutex.withLock {
            if (!running && streamJob?.isActive != true) return
            running = false
            decodeJob?.cancel()
            decodeJob = null
            val device = cameraDeviceProvider()
            if (device != null) {
                runCatching { device.preview.unregisterCameraStreamListener(streamListener) }
                runCatching { device.preview.stopStream() }
            }
            decoder?.close()
            decoder = null
            update(RealtimeFramePhase.IDLE, "实时预览帧已停止")
        }
    }

    override fun close() {
        scope.launch {
            stop()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private suspend fun decodePacket(packet: PreviewStreamFrame) {
        val currentDecoder = decoder ?: return
        val bitmap = runCatching {
            currentDecoder.decode(
                data = packet.data,
                presentationTimeUs = packet.timestamp.coerceAtLeast(0L),
            )
        }.getOrElse { error ->
            update(
                RealtimeFramePhase.ERROR,
                "预览帧解码失败：${error.message ?: error.javaClass.simpleName}",
            )
            return
        } ?: return

        val capturedAt = System.currentTimeMillis()
        val frameId = frameSequence.incrementAndGet().toString()
        val jpegBytes = bitmap.toJpeg()
        val frame = RealtimePreviewFrame(
            frameId = frameId,
            jpegBytes = jpegBytes,
            capturedAtEpochMs = capturedAt,
            width = bitmap.width,
            height = bitmap.height,
        )
        bitmap.recycle()
        decodedFrames.trySend(frame)
        update(RealtimeFramePhase.READY, "实时预览帧已接入：frame_id=$frameId", frameId)
        onDecodedFrameSource()
    }

    private fun update(
        phase: RealtimeFramePhase,
        message: String,
        frameId: String? = _state.value.lastFrameId,
    ) {
        _state.value = RealtimeFrameState(phase, message, frameId)
    }

    private companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 960
        const val DEFAULT_FPS = 15
        const val DEFAULT_FRAME_TIMEOUT_MS = 10_000L
    }
}

/**
 * Decodes the encoded access units delivered by the Insta360 preview callback.
 *
 * The UI renderer and the analysis pipeline deliberately share this decoder helper rather than
 * opening a second SDK preview stream.  Only the current UI preview owner may register the SDK
 * stream listener.
 */
internal class EncodedPreviewDecoder : Closeable {
    private val codecLock = Any()
    private val pendingImage = AtomicReference<CompletableDeferred<Bitmap>?>(null)
    private val latestImage = AtomicReference<Bitmap?>(null)
    private val outputThread = HandlerThread("lightpilot-preview-decoder").apply { start() }
    private val outputHandler = Handler(outputThread.looper)
    private var mediaCodec: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var outputSurface: Surface? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var configuredMime: String? = null

    fun requestFormat(width: Int, height: Int, encoder: VideoEncode) {
        val mime = if (encoder == VideoEncode.ENCODE_H265) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        synchronized(codecLock) {
            if (
                mediaCodec != null &&
                configuredWidth == width &&
                configuredHeight == height &&
                configuredMime == mime
            ) {
                return
            }
            closeCodecLocked()
            configuredWidth = width
            configuredHeight = height
            configuredMime = mime
            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2,
            )
            reader.setOnImageAvailableListener(
                { availableReader -> onImageAvailable(availableReader) },
                outputHandler,
            )
            val codec = MediaCodec.createDecoderByType(mime)
            val format = MediaFormat.createVideoFormat(mime, width, height)
            codec.configure(format, reader.surface, null, 0)
            codec.start()
            imageReader = reader
            outputSurface = reader.surface
            mediaCodec = codec
        }
    }

    suspend fun decode(data: ByteArray, presentationTimeUs: Long): Bitmap? {
        val codec = synchronized(codecLock) { mediaCodec } ?: return null
        val waiter = CompletableDeferred<Bitmap>()
        pendingImage.getAndSet(waiter)?.cancel()
        val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex < 0) {
            pendingImage.compareAndSet(waiter, null)
            return latestImage.getAndSet(null)
        }
        codec.getInputBuffer(inputIndex)?.let { input ->
            input.clear()
            input.put(data)
            codec.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, 0)
        } ?: run {
            pendingImage.compareAndSet(waiter, null)
            return null
        }

        val bufferInfo = MediaCodec.BufferInfo()
        repeat(MAX_DRAIN_OUTPUTS) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return@repeat
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    codec.releaseOutputBuffer(outputIndex, true)
                }
            }
        }
        return kotlinx.coroutines.withTimeoutOrNull(IMAGE_TIMEOUT_MS) {
            waiter.await()
        } ?: latestImage.getAndSet(null)
    }

    override fun close() {
        synchronized(codecLock) {
            closeCodecLocked()
        }
        outputThread.quitSafely()
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        val bitmap = runCatching { image.toBitmap() }.getOrNull()
        image.close()
        if (bitmap == null) return
        val waiter = pendingImage.getAndSet(null)
        if (waiter != null && !waiter.isCompleted) {
            waiter.complete(bitmap)
        } else {
            latestImage.getAndSet(bitmap)?.recycle()
        }
    }

    private fun closeCodecLocked() {
        pendingImage.getAndSet(null)?.cancel()
        latestImage.getAndSet(null)?.recycle()
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        runCatching { outputSurface?.release() }
        runCatching { imageReader?.close() }
        mediaCodec = null
        outputSurface = null
        imageReader = null
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val IMAGE_TIMEOUT_MS = 250L
        const val MAX_DRAIN_OUTPUTS = 8
    }
}

private fun Image.toBitmap(): Bitmap {
    require(format == PixelFormat.RGBA_8888) { "Unexpected decoder image format: $format" }
    val plane = planes.firstOrNull() ?: error("Decoder returned no image plane")
    val buffer = plane.buffer.duplicate()
    val pixels = IntArray(width * height)
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowBytes = ByteArray(max(width * pixelStride, pixelStride))
    for (y in 0 until height) {
        buffer.position(y * rowStride)
        buffer.get(rowBytes, 0, width * pixelStride)
        for (x in 0 until width) {
            val offset = x * pixelStride
            val red = rowBytes[offset].toInt() and 0xff
            val green = rowBytes[offset + 1].toInt() and 0xff
            val blue = rowBytes[offset + 2].toInt() and 0xff
            val alpha = rowBytes[offset + 3].toInt() and 0xff
            pixels[y * width + x] =
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

internal fun Bitmap.toJpeg(): ByteArray {
    val output = ByteArrayOutputStream()
    if (!compress(Bitmap.CompressFormat.JPEG, 82, output)) {
        throw IOException("Unable to encode decoded preview frame")
    }
    return output.toByteArray()
}
