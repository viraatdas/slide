package ai.exla.slide.messaging

import ai.exla.slide.SlideApp
import ai.exla.slide.data.repo.SlideRepository
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException

/**
 * Handles the Accept / Decline actions on the incoming-call notification. Works
 * even when the app process was killed (the receiver is cold-started by the
 * notification action).
 *
 * Accept targets MainActivity directly (Android 12 blocks notification
 * trampolines). This receiver owns only decline, timeout, and active-call end.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_END_ACTIVE_CALL) {
            val app = context.applicationContext as? SlideApp ?: return
            val callId = intent.getStringExtra(IncomingCallPayload.EXTRA_CALL_ID)
            app.container.callEventCoordinator.endLocally(callId)
            val pending = goAsync()
            app.applicationScope.launch {
                try {
                    callId?.let { leaveActiveCallWithRetry(app.container.repository, it) }
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val payload = IncomingCallPayload.fromExtras(intent.extras) ?: return

        when (intent.action) {
            IncomingCallNotifier.ACTION_DECLINE, ACTION_RING_TIMEOUT -> {
                // Ignore an alarm/terminal action for an invitation that has
                // already been replaced or consumed.
                if (!IncomingCallNotifier.dismiss(context, payload.callId)) return
                val app = context.applicationContext as? SlideApp
                if (app != null) {
                    app.container.callEventCoordinator.resolve(
                        payload.callId,
                        CallResolutionKind.Declined,
                    )
                    val pending = goAsync()
                    app.applicationScope.launch {
                        runCatching { app.container.repository.declineCall(payload.callId) }
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_RING_TIMEOUT = "ai.exla.slide.action.RING_TIMEOUT"
        const val ACTION_END_ACTIVE_CALL = "ai.exla.slide.action.END_ACTIVE_CALL"
    }
}

/**
 * Notification actions cannot depend on a Compose/ViewModel collector being
 * alive. Keep the receiver process eligible to run while an idempotent leave
 * is retried, but cap the total below Android's broadcast execution window.
 */
private suspend fun leaveActiveCallWithRetry(repository: SlideRepository, callId: String) {
    for (attempt in 0..RECEIVER_LEAVE_RETRY_DELAYS_MS.size) {
        val result = withTimeoutOrNull(RECEIVER_LEAVE_ATTEMPT_TIMEOUT_MS) {
            repository.leaveCall(callId)
        } ?: Result.failure(ReceiverLeaveTimedOut())
        if (result.isReceiverLeaveComplete()) return
        if (attempt < RECEIVER_LEAVE_RETRY_DELAYS_MS.size) {
            delay(RECEIVER_LEAVE_RETRY_DELAYS_MS[attempt])
        }
    }
}

private fun Result<Unit>.isReceiverLeaveComplete(): Boolean {
    if (isSuccess) return true
    val http = exceptionOrNull() as? HttpException ?: return false
    return http.code() == 404 || http.code() == 409
}

private const val RECEIVER_LEAVE_ATTEMPT_TIMEOUT_MS = 1_000L
private val RECEIVER_LEAVE_RETRY_DELAYS_MS = longArrayOf(500L, 1_500L, 3_000L)
private class ReceiverLeaveTimedOut : Exception("receiver leave attempt timed out")
