package ai.exla.slide.ui.incall

import ai.exla.slide.call.CallPeer
import ai.exla.slide.data.model.Call
import ai.exla.slide.data.model.CallParticipant
import ai.exla.slide.data.model.CallSession
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingPeerResolutionTest {

    @Test
    fun `accepted session replaces anonymous knock identity`() {
        val session = CallSession(
            call = Call(
                id = "call-1",
                createdBy = "real-caller",
                participants = listOf(
                    CallParticipant(
                        userId = "real-caller",
                        state = "joined",
                        displayName = "Ada",
                        phone = "+14155550137",
                    )
                ),
            ),
            joinToken = "token",
            sfuUrl = "wss://example.test",
        )

        val resolved = resolveIncomingPeer(
            session,
            CallPeer(
                userId = "00000000-0000-0000-0000-000000000000",
                displayName = "Someone",
            ),
        )

        assertEquals("real-caller", resolved.userId)
        assertEquals("Ada", resolved.displayName)
        assertEquals("+14155550137", resolved.phone)
    }
}
