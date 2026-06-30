package ai.exla.slide.data.repo

import ai.exla.slide.data.api.SlideApi
import ai.exla.slide.data.auth.TokenStore
import ai.exla.slide.data.model.Call
import ai.exla.slide.data.model.CallSession
import ai.exla.slide.data.model.Contact
import ai.exla.slide.data.model.CreateCallBody
import ai.exla.slide.data.model.LogoutBody
import ai.exla.slide.data.model.PatchMeBody
import ai.exla.slide.data.model.RegisterDeviceBody
import ai.exla.slide.data.model.RequestOtpBody
import ai.exla.slide.data.model.SyncContactsBody
import ai.exla.slide.data.model.UnregisterPushBody
import ai.exla.slide.data.model.User
import ai.exla.slide.data.model.VerifyOtpBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Response

/**
 * Coroutine-friendly wrapper over [SlideApi] that also persists auth state.
 * ViewModels depend on this rather than touching Retrofit directly.
 */
class SlideRepository(
    private val api: SlideApi,
    private val tokenStore: TokenStore,
    private val appVersion: String = "1.0.0",
) {
    /* ---- Auth ---- */

    /** Returns the dev OTP code if the backend echoes one (dev only). */
    suspend fun requestOtp(phone: String): Result<String?> = io {
        val resp = api.requestOtp(RequestOtpBody(phone))
        resp.requireSuccessful()
        resp.body()?.devCode
    }

    /** Returns isNewUser flag; tokens + identity stored as a side effect. */
    suspend fun verifyOtp(phone: String, code: String): Result<Boolean> = io {
        val resp = api.verifyOtp(VerifyOtpBody(phone, code))
        tokenStore.saveTokens(resp.accessToken, resp.refreshToken)
        tokenStore.userId = resp.user.id
        tokenStore.phone = resp.user.phone
        tokenStore.displayName = resp.user.displayName
        resp.isNewUser
    }

    /**
     * Register (or refresh) this device's FCM push token with the backend so
     * incoming calls/knocks can ring when the app is backgrounded/killed.
     * The API intentionally has two stores: `/push/register` drives actual
     * delivery while `/devices` is legacy device inventory. Register with the
     * delivery endpoint first, then best-effort mirror to inventory for older
     * server/admin tooling. No-ops while signed out or for a blank token.
     */
    suspend fun registerDevice(pushToken: String): Result<Unit> = io {
        if (pushToken.isBlank() || !tokenStore.isLoggedIn) return@io Unit
        val body = RegisterDeviceBody(
            pushToken = pushToken,
            platform = "android",
            kind = "fcm",
            appVersion = appVersion,
        )
        tokenStore.pushToken
            ?.takeIf { previous -> previous.isNotBlank() && previous != pushToken }
            ?.let { previous ->
                // FCM rotation creates a new token before the provider has
                // necessarily rejected the old one. Remove old ownership while
                // this user's bearer token is still available.
                runCatching {
                    api.unregisterPush(UnregisterPushBody(previous)).requireSuccessful()
                }
            }
        api.registerPush(body).requireSuccessful()
        tokenStore.pushToken = pushToken
        if (tokenStore.pendingPushToken == pushToken) tokenStore.pendingPushToken = null
        runCatching { api.registerDevice(body) }
        Unit
    }

    suspend fun logout(): Result<Unit> = io {
        // Remove server ownership while the access token is still available.
        tokenStore.pushToken?.takeIf { it.isNotBlank() }?.let { token ->
            runCatching { api.unregisterPush(UnregisterPushBody(token)).requireSuccessful() }
        }
        tokenStore.refreshToken?.takeIf { it.isNotEmpty() }?.let {
            runCatching { api.logout(LogoutBody(it)) }
        }
        tokenStore.clear()
        Unit
    }

    /* ---- Me ---- */

    suspend fun getMe(): Result<User> = io {
        api.getMe().also {
            tokenStore.phone = it.phone
            tokenStore.displayName = it.displayName
        }
    }

    suspend fun updateName(name: String): Result<User> = io {
        api.patchMe(PatchMeBody(displayName = name)).also {
            tokenStore.displayName = it.displayName
        }
    }

    /* ---- Contacts ---- */

    suspend fun syncContacts(phones: List<String>, names: List<String> = emptyList()): Result<List<Contact>> = io {
        api.syncContacts(SyncContactsBody(phones, names))
    }

    suspend fun getContacts(): Result<List<Contact>> = io { api.getContacts() }

    /* ---- Calls ---- */

    suspend fun getCalls(): Result<List<Call>> = io { api.getCalls().calls }

    /** One-to-one call: a single participant user id. */
    suspend fun createCall(
        peerUserId: String,
        videoEnabled: Boolean = true,
        ringStyle: String = "call",
    ): Result<CallSession> = io {
        api.createCall(
            CreateCallBody(
                type = "one_to_one",
                participantUserIds = listOf(peerUserId),
                videoEnabled = videoEnabled,
                ringStyle = ringStyle,
            )
        )
    }

    suspend fun acceptCall(callId: String): Result<CallSession> = io {
        try {
            api.acceptCall(callId, tokenStore.callAcceptKey)
        } catch (error: HttpException) {
            if (error.code() == 409) throw CallAnsweredElsewhereException()
            throw error
        }
    }

    suspend fun declineCall(callId: String): Result<Unit> = io {
        api.declineCall(callId).requireSuccessful()
        Unit
    }

    suspend fun leaveCall(callId: String): Result<Unit> = io {
        // The backend uses the installation's accept owner to ensure a stale
        // sibling cannot tear down the device that actually won the call.
        api.leaveCall(callId, tokenStore.callAcceptKey).requireSuccessful()
        Unit
    }

    private suspend inline fun <T> io(crossinline block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { block() } }

    private fun Response<*>.requireSuccessful() {
        if (!isSuccessful) throw HttpException(this)
    }
}

class CallAnsweredElsewhereException : Exception("call was accepted on another installation")
