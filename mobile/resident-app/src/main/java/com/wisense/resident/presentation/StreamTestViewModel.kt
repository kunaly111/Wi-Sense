package com.wisense.resident.presentation

import androidx.lifecycle.ViewModel
import com.wisense.resident.data.streaming.StreamTestState
import com.wisense.resident.data.streaming.StreamingController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class StreamTestViewModel @Inject constructor(
    private val controller: StreamingController,
) : ViewModel() {

    val state: StateFlow<StreamTestState> = controller.state
    val localVideoTrack = controller.localVideoTrack
    val eglBaseContext get() = controller.eglBaseContext
    val signalingPort get() = controller.signalingPort

    fun localIpAddress(): String? = controller.localIpAddress()

    fun start() = controller.start()
    fun stop() = controller.stop()

    override fun onCleared() {
        controller.stop()
        super.onCleared()
    }
}
