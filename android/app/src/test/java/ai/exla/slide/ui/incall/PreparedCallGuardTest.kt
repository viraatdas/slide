package ai.exla.slide.ui.incall

import ai.exla.slide.call.CallConnectionState
import ai.exla.slide.call.CallPeer
import ai.exla.slide.call.CallUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedCallGuardTest {

    @Test
    fun `outgoing response starts only while matching prepare is alive`() {
        val prepared = CallUiState(
            callId = null,
            peer = CallPeer("peer-1"),
            connection = CallConnectionState.Connecting,
        )
        assertTrue(isMatchingPreparedCall(prepared, "peer-1", null))
        assertFalse(
            isMatchingPreparedCall(
                prepared.copy(connection = CallConnectionState.Ended),
                "peer-1",
                null,
            )
        )
    }

    @Test
    fun `incoming response requires same call id and peer placeholder`() {
        val prepared = CallUiState(
            callId = "call-1",
            peer = CallPeer("anonymous"),
            connection = CallConnectionState.Connecting,
        )
        assertTrue(isMatchingPreparedCall(prepared, "anonymous", "call-1"))
        assertFalse(isMatchingPreparedCall(prepared, "anonymous", "call-2"))
        assertFalse(isMatchingPreparedCall(prepared, "other", "call-1"))
    }
}
