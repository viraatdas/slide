package ai.exla.slide.call

import ai.exla.slide.MainActivity
import ai.exla.slide.SlideApp
import ai.exla.slide.messaging.CallActionReceiver
import ai.exla.slide.messaging.IncomingCallPayload
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat

/**
 * Keeps LiveKit and its camera/microphone capture alive while the UI is
 * backgrounded. Android otherwise treats a call as ordinary background work
 * and may suspend or kill media shortly after the Activity leaves the screen.
 */
class CallForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        if (command.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val callId = command.getStringExtra(EXTRA_CALL_ID)
        val peerName = command.getStringExtra(EXTRA_PEER_NAME).orEmpty().ifBlank { "Slide call" }
        val videoEnabled = command.getBooleanExtra(EXTRA_VIDEO_ENABLED, false)
        val connecting = command.getBooleanExtra(EXTRA_CONNECTING, false)
        ensureChannel()
        val notification = buildNotification(callId, peerName, connecting)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (videoEnabled) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(NOTIFICATION_ID, notification, types)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to enter call foreground service", error)
            (application as? SlideApp)?.container?.callService?.fail()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(
        callId: String?,
        peerName: String,
        connecting: Boolean,
    ): android.app.Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val endIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_END_ACTIVE_CALL
            if (callId != null) putExtra(IncomingCallPayload.EXTRA_CALL_ID, callId)
        }
        val end = PendingIntent.getBroadcast(
            this,
            callId?.hashCode() ?: 0,
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle(peerName)
            .setContentText(if (connecting) "Connecting call…" else "Call in progress")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder().setName(peerName).setImportant(true).build()
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, end))
        } else {
            builder.addAction(android.R.drawable.sym_call_outgoing, "End call", end)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active calls",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps a Slide call connected in the background"
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "slide_active_calls"
        private const val NOTIFICATION_ID = 4712
        private const val ACTION_START = "ai.exla.slide.action.START_CALL_SERVICE"
        private const val ACTION_STOP = "ai.exla.slide.action.STOP_CALL_SERVICE"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_VIDEO_ENABLED = "video_enabled"
        private const val EXTRA_CONNECTING = "connecting"
        private const val TAG = "CallForegroundService"
        @Volatile private var running = false

        fun startConnecting(
            context: Context,
            callId: String?,
            peerName: String?,
            videoEnabled: Boolean,
        ): Boolean = dispatch(context, callId, peerName, videoEnabled, connecting = true)

        fun promote(
            context: Context,
            callId: String,
            peerName: String?,
            videoEnabled: Boolean,
        ): Boolean = dispatch(context, callId, peerName, videoEnabled, connecting = false)

        private fun dispatch(
            context: Context,
            callId: String?,
            peerName: String?,
            videoEnabled: Boolean,
            connecting: Boolean,
        ): Boolean {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START
                if (callId != null) putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_VIDEO_ENABLED, videoEnabled)
                putExtra(EXTRA_CONNECTING, connecting)
            }
            return try {
                if (running) context.startService(intent)
                else ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to start call foreground service", error)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
