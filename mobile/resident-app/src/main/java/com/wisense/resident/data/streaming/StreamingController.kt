package com.wisense.resident.data.streaming

import android.content.Context
import android.util.Log
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
 * Phase 3 proof-of-media-path: manual signaling (§ doc roadmap — "manual/
 * hardcoded signaling to prove the media path before automating it").
 * Resident is always the offerer/server here; Phase 4 replaces
 * [SignalingServer] with Firestore without touching WebRtcClient itself.
 *
 * Standalone test screen, separate from EmergencyStreamController (which
 * drives the real BLE ALERT flow) — kept around so the media path can still
 * be exercised without triggering real hardware.
 */
sealed interface StreamTestState {
    data object Idle : StreamTestState
    data object WaitingForCaregiver : StreamTestState
    data object Negotiating : StreamTestState
    data object Streaming : StreamTestState
    data class Error(val message: String) : StreamTestState
}

class StreamingController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webRtcClient: WebRtcClient? = null
    private var signalingServer: SignalingServer? = null
    private var job: Job? = null

    private val _state = MutableStateFlow<StreamTestState>(StreamTestState.Idle)
    val state: StateFlow<StreamTestState> = _state.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    val eglBaseContext: EglBase.Context?
        get() = webRtcClient?.eglBase?.eglBaseContext

    val signalingPort: Int get() = SignalingServer.DEFAULT_PORT

    fun start() {
        if (_state.value == StreamTestState.WaitingForCaregiver ||
            _state.value == StreamTestState.Negotiating ||
            _state.value == StreamTestState.Streaming
        ) {
            return
        }
        _state.value = StreamTestState.WaitingForCaregiver

        // A previous failed attempt may still hold the port/camera open (e.g.
        // an errored ServerSocket that was never explicitly closed) — release
        // before creating fresh ones, or the retry fails with EADDRINUSE.
        webRtcClient?.release()
        signalingServer?.close()

        val client = WebRtcClient(context)
        webRtcClient = client
        val server = SignalingServer()
        signalingServer = server

        job = scope.launch {
            try {
                _localVideoTrack.value = client.startLocalCamera()
                client.startLocalAudio()
                client.createPeerConnection(sendLocalMedia = true)

                server.awaitConnection()
                _state.value = StreamTestState.Negotiating

                val offerSdp = client.createOfferAndGatherIce()
                server.send(SignalingMessage.Offer(offerSdp))

                val answer = server.receive()
                check(answer is SignalingMessage.Answer) { "expected an answer, got ${answer.type}" }
                client.applyRemoteAnswer(answer.sdp)

                _state.value = StreamTestState.Streaming
            } catch (e: Exception) {
                // stop() closes the signaling socket to unblock accept()/read() —
                // that deliberately throws here too, but it's a cancellation, not
                // a real failure. Don't clobber the Idle state stop() just set.
                if (!currentCoroutineContext().isActive) return@launch
                Log.e(TAG, "streaming test failed", e)
                _state.value = StreamTestState.Error(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        signalingServer?.close()
        signalingServer = null
        webRtcClient?.release()
        webRtcClient = null
        _localVideoTrack.value = null
        _state.value = StreamTestState.Idle
    }

    /** Shown on-screen so the caregiver app knows what to type in — same WiFi only. */
    fun localIpAddress(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .flatMap { iface -> Collections.list(iface.inetAddresses).asSequence() }
            .firstOrNull { addr -> !addr.isLoopbackAddress && addr is Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "StreamingController"
    }
}
