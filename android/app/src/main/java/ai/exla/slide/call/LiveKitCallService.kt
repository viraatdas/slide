package ai.exla.slide.call

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real media via the self-hosted **LiveKit** SFU. The control plane (`/calls`)
 * returns `session.sfuUrl` (LiveKit ws URL) + `session.joinToken` (a LiveKit
 * access token scoped to room = call id); both participants join the same room.
 *
 * Replaces the old custom-SFU [PeerConnection] client (webrtc-rs SFU couldn't
 * complete DTLS over real networks). LiveKit handles ICE/DTLS/TURN + a TCP
 * fallback, so calls connect even on UDP-restricted networks.
 */
class LiveKitCallService(private val appContext: Context) : CallService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var connectJob: Job? = null
    private var roomEventsJob: Job? = null

    private val _state = MutableStateFlow(CallUiState())
    override val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var activeRoom: Room? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null

    override fun prepare(
        peer: CallPeer,
        isIncoming: Boolean,
        videoEnabled: Boolean,
        ringStyle: String,
        callId: String?,
    ): Boolean {
        releaseRoom()
        _state.value = CallUiState(
            callId = callId,
            peer = peer,
            connection = CallConnectionState.Connecting,
            isIncoming = isIncoming,
            ringStyle = ringStyle,
            cameraEnabled = videoEnabled,
            audioOnly = !videoEnabled,
        )
        val started = CallForegroundService.startConnecting(
            appContext,
            callId,
            peer.displayName ?: peer.phone,
            videoEnabled,
        )
        if (!started) {
            _state.update { it.copy(connection = CallConnectionState.Failed) }
        }
        return started
    }

    override fun start(request: StartCallRequest) {
        releaseRoom()
        if (!CallForegroundService.promote(
                appContext,
                request.session.call.id,
                request.peer.displayName ?: request.peer.phone,
                request.videoEnabled,
            )
        ) {
            _state.update { it.copy(callId = request.session.call.id) }
            fail()
            return
        }
        val room = LiveKit.create(appContext)
        activeRoom = room
        localVideo = null
        remoteVideo = null
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
        // One collector per Room. The old implementation accumulated event
        // collectors on a singleton Room, so a disconnect from an old call
        // could end a newer one.
        roomEventsJob = scope.launch {
            room.events.collect { event ->
                if (activeRoom === room) onRoomEvent(room, event)
            }
        }

        connectJob = scope.launch {
            try {
                room.connect(request.session.sfuUrl, request.session.joinToken)
                if (activeRoom !== room) return@launch
                room.localParticipant.setMicrophoneEnabled(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failRoomStart(room)
                return@launch
            }

            if (request.videoEnabled) {
                try {
                    room.localParticipant.setCameraEnabled(true)
                    if (activeRoom !== room) return@launch
                    localVideo = room.localParticipant
                        .getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                    _state.update { it.copy(cameraEnabled = true) }
                } catch (cameraError: CancellationException) {
                    throw cameraError
                } catch (cameraError: Throwable) {
                    // Camera can be unavailable/in use even with permission.
                    // Preserve the viable microphone connection as audio-only.
                    runCatching { room.localParticipant.setCameraEnabled(false) }
                    localVideo = null
                    _state.update {
                        it.copy(
                            cameraEnabled = false,
                            audioOnly = true,
                        )
                    }
                }
            } else {
                runCatching { room.localParticipant.setCameraEnabled(false) }
            }
        }
    }

    private fun failRoomStart(room: Room) {
        if (activeRoom !== room) return
        activeRoom = null
        roomEventsJob?.cancel()
        runCatching { room.disconnect() }
        CallForegroundService.stop(appContext)
        _state.update { it.copy(connection = CallConnectionState.Failed) }
    }

    private fun onRoomEvent(room: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.Connected -> onConnected(room)
            is RoomEvent.Disconnected -> {
                activeRoom = null
                ticker?.cancel()
                CallForegroundService.stop(appContext)
                _state.update {
                    it.copy(
                        connection = CallConnectionState.Ended,
                        remoteVideoActive = false,
                        remoteParticipantPresent = false,
                    )
                }
            }
            is RoomEvent.Reconnecting ->
                _state.update { it.copy(connection = CallConnectionState.Connecting) }
            is RoomEvent.Reconnected -> onConnected(room)
            is RoomEvent.ParticipantConnected -> onRemoteParticipantJoined(room)
            is RoomEvent.ParticipantDisconnected -> {
                if (room.remoteParticipants.isEmpty()) {
                    ticker?.cancel()
                    _state.update {
                        it.copy(
                            connection = CallConnectionState.Ringing,
                            durationSec = 0,
                            remoteParticipantPresent = false,
                            remoteVideoActive = false,
                        )
                    }
                }
            }
            is RoomEvent.TrackSubscribed -> {
                // Track events are a fallback in case the participant event was
                // emitted before this collector started.
                onRemoteParticipantJoined(room)
                (event.track as? VideoTrack)?.let { track ->
                    remoteVideo = track
                    _state.update { it.copy(remoteVideoActive = true) }
                }
            }
            is RoomEvent.TrackUnsubscribed -> {
                if (event.track == remoteVideo) {
                    remoteVideo = null
                    _state.update { it.copy(remoteVideoActive = false) }
                }
            }
            else -> Unit
        }
    }

    private fun onConnected(room: Room) {
        if (room.remoteParticipants.isNotEmpty()) {
            onRemoteParticipantJoined(room)
            return
        }
        ticker?.cancel()
        _state.update {
            it.copy(
                connection = CallConnectionState.Ringing,
                durationSec = 0,
                remoteParticipantPresent = false,
            )
        }
    }

    private fun onRemoteParticipantJoined(room: Room) {
        if (activeRoom !== room) return
        _state.update {
            it.copy(
                connection = CallConnectionState.Connected,
                remoteParticipantPresent = true,
            )
        }
        ticker?.cancel()
        ticker = scope.launch {
            while (activeRoom === room) {
                delay(1000)
                _state.update { it.copy(durationSec = it.durationSec + 1) }
            }
        }
    }

    /* ---------------- Controls ---------------- */

    override fun toggleMic(): Boolean {
        val room = activeRoom ?: return _state.value.micEnabled
        val next = !_state.value.micEnabled
        scope.launch { runCatching { room.localParticipant.setMicrophoneEnabled(next) } }
        _state.update { it.copy(micEnabled = next) }
        return next
    }

    override fun toggleCamera(): Boolean {
        if (_state.value.audioOnly) return false
        val room = activeRoom ?: return _state.value.cameraEnabled
        val next = !_state.value.cameraEnabled
        scope.launch { runCatching { room.localParticipant.setCameraEnabled(next) } }
        _state.update { it.copy(cameraEnabled = next) }
        return next
    }

    override fun flipCamera() {
        (localVideo as? LocalVideoTrack)?.let { track ->
            scope.launch { runCatching { track.switchCamera() } }
        }
        _state.update { it.copy(usingFrontCamera = !it.usingFrontCamera) }
    }

    override fun localVideoTrack(): VideoTrack? = localVideo
    override fun remoteVideoTrack(): VideoTrack? = remoteVideo
    override fun room(): Room? = activeRoom

    override fun end() {
        releaseRoom()
        CallForegroundService.stop(appContext)
        _state.update {
            it.copy(
                connection = CallConnectionState.Ended,
                remoteVideoActive = false,
                remoteParticipantPresent = false,
            )
        }
    }

    override fun fail() {
        releaseRoom()
        CallForegroundService.stop(appContext)
        _state.update {
            it.copy(
                connection = CallConnectionState.Failed,
                remoteVideoActive = false,
                remoteParticipantPresent = false,
            )
        }
    }

    private fun releaseRoom() {
        ticker?.cancel()
        connectJob?.cancel()
        roomEventsJob?.cancel()
        connectJob = null
        roomEventsJob = null
        val room = activeRoom
        activeRoom = null
        localVideo = null
        remoteVideo = null
        if (room != null) runCatching { room.disconnect() }
    }
}
