package ai.exla.slide.data.api

import ai.exla.slide.data.model.Call
import ai.exla.slide.data.model.CallSession
import ai.exla.slide.data.model.CallsResponse
import ai.exla.slide.data.model.Contact
import ai.exla.slide.data.model.CreateCallBody
import ai.exla.slide.data.model.Device
import ai.exla.slide.data.model.LogoutBody
import ai.exla.slide.data.model.PatchMeBody
import ai.exla.slide.data.model.RefreshBody
import ai.exla.slide.data.model.RefreshResponse
import ai.exla.slide.data.model.RegisterDeviceBody
import ai.exla.slide.data.model.RequestOtpBody
import ai.exla.slide.data.model.RequestOtpResponse
import ai.exla.slide.data.model.SyncContactsBody
import ai.exla.slide.data.model.User
import ai.exla.slide.data.model.UnregisterPushBody
import ai.exla.slide.data.model.VerifyOtpBody
import ai.exla.slide.data.model.VerifyOtpResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * One-to-one mapping of every endpoint in AGENTS.md. Bodies/responses are
 * JSON; auth is injected by an OkHttp interceptor. Endpoints that return 202 /
 * 204 use [Response]<Unit> so the empty body is fine.
 */
interface SlideApi {

    /* ---- Auth (phone-only) ---- */

    @POST("auth/request-otp")
    suspend fun requestOtp(@Body body: RequestOtpBody): Response<RequestOtpResponse>

    @POST("auth/verify-otp")
    suspend fun verifyOtp(@Body body: VerifyOtpBody): VerifyOtpResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshBody): RefreshResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutBody): Response<Unit>

    /* ---- User & onboarding ---- */

    @GET("me")
    suspend fun getMe(): User

    @PATCH("me")
    suspend fun patchMe(@Body body: PatchMeBody): User

    @POST("devices")
    suspend fun registerDevice(@Body body: RegisterDeviceBody): Device

    /**
     * Register a token with the delivery system. `/devices` is retained for
     * device inventory, but incoming push fan-out reads `push_subscriptions`,
     * which is populated by this endpoint.
     */
    @POST("push/register")
    suspend fun registerPush(@Body body: RegisterDeviceBody): Response<Unit>

    @HTTP(method = "DELETE", path = "push/register", hasBody = true)
    suspend fun unregisterPush(@Body body: UnregisterPushBody): Response<Unit>

    /* ---- Contacts ---- */

    @POST("contacts/sync")
    suspend fun syncContacts(@Body body: SyncContactsBody): List<Contact>

    @GET("contacts")
    suspend fun getContacts(): List<Contact>

    /* ---- Calls — control plane ---- */

    @GET("calls")
    suspend fun getCalls(@Query("cursor") cursor: String? = null): CallsResponse

    @POST("calls")
    suspend fun createCall(@Body body: CreateCallBody): CallSession

    @POST("calls/{id}/accept")
    suspend fun acceptCall(
        @Path("id") id: String,
        @Header("X-Call-Accept-Key") acceptKey: String,
    ): CallSession

    @POST("calls/{id}/decline")
    suspend fun declineCall(@Path("id") id: String): Response<Unit>

    @POST("calls/{id}/leave")
    suspend fun leaveCall(
        @Path("id") id: String,
        @Header("X-Call-Accept-Key") acceptKey: String,
    ): Response<Unit>
}
