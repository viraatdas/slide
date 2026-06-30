package ai.exla.slide.ui.incall

import ai.exla.slide.data.repo.CallAnsweredElsewhereException
import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AcceptRetryPolicyTest {

    @Test
    fun `network and recoverable http failures retry`() {
        assertTrue(IOException("response lost").isTransientAcceptFailure())
        assertTrue(httpError(408).isTransientAcceptFailure())
        assertTrue(httpError(429).isTransientAcceptFailure())
        assertTrue(httpError(503).isTransientAcceptFailure())
    }

    @Test
    fun `winner conflict and permanent client errors do not retry`() {
        assertFalse(CallAnsweredElsewhereException().isTransientAcceptFailure())
        assertFalse(httpError(400).isTransientAcceptFailure())
        assertFalse(httpError(404).isTransientAcceptFailure())
    }

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Unit>(code, "error".toResponseBody()))
}
