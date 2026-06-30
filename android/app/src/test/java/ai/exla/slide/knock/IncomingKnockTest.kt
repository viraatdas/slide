package ai.exla.slide.knock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingKnockTest {

    @Test
    fun `anonymous rhythm is visible but cannot route actions`() {
        val knock = IncomingKnock(
            fromUserId = ANONYMOUS_USER_ID,
            fromName = "private",
            pulse = 2,
        )

        assertFalse(knock.canRespond)
        assertNull(knock.toPeer())
        assertEquals("Someone", knock.displayName)
    }

    @Test
    fun `identified rhythm can route actions`() {
        val knock = IncomingKnock(fromUserId = "user-1", fromName = "Ada", pulse = 1)

        assertTrue(knock.canRespond)
        assertTrue(knock.toPeer()?.userId == "user-1")
    }
}
