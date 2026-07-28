package com.wisense.resident.data.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.wisense.resident.domain.model.EmergencySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns local camera + mic capture during an emergency (Phase 2 — no stream,
 * capture only). Bound by the foreground service with a service lifecycle so
 * CameraX keeps running with the screen off.
 *
 * Every piece is independently non-fatal per §7: camera or mic hardware or
 * permission being out never aborts the emergency — the session just reports
 * `cameraAvailable` / `micAvailable` false and proceeds with the rest.
 */
class EmergencyCaptureController(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _session = MutableStateFlow(EmergencySession.Inactive)
    val session: StateFlow<EmergencySession> = _session.asStateFlow()

    /**
     * The PreviewView backing the emergency preview. Created once (app
     * context) so its SurfaceProvider survives Activity config changes; the
     * Active Emergency screen renders this view and the controller binds the
     * camera Preview use case to its provider.
     */
    val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null

    /**
     * Start capture. [lifecycleOwner] must be the service's lifecycle so the
     * camera survives screen-off. Camera and mic start independently.
     */
    fun start(lifecycleOwner: LifecycleOwner) {
        if (_session.value.active) return
        val startedAt = System.currentTimeMillis()
        val cameraOk = startCamera(lifecycleOwner)
        val micOk = startMic()
        _session.value = EmergencySession(
            active = true,
            startedAtMillis = startedAt,
            cameraAvailable = cameraOk,
            micAvailable = micOk,
        )
        Log.d(TAG, "capture started camera=$cameraOk mic=$micOk")
    }

    /** Stop everything cleanly (CANCEL received, or service teardown). */
    fun stop() {
        if (!_session.value.active) return
        stopMic()
        stopCamera()
        _session.value = EmergencySession.Inactive
        Log.d(TAG, "capture stopped")
    }

    // ---------------------------------------------------------------- Camera

    private fun startCamera(lifecycleOwner: LifecycleOwner): Boolean {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            Log.w(TAG, "camera permission not granted")
            return false
        }
        return try {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val provider = providerFuture.get()
            provider.unbindAll()

            preview = Preview.Builder().build().also { p ->
                p.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageAnalysis = ImageAnalysis.Builder().build().also { analysis ->
                // No-op analyzer: this use case exists only to give the capture
                // session a surface that doesn't depend on anything being on-screen.
                // Phase 3/5 is what does real frame processing (WebRTC track).
                analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { it.close() }
            }

            // CameraX configures every bound use case's surface as ONE atomic
            // capture session — confirmed on-device that binding Preview alongside
            // ImageAnalysis still hangs the whole session for 5s while screen-locked,
            // because Preview's TextureView surface never arrives until the screen
            // is drawn again, even though ImageAnalysis's surface is ready
            // immediately. Only bind Preview when the screen is actually on;
            // ImageAnalysis alone keeps the capture session (camera + flash +
            // eventual streaming) alive regardless of screen state.
            val screenOn = ContextCompat.getSystemService(context, PowerManager::class.java)
                ?.isInteractive == true
            val useCases = if (screenOn) {
                arrayOf(preview!!, imageAnalysis!!)
            } else {
                arrayOf(imageAnalysis!!)
            }

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                *useCases,
            )
            cameraProvider = provider

            // §8: flash on during an emergency.
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(true)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "camera start failed", e)
            false
        }
    }

    private fun stopCamera() {
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(false)
            }
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "camera stop failed", e)
        } finally {
            camera = null
            preview = null
            imageAnalysis = null
            cameraProvider = null
        }
    }

    // ---------------------------------------------------------------- Mic

    private fun startMic(): Boolean {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.w(TAG, "mic permission not granted")
            return false
        }
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                Log.w(TAG, "no valid mic buffer size")
                return false
            }
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                Log.w(TAG, "mic failed to initialize")
                return false
            }
            audioRecord = record
            record.startRecording()

            // Phase 2 captures mic only to prove the path — drain the buffer
            // so it doesn't stall; Phase 3/5 feeds this into the WebRTC track.
            audioJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(minBuffer)
                while (_session.value.active) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) break
                }
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "mic start denied", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "mic start failed", e)
            false
        }
    }

    private fun stopMic() {
        audioJob?.cancel()
        audioJob = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "mic stop failed", e)
        }
        audioRecord?.release()
        audioRecord = null
    }

    // ---------------------------------------------------------------- Helpers

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "EmergencyCapture"
        private const val SAMPLE_RATE = 16000
    }
}
