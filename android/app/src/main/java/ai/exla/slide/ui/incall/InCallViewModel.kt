package ai.exla.slide.ui.incall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.exla.slide.call.CallPeer
import ai.exla.slide.call.CallService
import ai.exla.slide.call.CallUiState
import ai.exla.slide.call.StartCallRequest
import ai.exla.slide.data.model.Call
import ai.exla.slide.data.model.CallSession
import ai.exla.slide.data.repo.SlideRepository
import ai.exla.slide.data.repo.CallAnsweredElsewhereException
import ai.exla.slide.messaging.CallEventCoordinator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException

/**
 * Drives the in-call screen. Delegates media to [CallService] and call-control
 * (accept/decline/leave) to the REST API via [SlideRepository].
 */
class InCallViewModel(
    private val repo: SlideRepository,
    private val callService: CallService,
    private val callEvents: CallEventCoordinator,
) : ViewModel() {

    /** Invalidates create/accept responses that arrive after the user left. */
    private var operationVersion = 0L
    private val leaveJobs = mutableMapOf<String, Job>()
    private val leaveCallbacks = mutableMapOf<String, MutableList<() -> Unit>>()
    private val completedLeaves = mutableSetOf<String>()
    private val backgroundLeaveJobs = mutableMapOf<String, Job>()

    val state: StateFlow<CallUiState> = callService.state

    /** Place an outgoing one-to-one call to a peer user. */
    fun placeCall(peer: CallPeer, videoEnabled: Boolean, ringStyle: String = "call") {
        val version = ++operationVersion
        // Root owns permission gating and the pre-network foreground-service
        // prepare. Never prepare again here: End may have landed between UI
        // navigation and this effect, and a second prepare would resurrect it.
        if (!isMatchingPreparedCall(callService.state.value, peer.userId, callId = null)) return
        viewModelScope.launch {
            repo.createCall(peer.userId, videoEnabled, ringStyle)
                .onSuccess { session ->
                    val terminalBeforeBinding = callEvents.isTerminalForMedia(session.call.id)
                    if (version != operationVersion ||
                        !isMatchingPreparedCall(callService.state.value, peer.userId, callId = null) ||
                        terminalBeforeBinding
                    ) {
                        // The server may have created the call just as the user
                        // navigated away. Close that late session immediately.
                        // For an early remote decline the placeholder has no id,
                        // so coordinator matching could not end it itself.
                        if (terminalBeforeBinding) callService.end()
                        leaveThenFinish(session.call.id) {}
                        return@onSuccess
                    }
                    callService.start(
                        StartCallRequest(
                            session,
                            peer,
                            isIncoming = false,
                            videoEnabled = videoEnabled && session.call.videoEnabled,
                            ringStyle = session.call.ringStyle,
                        )
                    )
                }
                .onFailure {
                    if (version == operationVersion) callService.end()
                }
        }
    }

    /** Accept an incoming call by id. */
    fun acceptCall(
        callId: String,
        peer: CallPeer,
        videoEnabled: Boolean,
        ringStyle: String = "call",
    ) {
        val version = ++operationVersion
        if (!isMatchingPreparedCall(callService.state.value, peer.userId, callId)) return
        viewModelScope.launch {
            acceptCallWithRetry(callId, peer.userId, version)
                .onSuccess { session ->
                    if (version != operationVersion ||
                        !isMatchingPreparedCall(callService.state.value, peer.userId, callId) ||
                        callEvents.isTerminalForMedia(session.call.id)
                    ) {
                        leaveThenFinish(session.call.id) {}
                        return@onSuccess
                    }
                    val resolvedPeer = resolveIncomingPeer(session, peer)
                    callService.start(
                        StartCallRequest(
                            session,
                            resolvedPeer,
                            isIncoming = true,
                            videoEnabled = videoEnabled && session.call.videoEnabled,
                            ringStyle = session.call.ringStyle,
                        )
                    )
                }
                .onFailure { error ->
                    if (version == operationVersion) {
                        if (error is CallAnsweredElsewhereException) {
                            // This installation lost the accept race. Mark the
                            // call already resolved so terminal UI cleanup must
                            // not POST /leave and tear down the winner.
                            completedLeaves += callId
                        }
                        callService.end()
                    }
                }
        }
    }

    /**
     * Accept is idempotent per installation. A response can be lost after the
     * server commits and publishes `call_accepted`, so transient failures must
     * retry with the same repository/installation key before we conclude the
     * local answer failed. The prepared-state guard stops retries immediately
     * when End was pressed while a request was in flight.
     */
    private suspend fun acceptCallWithRetry(
        callId: String,
        peerUserId: String,
        version: Long,
    ): Result<CallSession> {
        var result: Result<CallSession> = Result.failure(AcceptAttemptTimedOut())
        for (attempt in 0 until ACCEPT_ATTEMPTS) {
            if (version != operationVersion ||
                !isMatchingPreparedCall(callService.state.value, peerUserId, callId)
            ) {
                return Result.failure(CallOperationCancelled())
            }
            result = withTimeoutOrNull(ACCEPT_ATTEMPT_TIMEOUT_MS) {
                repo.acceptCall(callId)
            } ?: Result.failure(AcceptAttemptTimedOut())
            val failure = result.exceptionOrNull()
            if (result.isSuccess || failure == null || !failure.isTransientAcceptFailure()) {
                return result
            }
            if (attempt < ACCEPT_ATTEMPTS - 1) {
                delay(ACCEPT_RETRY_DELAYS_MS[attempt])
            }
        }
        return result
    }

    /** Decline a ringing incoming call. */
    fun decline(callId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.declineCall(callId)
            onDone()
        }
    }

    fun toggleMic() = callService.toggleMic()
    fun toggleCamera() = callService.toggleCamera()
    fun flipCamera() = callService.flipCamera()

    /** End/leave the active call. */
    fun end(onDone: () -> Unit) {
        operationVersion += 1
        val callId = callService.state.value.callId
        callService.end()
        leaveThenFinish(callId, onDone)
    }

    /**
     * LiveKit disconnected or failed without an explicit user/server terminal
     * event. Close the control-plane call before leaving the call UI. Repeated
     * `Ended` emissions join the same cleanup request.
     */
    fun handleMediaTerminated(onDone: () -> Unit) {
        operationVersion += 1
        leaveThenFinish(callService.state.value.callId, onDone)
    }

    fun remoteEnded(callId: String) {
        val stateCallId = callService.state.value.callId
        if (stateCallId == callId) {
            operationVersion += 1
            callService.end()
        }
    }

    private fun leaveThenFinish(callId: String?, onDone: () -> Unit) {
        if (callId == null) {
            onDone()
            return
        }
        if (callId in completedLeaves) {
            onDone()
            return
        }
        leaveCallbacks.getOrPut(callId) { mutableListOf() }.add(onDone)
        if (leaveJobs[callId]?.isActive == true) return
        backgroundLeaveJobs.remove(callId)?.cancel()

        leaveJobs[callId] = viewModelScope.launch {
            var result: Result<Unit> = Result.failure(LeaveAttemptTimedOut())
            for (attempt in 0 until LEAVE_UI_ATTEMPTS) {
                result = leaveAttempt(callId)
                if (result.isAuthoritativeClosure()) break
                if (attempt < LEAVE_UI_ATTEMPTS - 1) delay(LEAVE_RETRY_DELAYS_MS[attempt])
            }
            val closed = result.isAuthoritativeClosure()
            if (closed) completedLeaves += callId
            leaveJobs.remove(callId)
            leaveCallbacks.remove(callId).orEmpty().forEach { it() }
            if (!closed) scheduleBackgroundLeave(callId)
        }
    }

    private suspend fun leaveAttempt(callId: String): Result<Unit> =
        withTimeoutOrNull(LEAVE_ATTEMPT_TIMEOUT_MS) { repo.leaveCall(callId) }
            ?: Result.failure(LeaveAttemptTimedOut())

    private fun scheduleBackgroundLeave(callId: String) {
        if (backgroundLeaveJobs[callId]?.isActive == true || callId in completedLeaves) return
        backgroundLeaveJobs[callId] = viewModelScope.launch {
            for (delayMs in BACKGROUND_LEAVE_DELAYS_MS) {
                delay(delayMs)
                val result = leaveAttempt(callId)
                if (result.isAuthoritativeClosure()) {
                    completedLeaves += callId
                    break
                }
            }
            backgroundLeaveJobs.remove(callId)
        }
    }

    fun localTrack() = callService.localVideoTrack()
    fun remoteTrack() = callService.remoteVideoTrack()
    fun room() = callService.room()

    /** Convenience used by previews/demo: start a mock call to a peer. */
    fun startMockCall(peer: CallPeer) {
        callService.start(
            StartCallRequest(
                session = ai.exla.slide.data.model.CallSession(
                    call = Call(id = "demo", status = "active"),
                    joinToken = "demo",
                    sfuUrl = "wss://demo",
                ),
                peer = peer,
                isIncoming = false,
                videoEnabled = true,
                ringStyle = "call",
            )
        )
    }
}

