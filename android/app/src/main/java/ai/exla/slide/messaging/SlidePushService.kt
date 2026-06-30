package ai.exla.slide.messaging

import ai.exla.slide.SlideApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/**
 * Receives FCM events and turns a high-priority **data** message into a
 * full-screen incoming-call notification that rings over the lock screen.
 *
 * Inert until Firebase is wired up: this service is only ever instantiated by
 * the Firebase SDK, which never starts unless a FirebaseApp was initialized
 * (i.e. google-services.json + the google-services plugin are present). Until
 * then it is dead code that simply compiles.
 *
 * Expected data payload (all string values, FCM data is always strings):
 *   type       -> "incoming_call" | "knock" | "call_accepted" |
 *                 "call_ended" | "call_declined"
 *   callId     -> call id (or knock correlation id)
 *   fromUserId -> caller's user id
 *   fromName   -> caller's display name
 *   callType   -> "one_to_one" | "group" (optional; defaults to one_to_one)
 *
 * Send these as a `data` message (NOT `notification`) with priority "high" so
 * Android delivers it even in Doze / when the app is killed, and so this
 * handler runs to post the full-screen-intent notification.
 */
class SlidePushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val app = application as? SlideApp ?: return
        // Persist synchronously before Firebase may tear down this service.
        // The process-wide scope attempts immediately; SlideApp startup and the
        // foreground login path both retry the persisted handoff.
        app.container.tokenStore.persistPendingPushToken(token)
        app.applicationScope.launch { app.container.repository.registerDevice(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"]?.takeIf { it.isNotBlank() } ?: return
        val callId = data["callId"]?.takeIf { it.isNotBlank() } ?: return
        val app = application as? SlideApp ?: return
        if (!app.container.tokenStore.isLoggedIn) {
            IncomingCallNotifier.dismiss(this, callId)
            PushTokens.deleteCurrentToken(this)
            return
        }
        if (type == "call_accepted") {
            // Another installation accepted. On the accepting installation
            // this is a harmless echo; on siblings it stops the ringer.
            app.container.callEventCoordinator.resolve(
                callId,
                CallResolutionKind.AcceptedElsewhere,
            )
            return
        }
        if (type == "call_ended" || type == "call_declined") {
            app.container.callEventCoordinator.resolve(
                callId,
                if (type == "call_ended") {
                    CallResolutionKind.Ended
                } else {
                    CallResolutionKind.Declined
                },
            )
            return
        }
        if (type != "incoming_call" && type != "knock") return

        val payload = IncomingCallPayload(
                type = type,
                callId = callId,
                fromUserId = data["fromUserId"].orEmpty(),
                fromName = sanitizeCallerName(data["fromName"]),
                callType = data["callType"]?.takeIf { it.isNotBlank() } ?: "one_to_one",
                videoEnabled = data["videoEnabled"]?.toBooleanStrictOrNull() ?: true,
                ringStyle = data["ringStyle"]?.takeIf { it.isNotBlank() }
                    ?: if (data["knock"]?.toBooleanStrictOrNull() == true || type == "knock") "knock" else "call",
                sentAtMillis = message.sentTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                expiresAtMillis = data["expiresAt"]?.toLongOrNull(),
            )

        app.container.callEventCoordinator.deliverIncoming(payload)
    }
}
