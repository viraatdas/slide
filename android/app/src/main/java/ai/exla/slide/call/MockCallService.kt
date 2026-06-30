package ai.exla.slide.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

/**
 * Default [CallService] so the in-call UI is fully renderable without a device
 * camera or a live SFU. Simulates a connect handshake and ticks a call timer.
 * Produces no real media tracks (self-view falls back to an avatar).
 */
class MockCallService : CallService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private val _state = MutableStateFlow(CallUiState())
    override val state: StateFlow<CallUiState> = _state.asStateFlow()

    override fun prepare(
        peer: CallPeer,
        isIncoming: Boolean,
        videoEnabled: Boolean,
        ringStyle: String,
        callId: String?,
    ): Boolean {
        ticker?.cancel()
        _state.value = CallUiState(
            callId = callId,
            peer = peer,
            connection = CallConnectionState.Connecting,
            isIncoming = isIncoming,
            ringStyle = ringStyle,
            cameraEnabled = videoEnabled,
            audioOnly = !videoEnabled,
        )
        return true
    }

    override fun start(request: StartCallRequest) {
        ticker?.cancel()
        _state.value = CallUiState(
            callId = request.session.call.id,
            peer = request.peer,
            connection = CallConnectionState.Connecting,
            isIncoming = request.isIncoming,
            ringStyle = request.ringStyle,
            cameraEnabled = request.videoEnabled,
            audioOnly = !request.videoEnabled,
            remoteVideoActive = false,
        )
        scope.launch {
            delay(1200)
            _state.update {
                it.copy(
                    connection = CallConnectionState.Connected,
                    remoteParticipantPresent = true,
                )
            }
            ticker = scope.launch {
                while (true) {
                    delay(1000)
                    _state.update { it.copy(durationSec = it.durationSec + 1) }
                }
            }
        }
    }

    override fun end() {
        ticker?.cancel()
        _state.update { it.copy(connection = CallConnectionState.Ended) }
    }

    override fun fail() {
        ticker?.cancel()
        _state.update { it.copy(connection = CallConnectionState.Failed) }
    }

    override fun toggleMic(): Boolean {
        val next = !_state.value.micEnabled
        _state.update { it.copy(micEnabled = next) }
        return next
    }

    override fun toggleCamera(): Boolean {
        if (_state.value.audioOnly) return false
        val next = !_state.value.cameraEnabled
        _state.update { it.copy(cameraEnabled = next) }
        return next
    }

    override fun flipCamera() {
        _state.update { it.copy(usingFrontCamera = !it.usingFrontCamera) }
    }

    override fun localVideoTrack(): VideoTrack? = null
    override fun remoteVideoTrack(): VideoTrack? = null
    override fun room(): Room? = null
}
