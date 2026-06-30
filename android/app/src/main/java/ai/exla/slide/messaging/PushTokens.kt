package ai.exla.slide.messaging

import ai.exla.slide.data.repo.SlideRepository
import ai.exla.slide.SlideApp
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

/**
 * Safe entry point for fetching + registering the FCM push token. Everything is
 * guarded so the app runs without Firebase wired up: if no FirebaseApp has been
 * initialized (i.e. google-services.json + the google-services plugin are not
 * present yet) this is a no-op rather than a crash.
 */
object PushTokens {

    private const val TAG = "PushTokens"

    /** True only when a default FirebaseApp exists (json + plugin present). */
    fun isFirebaseAvailable(context: Context): Boolean =
        runCatching { FirebaseApp.getInstance() }.getOrNull() != null

    /**
     * Fetch the current FCM token and register it with the backend. Call after
     * sign-in. No-ops (without throwing) when Firebase isn't configured yet.
     */
    fun registerCurrentToken(context: Context, repository: SlideRepository) {
        if (!isFirebaseAvailable(context)) {
            Log.i(TAG, "Firebase not configured; skipping FCM token registration")
            return
        }
        val app = context.applicationContext as? SlideApp ?: return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Failed to fetch FCM token", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result
                if (!token.isNullOrBlank()) {
                    app.container.tokenStore.persistPendingPushToken(token)
                    app.applicationScope.launch { repository.registerDevice(token) }
                } else {
                    Log.w(TAG, "Firebase returned an empty FCM token")
                }
            }
        }.onFailure { Log.w(TAG, "FirebaseMessaging unavailable", it) }
    }

    /**
     * Invalidate the installation token on logout. The server may still have
     * the old value until its logout cleanup runs, but FCM can no longer route
     * another account's calls to this signed-out installation. A fresh token is
     * generated and registered after the next successful sign-in.
     */
    fun deleteCurrentToken(context: Context) {
        if (!isFirebaseAvailable(context)) return
        runCatching {
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Failed to invalidate FCM token on logout", task.exception)
                }
            }
        }.onFailure { Log.w(TAG, "FirebaseMessaging unavailable", it) }
    }
}
