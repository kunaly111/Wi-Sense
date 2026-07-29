package com.wisense.caregiver.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.wisense.shared.firebase.AuthClient
import com.wisense.shared.webrtc.FirestoreSignaling
import com.wisense.shared.webrtc.SignalingMessage
import com.wisense.shared.webrtc.WebRtcClient
import com.wisense.shared.webrtc.snapshots
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack

/**
 * Listens for this house's active emergency (a live Firestore query, not
 * just the FCM push — catches it whether or not the push has arrived yet)
 * and negotiates the moment one appears, via Firestore signaling docs
 * instead of Phase 3's manual TCP connection.
 */
sealed interface ViewerUiState {
    data object Waiting : ViewerUiState
    data object Negotiating : ViewerUiState
    data class Streaming(val videoTrack: VideoTrack) : ViewerUiState
    data class Error(val message: String) : ViewerUiState
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private var webRtcClient: WebRtcClient? = null
    private var negotiatingEmergencyId: String? = null
    private var negotiationJob: Job? = null

    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Waiting)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    val eglBaseContext get() = webRtcClient?.eglBase?.eglBaseContext

    /**
     * [houseId] isn't used in the query filter — a Firestore list query's
     * security rule must be provable from the query's OWN filter fields, and
     * our rule checks ownerId/caregiverIds (confirmed on-device:
     * PERMISSION_DENIED when the query filtered on houseId/status instead,
     * fields the rule doesn't reference). Filtering by array-contains on
     * caregiverIds directly matches what the rule checks, so Firestore can
     * prove it. Hobby scope assumes one house per caregiver, so this is
     * equivalent in practice; kept as a parameter for a future multi-house
     * caregiver to narrow by.
     */
    fun startListening(houseId: String) {
        val uid = AuthClient.currentUser?.uid ?: run {
            _uiState.value = ViewerUiState.Error("not signed in")
            return
        }
        viewModelScope.launch {
            try {
                FirebaseFirestore.getInstance()
                    .collection("wisense_emergencies")
                    .whereArrayContains("caregiverIds", uid)
                    .whereEqualTo("status", "active")
                    .limit(1)
                    .snapshots()
                    .filterNotNull()
                    .collect { snapshot ->
                        val doc = snapshot.documents.firstOrNull()
                        when {
                            doc == null -> reset()
                            negotiatingEmergencyId == null -> {
                                Log.d(TAG, "active emergency ${doc.id} found, negotiating")
                                negotiate(doc.id)
                            }
                        }
                    }
            } catch (e: Exception) {
                // A cancelled scope (e.g. navigating away from the Viewer
                // screen tears down this ViewModel) throws here too — that's
                // normal teardown, not a real failure, so don't report it.
                if (!currentCoroutineContext().isActive) return@launch
                Log.e(TAG, "emergency listener failed", e)
                _uiState.value = ViewerUiState.Error(e.message ?: e.toString())
            }
        }
    }

    private fun negotiate(emergencyId: String) {
        negotiationJob?.cancel()
        negotiatingEmergencyId = emergencyId
        _uiState.value = ViewerUiState.Negotiating

        val client = WebRtcClient(getApplication())
        webRtcClient = client

        negotiationJob = viewModelScope.launch {
            launch {
                client.remoteVideoTrack.collect { track ->
                    if (track != null) {
                        Log.d(TAG, "remote video track received for $emergencyId, streaming")
                        _uiState.value = ViewerUiState.Streaming(track)
                    }
                }
            }
            try {
                client.createPeerConnection(sendLocalMedia = false)
                Log.d(TAG, "waiting for offer on $emergencyId")
                val offer = FirestoreSignaling.awaitMessage(emergencyId, "offer")
                check(offer is SignalingMessage.Offer) { "expected an offer, got ${offer.type}" }
                Log.d(TAG, "offer received for $emergencyId, sending answer")
                val answerSdp = client.createAnswerAndGatherIce(offer.sdp)
                FirestoreSignaling.send(emergencyId, SignalingMessage.Answer(answerSdp))
                Log.d(TAG, "answer sent for $emergencyId")
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) return@launch
                Log.e(TAG, "negotiate failed", e)
                _uiState.value = ViewerUiState.Error(e.message ?: e.toString())
            }
        }
    }

    private fun reset() {
        // Cancel before releasing: without this, the coroutine tree from a
        // prior negotiate() (in particular its remoteVideoTrack collector)
        // keeps running against a client that's about to be torn out from
        // under it — a stale, orphaned client that never gets released.
        negotiationJob?.cancel()
        negotiationJob = null
        negotiatingEmergencyId = null
        webRtcClient?.release()
        webRtcClient = null
        _uiState.value = ViewerUiState.Waiting
    }

    override fun onCleared() {
        negotiationJob?.cancel()
        webRtcClient?.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ViewerViewModel"
    }
}
