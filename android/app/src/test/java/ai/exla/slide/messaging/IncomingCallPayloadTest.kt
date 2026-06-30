package ai.exla.slide.messaging

import ai.exla.slide.data.model.Call
import ai.exla.slide.data.model.SignalEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallPayloadTest {

    @Test
    fun `signal payload keeps call identity and knock contract`() {
        val payload = IncomingCallPayload.fromSignal(
            SignalEnvelope(
                type = "incoming_call",
                callId = "call-1",
                fromUserId = "user-2",
                fromName = "  Ada  ",
                videoEnabled = false,
                knock = true,
                call = Call(id = "call-1", ringStyle = "knock"),
            )
        )

        requireNotNull(payload)
        assertEquals("call-1", payload.callId)
        assertEquals("user-2", payload.fromUserId)
        assertEquals("Ada", payload.fromName)
        assertEquals("knock", payload.ringStyle)
        assertTrue(payload.isKnock)
        assertFalse(payload.videoEnabled)
    }

    @Test
    fun `terminal and unrelated signals are not invitations`() {
        assertEquals(
            null,
            IncomingCallPayload.fromSignal(
                SignalEnvelope(type = "call_ended", callId = "call-1")
            )
        )
    }

    @Test
    fun `delayed push expires at the local ring window`() {
        val now = 1_000_000L
        val fresh = payload(sentAt = now - 44_999L)
        val stale = payload(sentAt = now - 45_001L)

        assertFalse(fresh.isStale(now))
        assertTrue(stale.isStale(now))
    }

    @Test
    fun `notification timeout uses remaining server ring window`() {
        val now = 1_000_000L
        assertEquals(5_000L, remainingIncomingRingMs(now, now - 40_000L))
        assertEquals(0L, remainingIncomingRingMs(now, now - 45_000L))
        assertEquals(INCOMING_RING_WINDOW_MS, remainingIncomingRingMs(now, 0L))
        assertEquals(2_000L, remainingIncomingRingMs(now, now, now + 2_000L))
        assertEquals(0L, remainingIncomingRingMs(now, now, now - 1L))
    }

    @Test
    fun `placeholder caller names are sanitized`() {
        assertEquals("Slide", sanitizeCallerName(" unknown "))
        assertEquals("Slide", sanitizeCallerName("Someone"))
        assertEquals("Ada", sanitizeCallerName(" Ada "))
    }

    private fun payload(sentAt: Long) = IncomingCallPayload(
        type = "incoming_call",
        callId = "call-1",
        fromUserId = "user-2",
        fromName = "Ada",
        callType = "one_to_one",
        videoEnabled = true,
        ringStyle = "call",
        sentAtMillis = sentAt,
    )
}
