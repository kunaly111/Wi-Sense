package com.wisense.resident.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wisense.resident.data.streaming.StreamTestState
import com.wisense.resident.presentation.StreamTestViewModel
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Phase 3 proof-of-media-path — NOT the real emergency flow. Manual signaling
 * only: start this screen, type this phone's IP into the caregiver test app's
 * viewer screen. Not wired to the BLE ALERT trigger; that integration is
 * later roadmap work once Firestore signaling (Phase 4) replaces this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamTestScreen(
    onBack: () -> Unit,
    viewModel: StreamTestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val ipAddress = remember { viewModel.localIpAddress() ?: "unknown — check WiFi connection" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Streaming test (Phase 3)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "On the caregiver test app, enter:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "$ipAddress:${viewModel.signalingPort}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))

            val eglContext = viewModel.eglBaseContext
            if (localVideoTrack != null && eglContext != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                ) {
                    LocalVideoPreview(track = localVideoTrack!!, eglBaseContext = eglContext)
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                text = when (val s = state) {
                    StreamTestState.Idle -> "Idle — tap Start to open the camera and wait for the caregiver."
                    StreamTestState.WaitingForCaregiver -> "Camera on — waiting for the caregiver app to connect…"
                    StreamTestState.Negotiating -> "Caregiver connected — negotiating media…"
                    StreamTestState.Streaming -> "Streaming to caregiver."
                    is StreamTestState.Error -> "Error: ${s.message}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { if (state == StreamTestState.Idle || state is StreamTestState.Error) viewModel.start() else viewModel.stop() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state == StreamTestState.Idle || state is StreamTestState.Error) "Start" else "Stop")
            }
        }
    }
}

@Composable
private fun LocalVideoPreview(track: VideoTrack, eglBaseContext: EglBase.Context) {
    var attachedRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(track) {
        onDispose {
            attachedRenderer?.let { track.removeSink(it) }
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                setMirror(true)
                track.addSink(this)
                attachedRenderer = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
