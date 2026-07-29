package com.wisense.resident.data.emergency

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.wisense.resident.data.settings.SettingsStore
import com.wisense.shared.webrtc.FirestoreSignaling
import com.wisense.shared.webrtc.SignalingMessage
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
import kotlinx.coroutines.tasks.await
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * The real ALERT-triggered path. Opens camera+mic via WebRtcClient's own
 * Camera2 capturer and, Phase 4 onward, signals through a
 * wisense_emergencies/{id} Firestore doc instead of Phase 3's raw TCP
 * socket — works across any two networks, and a Cloud Function
 * (onEmergencyCreated) pushes a real notification to the house's
 * caregivers the instant the doc is created.
 *
 * Every piece is independently non-fatal per doc §7: camera or mic hardware
 * or permission being out never aborts the emergency, and no caregiver
 * connecting never aborts it either — the session just reports what's
 * available and proceeds. If there's no house yet (auth/setup incomplete),
 * capture still runs locally; only the Firestore/notification part no-ops.
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

class EmergencyStreamController(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webRtcClient: WebRtcClient? = null
    private var signalingJob: Job? = null
    private var currentEmergencyId: String? = null

    private val _state = MutableStateFlow<EmergencyStreamState>(EmergencyStreamState.Inactive)
    val state: StateFlow<EmergencyStreamState> = _state.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    val eglBaseContext: EglBase.Context? get() = webRtcClient?.eglBase?.eglBaseContext

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
        // the way CameraX's Camera.cameraControl did. Confirmed on-device
        // this can fail with CAMERA_IN_USE since the capturer already holds
        // the camera open — non-fatal either way.
        if (cameraOk) tryStart { enableTorch(true) }

        _state.value = EmergencyStreamState.Active(
            startedAtMillis = startedAt,
            cameraAvailable = cameraOk,
            micAvailable = micOk,
            caregiverConnected = false,
        )
        Log.d(TAG, "started camera=$cameraOk mic=$micOk")

        signalingJob = scope.launch {
            try {
                val houseId = settingsStore.houseId.value
                if (houseId == null) {
                    Log.w(TAG, "no house linked yet — capturing locally only, no caregiver notify")
                    return@launch
                }

                val db = FirebaseFirestore.getInstance()
                // Denormalized onto the emergency doc itself: a Firestore
                // security rule for a list query can't call get() on another
                // document to check membership when there are zero matching
                // results yet (confirmed on-device — PERMISSION_DENIED even
                // with correct caregiverIds on the house doc). Copying these
                // fields here lets the rule check resource.data directly.
                val houseSnap = db.collection("wisense_houses").document(houseId).get().await()
                val ownerId = houseSnap.getString("ownerId")
                @Suppress("UNCHECKED_CAST")
                val caregiverIds = houseSnap.get("caregiverIds") as? List<String> ?: emptyList()

                val emergencyDoc = db.collection("wisense_emergencies").document()
                currentEmergencyId = emergencyDoc.id
                emergencyDoc.set(
                    mapOf(
                        "houseId" to houseId,
                        "ownerId" to ownerId,
                        "caregiverIds" to caregiverIds,
                        "status" to "active",
                        "triggeredAt" to FieldValue.serverTimestamp(),
                        "triggerSource" to "ble_alert",
                        "cameraAvailable" to cameraOk,
                        "micAvailable" to micOk,
                    ),
                ).await()
                Log.d(TAG, "created emergency doc ${emergencyDoc.id}, notifying caregivers")

                val offerSdp = client.createOfferAndGatherIce()
                FirestoreSignaling.send(emergencyDoc.id, SignalingMessage.Offer(offerSdp))
                Log.d(TAG, "sent offer for ${emergencyDoc.id}, waiting for caregiver answer")

                val answer = FirestoreSignaling.awaitMessage(emergencyDoc.id, "answer")
                check(answer is SignalingMessage.Answer) { "expected an answer, got ${answer.type}" }
                client.applyRemoteAnswer(answer.sdp)
                setCaregiverConnected(true)
                Log.d(TAG, "caregiver answered for ${emergencyDoc.id}, connected")
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) return@launch
                Log.e(TAG, "emergency stream signaling failed", e)
            }
        }
    }

    fun stop() {
        if (!active) return
        signalingJob?.cancel()
        signalingJob = null

        currentEmergencyId?.let { id ->
            // Fire-and-forget: this scope is about to be torn down along with
            // the rest of the emergency state, but the write itself completes
            // independently of our local coroutine lifetime.
            FirebaseFirestore.getInstance().collection("wisense_emergencies").document(id)
                .update(mapOf("status" to "resolved", "resolvedAt" to FieldValue.serverTimestamp()))
        }
        currentEmergencyId = null

        if (webRtcClient != null) tryStart { enableTorch(false) }
        webRtcClient?.release()
        webRtcClient = null
        _localVideoTrack.value = null
        _state.value = EmergencyStreamState.Inactive
        Log.d(TAG, "stopped")
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
