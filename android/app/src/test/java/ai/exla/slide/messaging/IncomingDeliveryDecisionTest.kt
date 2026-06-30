package ai.exla.slide.messaging

import ai.exla.slide.call.CallConnectionState
import ai.exla.slide.call.CallUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingDeliveryDecisionTest {

    @Test
    fun `duplicate delivery for connecting call is ignored`() {
        assertEquals(
            IncomingDeliveryDecision.IgnoreActiveCall,
            decideIncomingDelivery(
                CallUiState(callId = "call-1", connection = CallConnectionState.Connecting),
                "call-1",
            ),
        )
    }

    @Test
    fun `duplicate delivery for connected call is ignored`() {
        assertEquals(
            IncomingDeliveryDecision.IgnoreActiveCall,
            decideIncomingDelivery(
                CallUiState(callId = "call-1", connection = CallConnectionState.Connected),
                "call-1",
            ),
        )
    }

    @Test
    fun `different delivery is declined while media is busy`() {
        assertEquals(
            IncomingDeliveryDecision.DeclineWhileBusy,
            decideIncomingDelivery(
                CallUiState(callId = "call-1", connection = CallConnectionState.Connected),
                "call-2",
            ),
        )
    }

    @Test
    fun `ringing room is busy before remote participant joins`() {
        assertEquals(
            IncomingDeliveryDecision.DeclineWhileBusy,
            decideIncomingDelivery(
                CallUiState(callId = "call-1", connection = CallConnectionState.Ringing),
                "call-2",
            ),
        )
    }

    @Test
    fun `pending outgoing call without id is still busy`() {
        assertEquals(
            IncomingDeliveryDecision.DeclineWhileBusy,
            decideIncomingDelivery(
                CallUiState(callId = null, connection = CallConnectionState.Connecting),
                "call-2",
            ),
        )
    }

    @Test
    fun `delivery shows when media is not active`() {
        assertEquals(
            IncomingDeliveryDecision.Show,
            decideIncomingDelivery(
                CallUiState(callId = "old-call", connection = CallConnectionState.Ended),
                "call-2",
            ),
        )
    }

    @Test
    fun `late duplicate does not resurrect ended call`() {
        assertEquals(
            IncomingDeliveryDecision.IgnoreActiveCall,
            decideIncomingDelivery(
                CallUiState(callId = "call-1", connection = CallConnectionState.Ended),
                "call-1",
            ),
        )
    }

    @Test
    fun `foreground compose delivery prevents notification-denied decline`() {
        assertEquals(false, shouldDeclineUndeliverable(false, appInForeground = true))
        assertEquals(true, shouldDeclineUndeliverable(false, appInForeground = false))
        assertEquals(false, shouldDeclineUndeliverable(true, appInForeground = false))
    }
}
