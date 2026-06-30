package ai.exla.slide.call

import ai.exla.slide.data.model.CallSession
import ai.exla.slide.data.model.User

/** High-level connection state for the in-call UI. */
enum class CallConnectionState { Idle, Connecting, Ringing, Connected, Ended, Failed }

/** Lightweight description of who's on the other end of a call. */
data class CallPeer(
    val userId: String,
    val displayName: String? = null,
    val phone: String? = null,
    val avatarUrl: String? = null,
) {
    companion object {
        fun from(user: User) = CallPeer(user.id, user.displayName, user.phone, user.avatarUrl)
    }
}

/** Snapshot of an active call, surfaced to the UI as StateFlow. */
data class CallUiState(
    val callId: String? = null,
    val peer: CallPeer? = null,
    val connection: CallConnectionState = CallConnectionState.Idle,
    val durationSec: Int = 0,
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val usingFrontCamera: Boolean = true,
    val remoteVideoActive: Boolean = false,
    /** True only after another LiveKit participant is present in the room. */
    val remoteParticipantPresent: Boolean = false,
    val isIncoming: Boolean = false,
    val ringStyle: String = "call",
    /** Audio-only → centered avatar layout on white. */
    val audioOnly: Boolean = false,
)

/** Context used when starting a call. */
data class StartCallRequest(
    val session: CallSession,
    val peer: CallPeer,
    val isIncoming: Boolean,
    val videoEnabled: Boolean = true,
    val ringStyle: String = session.call.ringStyle,
)
