package ai.exla.slide

import ai.exla.slide.messaging.IncomingCallPayload
import android.os.Bundle
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.exla.slide.ui.nav.SlideAppRoot
import ai.exla.slide.ui.theme.SlideTheme

class MainActivity : ComponentActivity() {

    private var incomingLaunch by mutableStateOf<IncomingCallLaunch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIntent(intent)

        val container = (application as SlideApp).container

        setContent {
            SlideTheme {
                SlideAppRoot(
                    container = container,
                    incomingLaunch = incomingLaunch,
                    onIncomingLaunchConsumed = {
                        incomingLaunch = null
                        // Do not replay an accept after configuration change.
                        setIntent(Intent(this, MainActivity::class.java))
                    },
                    onIncomingCallFinished = { setIncomingCallWindowMode(false) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        val payload = IncomingCallPayload.fromExtras(intent?.extras) ?: return
        setIncomingCallWindowMode(true)
        incomingLaunch = IncomingCallLaunch(
            payload = payload,
            autoAccept = intent?.getBooleanExtra(EXTRA_AUTO_ACCEPT, false) == true,
            nonce = System.nanoTime(),
        )
    }

    @Suppress("DEPRECATION")
    private fun setIncomingCallWindowMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enabled)
            setTurnScreenOn(enabled)
        } else if (enabled) {
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        } else {
            window.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    companion object {
        const val EXTRA_AUTO_ACCEPT = "ai.exla.slide.push.AUTO_ACCEPT"
    }
}

data class IncomingCallLaunch(
    val payload: IncomingCallPayload,
    val autoAccept: Boolean,
    val nonce: Long,
)