/** Rebind anonymous pre-answer knock identity from the authenticated accept response. */
internal fun resolveIncomingPeer(
    session: ai.exla.slide.data.model.CallSession,
    fallback: CallPeer,
): CallPeer {
    val creatorId = session.call.createdBy
    val caller = session.call.participants.firstOrNull { it.userId == creatorId }
        ?: session.call.participants.firstOrNull { participant ->
            participant.userId != fallback.userId || fallback.userId == ANONYMOUS_USER_ID
        }
    return CallPeer(
        userId = caller?.userId ?: creatorId ?: fallback.userId,
        displayName = caller?.displayName?.takeIf { it.isNotBlank() }
            ?: fallback.displayName?.takeUnless { fallback.userId == ANONYMOUS_USER_ID },
        phone = caller?.phone?.takeIf { it.isNotBlank() } ?: fallback.phone,
        avatarUrl = caller?.avatarUrl ?: fallback.avatarUrl,
    )
}

private const val ANONYMOUS_USER_ID = "00000000-0000-0000-0000-000000000000"
private const val ACCEPT_ATTEMPT_TIMEOUT_MS = 2_500L
private const val ACCEPT_ATTEMPTS = 3
private val ACCEPT_RETRY_DELAYS_MS = longArrayOf(250L, 750L)
private const val LEAVE_ATTEMPT_TIMEOUT_MS = 2_500L
private const val LEAVE_UI_ATTEMPTS = 3
private val LEAVE_RETRY_DELAYS_MS = longArrayOf(250L, 750L)
private val BACKGROUND_LEAVE_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)

private class LeaveAttemptTimedOut : Exception("leave attempt timed out")
private class AcceptAttemptTimedOut : Exception("accept attempt timed out")
private class CallOperationCancelled : Exception("call operation was cancelled")

internal fun Throwable.isTransientAcceptFailure(): Boolean {
    if (this is CallAnsweredElsewhereException) return false
    val http = this as? HttpException ?: return true
    return http.code() == 408 || http.code() == 425 || http.code() == 429 || http.code() >= 500
}

private fun Result<Unit>.isAuthoritativeClosure(): Boolean {
    if (isSuccess) return true
    val http = exceptionOrNull() as? HttpException ?: return false
    return http.code() == 404 || http.code() == 409
}

internal fun isMatchingPreparedCall(
    state: CallUiState,
    peerUserId: String,
    callId: String?,
): Boolean = state.connection == ai.exla.slide.call.CallConnectionState.Connecting &&
    state.callId == callId && state.peer?.userId == peerUserId
