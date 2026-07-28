package com.wisense.resident.presentation.screens

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wisense.resident.domain.model.EmergencySession
import com.wisense.resident.presentation.MainViewModel
import kotlinx.coroutines.delay

/**
 * §8 Active Emergency screen. Shown the instant ALERT is received. Local
 * camera preview, elapsed time, "caregiver has been notified" confirmation.
 *
 * No cancel button — cancellation is physical, on the ESP32 button. This
 * screen exits automatically when the service reports the session ended
 * (CANCEL received), via the nav graph's session observer.
 */
@Composable
fun EmergencyScreen(viewModel: MainViewModel) {
    val session by viewModel.emergencySession.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF3B0A0A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Fall detected",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFFFB4AB),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Caregiver has been notified",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFFFDAD6),
        )

        Spacer(Modifier.height(16.dp))
        ElapsedTimer(startedAtMillis = session.startedAtMillis)
        Spacer(Modifier.height(16.dp))

        // §7: proceed with whatever is available; camera out → explicit fallback.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (session.cameraAvailable) {
                CameraPreview(viewModel)
            } else {
                CameraUnavailableFallback(micAvailable = session.micAvailable)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Press the button on the room sensor to cancel.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFFFDAD6),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ElapsedTimer(startedAtMillis: Long) {
    // Ticks once a second; recomputes from the real start time.
    val elapsedSeconds = rememberElapsedSeconds(startedAtMillis)
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    Text(
        text = "%02d:%02d".format(minutes, seconds),
        style = MaterialTheme.typography.displaySmall,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun rememberElapsedSeconds(startedAtMillis: Long): Long {
    var elapsed by remember(startedAtMillis) { mutableStateOf(0L) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            elapsed = ((System.currentTimeMillis() - startedAtMillis) / 1000L)
                .coerceAtLeast(0L)
            delay(1000L)
        }
    }
    return elapsed
}

@Composable
private fun CameraPreview(viewModel: MainViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = {
                // previewView is a singleton (survives config changes so the camera
                // binding isn't lost on rotation) — but that means it can still be
                // attached to a now-destroyed composition's parent (e.g. after the
                // Activity is torn down and recreated on screen lock). Compose's
                // AndroidView throws "child already has a parent" otherwise.
                val preview = viewModel.previewView
                (preview.parent as? ViewGroup)?.removeView(preview)
                preview
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CameraUnavailableFallback(micAvailable: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1B1F))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Emergency detected — no camera feed",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (micAvailable) {
                "Audio is streaming. The camera is unavailable on this device."
            } else {
                "Camera and microphone are unavailable on this device."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFCAC4D0),
            textAlign = TextAlign.Center,
        )
    }
}
