package com.wisense.shared.webrtc

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withTimeout
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack

/**
 * Wraps PeerConnectionFactory/PeerConnection setup and camera capture behind
 * a coroutine-friendly API. One instance per call — resident (offerer, sends
 * camera+mic) and caregiver (answerer, receive-only) both use this, driven
 * by [SignalingServer]/[SignalingClient] for the actual SDP exchange.
 */
class WebRtcClient(private val context: Context) {

    val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory by lazy { buildFactory() }

    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var iceGatheringComplete: CompletableDeferred<Unit>? = null

    private val _connectionState =
        MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = _connectionState.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private fun buildFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    /** Resident side only: opens the back camera and starts a local video track. */
    fun startLocalCamera(): VideoTrack {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("no camera available")
        val capturer = enumerator.createCapturer(deviceName, null) ?: error("no camera capturer available")
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("WiSenseCaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        val videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, context, videoSource.capturerObserver)
        capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        val track = peerConnectionFactory.createVideoTrack("wisense_video", videoSource)
        localVideoTrack = track
        return track
    }

    /** Resident side only: opens the mic and starts a local audio track. */
    fun startLocalAudio(): AudioTrack {
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        val track = peerConnectionFactory.createAudioTrack("wisense_audio", audioSource)
        localAudioTrack = track
        return track
    }

    /**
     * [sendLocalMedia] true for the resident (attaches camera+mic tracks
     * started above), false for the caregiver (receive-only — remote video
     * arrives via [remoteVideoTrack]).
     */
    fun createPeerConnection(sendLocalMedia: Boolean) {
        // No STUN server: confirmed on-device that gathering never completed
        // within 15s waiting on stun.l.google.com (network likely blocks/
        // delays outbound UDP to it). Not needed anyway — Phase 3 is same-WiFi
        // only, so local host candidates are sufficient. STUN/TURN comes back
        // once Phase 4+ needs to work across networks (doc §6/§9).
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    iceGatheringComplete?.complete(Unit)
                }
            }
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dataChannel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                _connectionState.value = newState
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) _remoteVideoTrack.value = track
            }
        }

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            ?: error("createPeerConnection returned null")
        peerConnection = pc

        if (sendLocalMedia) {
            localVideoTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
            localAudioTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        }
    }

    /**
     * Resident side: creates the offer and suspends until ICE gathering
     * completes, returning the local description with every candidate baked
     * into the SDP text — this single-shot description is what makes
     * signaling over one manually-typed message (no trickle exchange) work.
     */
    suspend fun createOfferAndGatherIce(): String {
        val pc = peerConnection ?: error("call createPeerConnection() first")
        val offer = pc.createOfferSuspend()
        pc.setLocalDescriptionSuspend(offer)
        awaitIceGatheringComplete()
        return pc.localDescription?.description ?: error("no local description after ICE gathering")
    }

    /** Caregiver side: applies the resident's offer and returns a gathered answer. */
    suspend fun createAnswerAndGatherIce(remoteOfferSdp: String): String {
        val pc = peerConnection ?: error("call createPeerConnection() first")
        pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp))
        val answer = pc.createAnswerSuspend()
        pc.setLocalDescriptionSuspend(answer)
        awaitIceGatheringComplete()
        return pc.localDescription?.description ?: error("no local description after ICE gathering")
    }

    /** Resident side: applies the caregiver's answer once it arrives back over signaling. */
    suspend fun applyRemoteAnswer(remoteAnswerSdp: String) {
        val pc = peerConnection ?: error("call createPeerConnection() first")
        pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.ANSWER, remoteAnswerSdp))
    }

    private suspend fun awaitIceGatheringComplete() {
        val deferred = CompletableDeferred<Unit>()
        iceGatheringComplete = deferred
        // Gathering may already have finished (e.g. zero-candidate edge case)
        // between setLocalDescription returning and us registering the
        // deferred above — check current state directly as a fallback.
        if (peerConnection?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            deferred.complete(Unit)
        }
        withTimeout(ICE_GATHERING_TIMEOUT_MS) { deferred.await() }
    }

    fun release() {
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        eglBase.release()
    }

    companion object {
        private const val STREAM_ID = "wisense_stream"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        private const val ICE_GATHERING_TIMEOUT_MS = 15_000L
    }
}

private suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(error: String) =
                    cont.resumeWithException(IllegalStateException("createOffer failed: $error"))
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            },
            MediaConstraints(),
        )
    }

private suspend fun PeerConnection.createAnswerSuspend(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(error: String) =
                    cont.resumeWithException(IllegalStateException("createAnswer failed: $error"))
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            },
            MediaConstraints(),
        )
    }

private suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setLocalDescription(
            object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String) =
                    cont.resumeWithException(IllegalStateException("setLocalDescription failed: $error"))
                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            },
            sdp,
        )
    }

private suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String) =
                    cont.resumeWithException(IllegalStateException("setRemoteDescription failed: $error"))
                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            },
            sdp,
        )
    }
