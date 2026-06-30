package ai.exla.slide.knock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.exla.slide.call.CallPeer
import ai.exla.slide.data.auth.TokenStore
import ai.exla.slide.signaling.SignalingClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the incoming-knock banner. [pulse] increments on every received tap
 * so the UI can re-trigger its pulse animation; the banner auto-dismisses
 * [DISMISS_AFTER_MS] after the most recent tap.
 */
data class IncomingKnock(
    val fromUserId: String,
    val fromName: String?,
    val pulse: Int = 0,
) {
    // The door metaphor is anonymous until the recipient picks up.
    val displayName: String get() = "Someone"
    val canRespond: Boolean get() = isRoutableKnockUserId(fromUserId)

    /** Peer used to escalate a knock into a real call. */
    fun toPeer(): CallPeer? = if (canRespond) {
        CallPeer(userId = fromUserId, displayName = fromName)
    } else {
        null
    }
}

/**
 * Owns knock send/receive for the UI layer. Outgoing taps auto-track seq + dt
 * (ms since the previous tap); incoming taps are surfaced via [incoming] with a
 * self-resetting auto-dismiss so a knock only "rings" while someone is knocking.
 */
class KnockViewModel(
    private val signaling: SignalingClient,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _incoming = MutableStateFlow<IncomingKnock?>(null)
    val incoming: StateFlow<IncomingKnock?> = _incoming.asStateFlow()

    // Outgoing pattern bookkeeping.
    private var outSeq = 0
    private var lastTapAt = 0L

    private var dismissJob: Job? = null

    init { observeSignaling() }

    private fun observeSignaling() {
        viewModelScope.launch {
            signaling.events.collect { event ->
                if (event.type != "knock") return@collect
                val from = event.fromUserId ?: return@collect
                // Privacy-preserving standalone knocks carry a nil UUID. Keep
                // their live rhythm/sound, but IncomingKnock.canRespond makes
                // the banner display-only until a durable invitation arrives.
                _incoming.update { current ->
                    val pulse = (current?.takeIf { it.fromUserId == from }?.pulse ?: 0) + 1
                    IncomingKnock(fromUserId = from, fromName = event.fromName, pulse = pulse)
                }
                scheduleDismiss()
            }
        }
    }

    private fun scheduleDismiss() {
        dismissJob?.cancel()
        dismissJob = viewModelScope.launch {
            delay(DISMISS_AFTER_MS)
            _incoming.value = null
        }
    }

    /** Manually dismiss the incoming banner. */
    fun dismissIncoming() {
        dismissJob?.cancel()
        _incoming.value = null
    }

    /** Begin a fresh outgoing knock pattern (call when opening the pad). */
    fun startPattern() {
        outSeq = 0
        lastTapAt = 0L
    }

    /**
     * Send a single tap to [toUserId]. Returns true if it went out over the WS.
     * The caller is responsible for local sound/haptic feedback.
     */
    fun tap(toUserId: String): Boolean {
        if (!isRoutableKnockUserId(toUserId)) return false
        val now = System.currentTimeMillis()
        val dt = if (lastTapAt == 0L) 0 else (now - lastTapAt).toInt().coerceIn(0, 60_000)
        lastTapAt = now
        outSeq += 1
        val fromName = tokenStore.displayName?.takeIf { it.isNotBlank() }
            ?: tokenStore.phone
            ?: "Slide"
        return signaling.sendKnock(to = toUserId, fromName = fromName, seq = outSeq, dt = dt)
    }

    /** Knock back to whoever is currently knocking us (single tap). */
    fun knockBack() {
        val from = _incoming.value?.fromUserId ?: return
        tap(from)
    }

    private companion object {
        const val DISMISS_AFTER_MS = 2_500L
    }
}

internal const val ANONYMOUS_USER_ID = "00000000-0000-0000-0000-000000000000"

internal fun isRoutableKnockUserId(userId: String): Boolean =
    userId.isNotBlank() && userId != ANONYMOUS_USER_ID
