package com.wisense.caregiver.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisense.shared.webrtc.SignalingClient
import com.wisense.shared.webrtc.SignalingMessage
import com.wisense.shared.webrtc.SignalingServer
import com.wisense.shared.webrtc.WebRtcClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack

/**
 * Phase 3 proof-of-media-path viewer: connect to the resident's manually
 * entered IP, receive its offer over the raw signaling socket, answer it,
 * and render whatever video track WebRTC negotiates. No auth, no history,
 * no house/caregiver management — that's Phase 6.
 */
sealed interface ViewerUiState {
    data object Idle : ViewerUiState
    data object Connecting : ViewerUiState
    data object Negotiating : ViewerUiState
    data class Streaming(val videoTrack: VideoTrack) : ViewerUiState
    data class Error(val message: String) : ViewerUiState
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val webRtcClient = WebRtcClient(application)
    private val signalingClient = SignalingClient()

    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Idle)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    val eglBaseContext get() = webRtcClient.eglBase.eglBaseContext

    fun connect(residentAddress: String) {
        if (_uiState.value is ViewerUiState.Connecting || _uiState.value is ViewerUiState.Streaming) return
        _uiState.value = ViewerUiState.Connecting

        // Resident screen displays "ip:port" as one string to copy — accept
        // either that or a bare IP (falls back to the default signaling port).
        val (host, port) = residentAddress.substringBeforeLast(':').let { h ->
            val portPart = residentAddress.substringAfterLast(':', missingDelimiterValue = "")
            h to (portPart.toIntOrNull() ?: SignalingServer.DEFAULT_PORT)
        }

        viewModelScope.launch {
            launch {
                webRtcClient.remoteVideoTrack.collect { track ->
                    if (track != null) _uiState.value = ViewerUiState.Streaming(track)
                }
            }
            try {
                signalingClient.connect(host, port)
                webRtcClient.createPeerConnection(sendLocalMedia = false)

                val offer = signalingClient.receive()
                check(offer is SignalingMessage.Offer) { "expected an offer first, got ${offer.type}" }
                _uiState.value = ViewerUiState.Negotiating

                val answerSdp = webRtcClient.createAnswerAndGatherIce(offer.sdp)
                signalingClient.send(SignalingMessage.Answer(answerSdp))
            } catch (e: Exception) {
                Log.e(TAG, "viewer connect failed", e)
                _uiState.value = ViewerUiState.Error(e.message ?: e.toString())
            }
        }
    }

    override fun onCleared() {
        signalingClient.close()
        webRtcClient.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ViewerViewModel"
    }
}
