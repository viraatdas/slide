package ai.exla.slide.call

import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the media engine. Runtime uses [LiveKitCallService];
 * [MockCallService] keeps previews and unit-level UI work device-independent.
 */
interface CallService {

    /** Observable call state for the in-call screen. */
    val state: StateFlow<CallUiState>

    /** Publish connecting state while the REST create/accept request is in flight. */
    fun prepare(
        peer: CallPeer,
        isIncoming: Boolean,
        videoEnabled: Boolean,
        ringStyle: String,
        callId: String? = null,
    ): Boolean

    /** Connect to the SFU using the session's sfuUrl + joinToken + iceServers. */
    fun start(request: StartCallRequest)

    /** Tear down the peer connection and release media. */
    fun end()

    /** Surface an infrastructure failure through the observable call state. */
    fun fail()

    fun toggleMic(): Boolean
    fun toggleCamera(): Boolean
    fun flipCamera()

    /** Local self-view track. Null until camera capture starts (always in mock). */
    fun localVideoTrack(): VideoTrack?

    /** Remote participant's video track once it arrives from the SFU. */
    fun remoteVideoTrack(): VideoTrack?

    /** The LiveKit room backing the call (null for the mock), needed to bind a
     *  renderer to a track in the UI. */
    fun room(): Room?
}
