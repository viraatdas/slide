package ai.exla.slide

import android.content.Context
import ai.exla.slide.call.CallService
import ai.exla.slide.call.LiveKitCallService
import ai.exla.slide.data.api.ApiClient
import ai.exla.slide.data.auth.TokenStore
import ai.exla.slide.data.repo.SlideRepository
import ai.exla.slide.signaling.SignalingClient
import ai.exla.slide.messaging.CallEventCoordinator

/**
 * Tiny manual DI container — avoids a heavyweight DI framework for a small
 * graph. Everything is constructed lazily and shared app-wide.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    private val apiClient: ApiClient by lazy { ApiClient(tokenStore) }

    val repository: SlideRepository by lazy {
        SlideRepository(apiClient.api, tokenStore, BuildConfig.VERSION_NAME)
    }

    val signalingClient: SignalingClient by lazy {
        SignalingClient(tokenStore, BuildConfig.WS_BASE_URL, apiClient.json)
    }

    /** Real media via LiveKit (self-hosted SFU). The mock remains for previews. */
    val callService: CallService by lazy { LiveKitCallService(appContext) }

    val callEventCoordinator: CallEventCoordinator by lazy {
        CallEventCoordinator(
            app = appContext as SlideApp,
            signaling = signalingClient,
            repository = repository,
            callService = callService,
            tokenStore = tokenStore,
        )
    }
}
