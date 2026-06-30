package ai.exla.slide.messaging

import ai.exla.slide.SlideApp
import ai.exla.slide.call.CallService
import ai.exla.slide.data.auth.TokenStore
import ai.exla.slide.data.repo.SlideRepository
import ai.exla.slide.signaling.SignalingClient
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow

enum class CallResolutionKind { AcceptedElsewhere, Declined, Ended }

data class CallResolution(
    val callId: String,
    val kind: CallResolutionKind,
)

/**
 * Application-scoped observer for call signaling. Compose collectors disappear
 * when an Activity is destroyed, while the WebSocket can remain connected.
 * Calls intentionally arrive through both WebSocket and FCM, so this observer
 * feeds either path into the same call-id-scoped notification state machine.
 */
class CallEventCoordinator(
    private val app: SlideApp,
    private val signaling: SignalingClient,
    private val repository: SlideRepository,
    private val callService: CallService,
    private val tokenStore: TokenStore,
) {
    private val scope = app.applicationScope
    private val mainHandler = Handler(Looper.getMainLooper())
    // Terminal/local events are buffered while Activity/Compose collectors are
    // absent. Invitations are state, not one-shot events: overlapping Activity
    // collectors during recreation must both see the still-ringing call.
    private val resolutionEvents = Channel<CallResolution>(Channel.BUFFERED)
    val resolutions: Flow<CallResolution> = resolutionEvents.receiveAsFlow()
    private val _pendingInvitation = MutableStateFlow<IncomingCallPayload?>(null)
    val invitations: StateFlow<IncomingCallPayload?> = _pendingInvitation.asStateFlow()
    private var pendingExpiryJob: Job? = null
    private val localEndEvents = Channel<String?>(Channel.BUFFERED)
    val localEnds: Flow<String?> = localEndEvents.receiveAsFlow()
    private val resolvedCalls = LinkedHashMap<String, CallResolutionKind>()

    @Synchronized
    fun endLocally(callId: String?) {
        callId?.let { rememberResolved(it, CallResolutionKind.Ended) }
        onMain { callService.end() }
        localEndEvents.trySend(callId)
    }

    @Synchronized
    fun deliverIncoming(payload: IncomingCallPayload) {
        if (resolvedCalls.containsKey(payload.callId)) return
        if (!tokenStore.isLoggedIn) {
            IncomingCallNotifier.dismiss(app, payload.callId)
            return
        }
        if (payload.isStale()) {
            scope.launch { repository.declineCall(payload.callId) }
            return
        }

        when (decideIncomingDelivery(callService.state.value, payload.callId)) {
            IncomingDeliveryDecision.IgnoreActiveCall -> {
                IncomingCallNotifier.dismiss(app, payload.callId)
                return
            }
            IncomingDeliveryDecision.DeclineWhileBusy -> {
                scope.launch { repository.declineCall(payload.callId) }
                return
            }
            IncomingDeliveryDecision.Show -> Unit
        }

        val ringingId = IncomingCallNotifier.activeCallId(app)
        if (ringingId != null && ringingId != payload.callId) {
            scope.launch { repository.declineCall(payload.callId) }
            return
        }
        val pendingId = _pendingInvitation.value?.callId
        if (pendingId != null && pendingId != payload.callId) {
            scope.launch { repository.declineCall(payload.callId) }
            return
        }

        val notificationPosted = IncomingCallNotifier.showIncoming(app, payload)
        val declineUndeliverable = shouldDeclineUndeliverable(
            notificationPosted,
            app.isInForeground,
        )
        if (declineUndeliverable) {
            // There is no notification and no foreground UI that can consume
            // this invite. Do not leave it queued for a later Activity to
            // resurrect after we have already declined it on the server.
            rememberResolved(payload.callId, CallResolutionKind.Declined)
            scope.launch { repository.declineCall(payload.callId) }
            return
        }
        // Existing Compose collectors may remain alive while the Activity is
        // stopped; always update them so returning through Recents also lands
        // on the incoming screen. Foreground state only controls whether lack
        // of a notification is considered undeliverable.
        retainInvitation(payload)
    }

    @Synchronized
    fun resolve(callId: String, kind: CallResolutionKind) {
        val effectiveKind = rememberResolved(callId, kind)
        IncomingCallNotifier.dismiss(app, callId)
        clearPendingInvitation(callId)
        if (effectiveKind != CallResolutionKind.AcceptedElsewhere) {
            // LiveKit state is Main-confined. Recheck the id on Main so a
            // terminal event recorded during start() ends the just-bound room.
            onMain {
                if (callService.state.value.callId == callId && isTerminalForMedia(callId)) {
                    callService.end()
                }
            }
        }
        resolutionEvents.trySend(CallResolution(callId, effectiveKind))
    }

    private fun rememberResolved(callId: String, kind: CallResolutionKind): CallResolutionKind {
        val existing = resolvedCalls[callId]
        // Cross-transport delivery can reorder accepted after ended/declined.
        // A positive accept may be upgraded to terminal, never the reverse.
        val effectiveKind = when {
            existing == null -> kind
            existing == CallResolutionKind.AcceptedElsewhere &&
                kind != CallResolutionKind.AcceptedElsewhere -> kind
            else -> existing
        }
        resolvedCalls[callId] = effectiveKind
        while (resolvedCalls.size > MAX_RESOLVED_CALL_IDS) {
            resolvedCalls.remove(resolvedCalls.keys.first())
        }
        return effectiveKind
    }

    /** Protects a queued invitation from resurfacing after a terminal event. */
    @Synchronized
    fun isResolved(callId: String): Boolean = resolvedCalls.containsKey(callId)

    /** Decline/end must block a late create response; accept is positive for its caller. */
    @Synchronized
    fun isTerminalForMedia(callId: String): Boolean =
        resolvedCalls[callId]?.let { it != CallResolutionKind.AcceptedElsewhere } == true

    /** Retain a full-screen/notification launch across Activity recreation. */
    @Synchronized
    fun stageInvitation(payload: IncomingCallPayload): Boolean {
        if (resolvedCalls.containsKey(payload.callId) || payload.isStale() || !tokenStore.isLoggedIn) {
            return false
        }
        val pendingId = _pendingInvitation.value?.callId
        if (pendingId != null && pendingId != payload.callId) return false
        retainInvitation(payload)
        return true
    }

    /** The user is leaving the ringing surface to answer this exact call. */
    @Synchronized
    fun consumeInvitation(callId: String): Boolean {
        if (resolvedCalls.containsKey(callId) || _pendingInvitation.value?.callId != callId) {
            IncomingCallNotifier.dismiss(app, callId)
            return false
        }
        IncomingCallNotifier.dismiss(app, callId)
        clearPendingInvitation(callId)
        return true
    }

    private fun retainInvitation(payload: IncomingCallPayload) {
        _pendingInvitation.value = payload
        pendingExpiryJob?.cancel()
        val remainingMs = remainingIncomingRingMs(
            System.currentTimeMillis(),
            payload.sentAtMillis,
            payload.expiresAtMillis,
        )
        pendingExpiryJob = scope.launch {
            delay(remainingMs)
            expireInvitation(payload.callId)
        }
    }

    private fun clearPendingInvitation(callId: String) {
        if (_pendingInvitation.value?.callId != callId) return
        pendingExpiryJob?.cancel()
        pendingExpiryJob = null
        _pendingInvitation.value = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    @Synchronized
    private fun expireInvitation(callId: String) {
        if (_pendingInvitation.value?.callId != callId) return
        // This is the timeout job itself, so do not cancel it via clear().
        pendingExpiryJob = null
        _pendingInvitation.value = null
        rememberResolved(callId, CallResolutionKind.Declined)
        IncomingCallNotifier.dismiss(app, callId)
        resolutionEvents.trySend(CallResolution(callId, CallResolutionKind.Declined))
        scope.launch { repository.declineCall(callId) }
    }

    fun start() {
        scope.launch {
            signaling.events.collect { event ->
                when (event.type) {
                    "incoming_call" -> {
                        val payload = IncomingCallPayload.fromSignal(event) ?: return@collect
                        deliverIncoming(payload)
                    }

                    "call_accepted" -> {
                        val callId = event.callId ?: event.call?.id ?: return@collect
                        resolve(callId, CallResolutionKind.AcceptedElsewhere)
                    }

                    "call_ended", "call_declined" -> {
                        val callId = event.callId ?: event.call?.id ?: return@collect
                        resolve(
                            callId,
                            if (event.type == "call_ended") {
                                CallResolutionKind.Ended
                            } else {
                                CallResolutionKind.Declined
                            },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_RESOLVED_CALL_IDS = 64
    }
}
