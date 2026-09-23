package com.example.insta_auto_adjust.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arashivision.sdk.media.player.preview.InstaCapturePlayerView
import com.example.insta_auto_adjust.camera.insta360.Insta360PreviewController
import com.example.insta_auto_adjust.camera.insta360.PreviewPhase

@Composable
fun RealCameraPreview(
    controller: Insta360PreviewController,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewState by controller.state.collectAsState()
    val previewViewState = remember { mutableStateOf<InstaCapturePlayerView?>(null) }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                InstaCapturePlayerView(context).also { previewViewState.value = it }
            },
            modifier = Modifier.fillMaxSize(),
            update = {},
        )

        previewViewState.value?.let { view ->
            DisposableEffect(view, lifecycleOwner, controller) {
                controller.start(view, lifecycleOwner.lifecycle)
                onDispose { controller.stop() }
            }
        }

        if (previewState.phase != PreviewPhase.RENDERING) {
            Text(
                text = previewState.message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
