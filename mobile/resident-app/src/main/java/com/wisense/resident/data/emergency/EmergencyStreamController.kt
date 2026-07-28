package com.wisense.resident.data.emergency

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.wisense.shared.webrtc.SignalingMessage
import com.wisense.shared.webrtc.SignalingServer
import com.wisense.shared.webrtc.WebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * The real ALERT-triggered path — replaces the old CameraX-based
 * EmergencyCaptureController. Opens camera+mic via WebRtcClient's own
 * Camera2 capturer (the same one Phase 3's streaming test uses; CameraX and
 * WebRTC's Camera2Session can't both hold the camera device at once, so
 * there's one capture pipeline now, not two) and starts a signaling server
 * so a caregiver can connect and watch live.
 *
 * Signaling is still Phase 3's manual TCP scheme — a caregiver must already
 * know to open the caregiver app and type this phone's IP (shown on the
 * Emergency screen) during a real emergency. Phase 4 (Firestore) is what
 * removes that manual step; nothing else here changes when it lands.
 *
 * Every piece is independently non-fatal per doc §7: camera or mic hardware
 * or permission being out never aborts the emergency, and no caregiver
 * connecting never aborts it either — the session just reports what's
 * available and proceeds.
 */
sealed interface EmergencyStreamState {
    data object Inactive : EmergencyStreamState
    data class Active(
        val startedAtMillis: Long,
        val cameraAvailable: Boolean,
        val micAvailable: Boolean,
        val caregiverConnected: Boolean,
    ) : EmergencyStreamState
}

class EmergencyStreamController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webRtcClient: WebRtcClient? = null
    private var signalingServer: SignalingServer? = null
    private var signalingJob: Job? = null

    private val _state = MutableStateFlow<EmergencyStreamState>(EmergencyStreamState.Inactive)
    val state: StateFlow<EmergencyStreamState> = _state.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    val eglBaseContext: EglBase.Context? get() = webRtcClient?.eglBase?.eglBaseContext
    val signalingPort: Int get() = SignalingServer.DEFAULT_PORT

    val active: Boolean get() = _state.value is EmergencyStreamState.Active

    fun start() {
        if (active) return
        val startedAt = System.currentTimeMillis()

        val client = WebRtcClient(context)
        webRtcClient = client

        val cameraOk = hasPermission(Manifest.permission.CAMERA) && tryStart {
            _localVideoTrack.value = client.startLocalCamera()
        }
        val micOk = hasPermission(Manifest.permission.RECORD_AUDIO) && tryStart {
            client.startLocalAudio()
        }
        client.createPeerConnection(sendLocalMedia = true)

        // Best-effort: WebRTC's Camera2Capturer doesn't expose torch control
        // the way CameraX's Camera.cameraControl did. CameraManager.setTorchMode()
        // may or may not work while the capturer already holds the camera open —
        // unverified, needs on-device confirmation. Non-fatal either way.
        if (cameraOk) tryStart { enableTorch(true) }

        _state.value = EmergencyStreamState.Active(
            startedAtMillis = startedAt,
            cameraAvailable = cameraOk,
            micAvailable = micOk,
            caregiverConnected = false,
        )
        Log.d(TAG, "started camera=$cameraOk mic=$micOk")

        val server = SignalingServer()
        signalingServer = server
        signalingJob = scope.launch {
            try {
                server.awaitConnection()
                setCaregiverConnected(true)

                val offerSdp = client.createOfferAndGatherIce()
                server.send(SignalingMessage.Offer(offerSdp))

                val answer = server.receive()
                check(answer is SignalingMessage.Answer) { "expected an answer, got ${answer.type}" }
                client.applyRemoteAnswer(answer.sdp)
            } catch (e: Exception) {
                // stop() closes the signaling socket to unblock a pending
                // accept()/read() — that's a deliberate cancellation, not a
                // real failure. Don't clobber state stop() already reset.
                if (!currentCoroutineContext().isActive) return@launch
                Log.e(TAG, "emergency stream signaling failed", e)
                setCaregiverConnected(false)
            }
        }
    }

    fun stop() {
        if (!active) return
        signalingJob?.cancel()
        signalingJob = null
        signalingServer?.close()
        signalingServer = null
        if (webRtcClient != null) tryStart { enableTorch(false) }
        webRtcClient?.release()
        webRtcClient = null
        _localVideoTrack.value = null
        _state.value = EmergencyStreamState.Inactive
        Log.d(TAG, "stopped")
    }

    /** Shown on the Emergency screen so a caregiver knows what to connect to. */
    fun localIpAddress(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .flatMap { iface -> Collections.list(iface.inetAddresses).asSequence() }
            .firstOrNull { addr -> !addr.isLoopbackAddress && addr is Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun setCaregiverConnected(connected: Boolean) {
        val current = _state.value as? EmergencyStreamState.Active ?: return
        _state.value = current.copy(caregiverConnected = connected)
    }

    private fun enableTorch(enabled: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull() ?: return
        cameraManager.setTorchMode(cameraId, enabled)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun tryStart(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        Log.w(TAG, "start step failed", e)
        false
    }

    companion object {
        private const val TAG = "EmergencyStreamController"
    }
}
