package ai.exla.slide.messaging

import ai.exla.slide.call.CallConnectionState
import ai.exla.slide.call.CallUiState

/** How an incoming delivery relates to the installation's current media call. */
internal enum class IncomingDeliveryDecision {
    Show,
    IgnoreActiveCall,
    DeclineWhileBusy,
}

internal fun decideIncomingDelivery(
    active: CallUiState,
    incomingCallId: String,
): IncomingDeliveryDecision {
    // Call ids are unique. Once this process has handled an id, a later copy
    // can never represent a new invitation, even if media already terminated.
    if (active.callId != null && active.callId == incomingCallId &&
        active.connection != CallConnectionState.Idle
    ) {
        return IncomingDeliveryDecision.IgnoreActiveCall
    }
    val mediaActive = active.connection == CallConnectionState.Connecting ||
        active.connection == CallConnectionState.Ringing ||
        active.connection == CallConnectionState.Connected
    if (!mediaActive) return IncomingDeliveryDecision.Show
    return IncomingDeliveryDecision.DeclineWhileBusy
}

internal fun shouldDeclineUndeliverable(
    notificationPosted: Boolean,
    appInForeground: Boolean,
): Boolean = !notificationPosted && !appInForeground
