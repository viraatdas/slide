package ai.exla.slide

import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.Activity
import android.os.Bundle
import ai.exla.slide.messaging.IncomingCallNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SlideApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    @Volatile
    var isInForeground: Boolean = false
        private set

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                isInForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                isInForeground = startedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Observe signaling outside Compose so a still-open background socket
        // can never swallow an incoming call without creating a notification.
        container.callEventCoordinator.start()
        // Recover token rotation work if Firebase started this process and it
        // died before registration completed. Foreground login also fetches the
        // current provider token, so this is an additional durable handoff.
        container.tokenStore.pendingPushToken
            ?.takeIf { it.isNotBlank() && container.tokenStore.isLoggedIn }
            ?.let { pendingToken ->
                applicationScope.launch { container.repository.registerDevice(pendingToken) }
            }
        // Create the high-importance incoming-call channel up front so push
        // notifications ring full-screen. Safe to call without Firebase.
        runCatching { IncomingCallNotifier.ensureChannel(this) }
    }
}
