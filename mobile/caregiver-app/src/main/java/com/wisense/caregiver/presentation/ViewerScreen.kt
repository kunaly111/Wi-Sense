package com.wisense.caregiver.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun ViewerScreen(houseId: String) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val viewModel: ViewerViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(houseId) {
        viewModel.startListening(houseId)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Wi-Sense Caregiver", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        val streamingState = uiState as? ViewerUiState.Streaming
        if (streamingState != null) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                RemoteVideoView(track = streamingState.videoTrack, eglBaseContext = viewModel.eglBaseContext)
            }
        } else {
            Text(
                text = when (val state = uiState) {
                    ViewerUiState.Waiting -> "Waiting for an alert from this house…"
                    ViewerUiState.Negotiating -> "Alert received — connecting to the live feed…"
                    is ViewerUiState.Error -> "Error: ${state.message}"
                    is ViewerUiState.Streaming -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RemoteVideoView(track: VideoTrack, eglBaseContext: EglBase.Context?) {
    var attachedRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(track) {
        onDispose {
            attachedRenderer?.let { track.removeSink(it) }
        }
    }

    if (eglBaseContext == null) return

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                track.addSink(this)
                attachedRenderer = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
