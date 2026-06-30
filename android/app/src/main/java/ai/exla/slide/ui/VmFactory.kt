package ai.exla.slide.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ai.exla.slide.AppContainer
import ai.exla.slide.ui.calls.CallsViewModel
import ai.exla.slide.ui.contacts.ContactsViewModel
import ai.exla.slide.knock.KnockViewModel
import ai.exla.slide.ui.incall.InCallViewModel
import ai.exla.slide.ui.onboarding.AuthViewModel
import ai.exla.slide.ui.profile.ProfileViewModel

/**
 * Single ViewModelProvider.Factory that constructs every ViewModel from the
 * app's [AppContainer]. Keeps DI explicit without a framework.
 */
class VmFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(AuthViewModel::class.java) ->
            AuthViewModel(container.repository) as T
        modelClass.isAssignableFrom(CallsViewModel::class.java) ->
            CallsViewModel(container.repository, container.signalingClient) as T
        modelClass.isAssignableFrom(ContactsViewModel::class.java) ->
            ContactsViewModel(container.repository, container.signalingClient) as T
        modelClass.isAssignableFrom(ProfileViewModel::class.java) ->
            ProfileViewModel(container.repository, container.tokenStore) as T
        modelClass.isAssignableFrom(InCallViewModel::class.java) ->
            InCallViewModel(
                container.repository,
                container.callService,
                container.callEventCoordinator,
            ) as T
        modelClass.isAssignableFrom(KnockViewModel::class.java) ->
            KnockViewModel(container.signalingClient, container.tokenStore) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
