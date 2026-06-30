package ai.exla.slide.messaging

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import ai.exla.slide.MainActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import kotlin.math.min

internal const val INCOMING_RING_WINDOW_MS = 45_000L

/** Remaining server ring window, accounting for delayed push delivery. */
internal fun remainingIncomingRingMs(
    nowMillis: Long,
    sentAtMillis: Long,
    expiresAtMillis: Long? = null,
): Long {
    val transportRemaining = if (sentAtMillis <= 0L || sentAtMillis > nowMillis) {
        INCOMING_RING_WINDOW_MS
    } else {
        val age = (nowMillis - sentAtMillis).coerceAtLeast(0L)
        (INCOMING_RING_WINDOW_MS - age).coerceIn(0L, INCOMING_RING_WINDOW_MS)
    }
    val serverRemaining = expiresAtMillis
        ?.takeIf { it > 0L }
        ?.let { (it - nowMillis).coerceIn(0L, INCOMING_RING_WINDOW_MS) }
        ?: INCOMING_RING_WINDOW_MS
    return min(transportRemaining, serverRemaining)
}

/**
 * Builds + posts the full-screen-intent incoming-call notification. On API 31+
 * it uses [NotificationCompat.CallStyle] so the system renders a native
 * phone-call style ringing UI; on older releases it falls back to a high-
 * priority notification with a full-screen intent. Either way the full-screen
 * intent launches the single-task [MainActivity] which shows the same call
 * state machine over the lock screen.
 */
object IncomingCallNotifier {

    const val CHANNEL_ID = "slide_incoming_calls"
    private const val PREFS = "slide_incoming_call"
    private const val KEY_ACTIVE_CALL_ID = "active_call_id"
    private const val KEY_SHOWN_AT = "shown_at"

    const val ACTION_ACCEPT = "ai.exla.slide.action.ACCEPT_CALL"
    const val ACTION_DECLINE = "ai.exla.slide.action.DECLINE_CALL"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Rings for incoming Slide calls and knocks"
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 700, 700)
            val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(ringtone, audioAttrs)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Post (or refresh) the one supported incoming invitation. Returns false
     * when another call is already ringing or notifications are unavailable;
     * callers should reject that invitation so the remote side does not ring
     * forever.
     */
    fun showIncoming(context: Context, payload: IncomingCallPayload): Boolean {
        ensureChannel(context)
        if (!canPostNotifications(context)) return false

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val existingId = prefs.getString(KEY_ACTIVE_CALL_ID, null)
        val existingShownAt = prefs.getLong(KEY_SHOWN_AT, 0L)
        val existingIsFresh = existingId != null &&
            remainingIncomingRingMs(now, existingShownAt) > 0L
        if (existingIsFresh && existingId != payload.callId) return false
        if (existingId != null && existingId != payload.callId) {
            NotificationManagerCompat.from(context).cancel(notificationId(existingId))
            cancelTimeout(context, existingId)
        }
        val isNew = existingId != payload.callId
        val payloadRemaining = remainingIncomingRingMs(
            now,
            payload.sentAtMillis,
            payload.expiresAtMillis,
        )
        val existingRemaining = if (!isNew && existingShownAt > 0L) {
            remainingIncomingRingMs(now, existingShownAt)
        } else {
            INCOMING_RING_WINDOW_MS
        }
        val remainingRingMs = min(payloadRemaining, existingRemaining)
        if (remainingRingMs <= 0L) {
            existingId?.let { dismiss(context, it) }
            return false
        }
        val shownAt = now - (INCOMING_RING_WINDOW_MS - remainingRingMs)
        prefs.edit()
            .putString(KEY_ACTIVE_CALL_ID, payload.callId)
            .putLong(KEY_SHOWN_AT, shownAt)
            .apply()

        // Full-screen intent: rings over the lock screen like a phone call.
        val fullScreenIntent = payload.putInto(
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        )
        val fullScreenPi = PendingIntent.getActivity(
            context,
            payload.callId.hashCode(),
            fullScreenIntent,
            pendingIntentFlags(mutable = false),
        )

        val acceptPi = acceptPendingIntent(context, payload)
        val declinePi = broadcastPendingIntent(context, ACTION_DECLINE, payload)

        val callerLabel = if (payload.isKnock) "Someone is knocking" else payload.fromName
        val subtitle = when {
            payload.isKnock -> "Slide to find out who"
            payload.videoEnabled -> "Incoming video call"
            else -> "Incoming call"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(callerLabel)
            .setContentText(subtitle)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!isNew)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenPi)
            .setTimeoutAfter(remainingRingMs)
            .setFullScreenIntent(fullScreenPi, canUseFullScreenIntent(context))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caller = Person.Builder().setName(callerLabel).setImportant(true).build()
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, acceptPi)
            )
        } else {
            builder
                .addAction(android.R.drawable.sym_call_outgoing, "Decline", declinePi)
                .addAction(android.R.drawable.sym_call_incoming, "Accept", acceptPi)
        }

        val notification = builder.build().apply {
            // A call should keep ringing until answered, declined, ended, or
            // the bounded timeout fires. Ordinary notification sounds play once.
            flags = flags or Notification.FLAG_INSISTENT
        }
        // Repeat the revocable permission check at the call site so a user
        // toggling notifications while this payload is processed cannot race
        // us into SecurityException (and so lint can prove this call is safe).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            dismiss(context, payload.callId)
            return false
        }
        try {
            NotificationManagerCompat.from(context)
                .notify(notificationId(payload.callId), notification)
        } catch (_: SecurityException) {
            dismiss(context, payload.callId)
            return false
        }
        scheduleTimeout(context, payload, remainingRingMs)
        return true
    }

    /** Cancel only [callId]; a late terminal event must not kill a newer call. */
    fun dismiss(context: Context, callId: String? = null): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_CALL_ID, null) ?: return false
        if (callId != null && activeId != callId) return false
        NotificationManagerCompat.from(context).cancel(notificationId(activeId))
        cancelTimeout(context, activeId)
        prefs.edit().remove(KEY_ACTIVE_CALL_ID).remove(KEY_SHOWN_AT).apply()
        return true
    }

    fun activeCallId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ACTIVE_CALL_ID, null) ?: return null
        val shownAt = prefs.getLong(KEY_SHOWN_AT, 0L)
        if (remainingIncomingRingMs(System.currentTimeMillis(), shownAt) > 0L) return id
        dismiss(context, id)
        return null
    }

    private fun acceptPendingIntent(
        context: Context,
        payload: IncomingCallPayload,
    ): PendingIntent {
        // Notification trampolines are blocked on Android 12+: the action must
        // target the Activity directly, not a receiver that starts an Activity.
        val intent = payload.putInto(Intent(context, MainActivity::class.java)).apply {
            putExtra(MainActivity.EXTRA_AUTO_ACCEPT, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        return PendingIntent.getActivity(
            context,
            (ACTION_ACCEPT + payload.callId).hashCode(),
            intent,
            pendingIntentFlags(mutable = false),
        )
    }

    private fun broadcastPendingIntent(
        context: Context,
        action: String,
        payload: IncomingCallPayload,
    ): PendingIntent {
        val intent = payload.putInto(
            Intent(context, CallActionReceiver::class.java).setAction(action)
        )
        return PendingIntent.getBroadcast(
            context,
            (action + payload.callId).hashCode(),
            intent,
            pendingIntentFlags(mutable = false),
        )
    }

    private fun scheduleTimeout(
        context: Context,
        payload: IncomingCallPayload,
        delayMs: Long,
    ) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            timeoutPendingIntent(context, payload),
        )
    }

    private fun cancelTimeout(context: Context, callId: String) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction(CallActionReceiver.ACTION_RING_TIMEOUT)
        alarm.cancel(
            PendingIntent.getBroadcast(
                context,
                (CallActionReceiver.ACTION_RING_TIMEOUT + callId).hashCode(),
                intent,
                pendingIntentFlags(mutable = false),
            )
        )
    }

    private fun timeoutPendingIntent(
        context: Context,
        payload: IncomingCallPayload,
    ): PendingIntent {
        val intent = payload.putInto(Intent(context, CallActionReceiver::class.java))
            .setAction(CallActionReceiver.ACTION_RING_TIMEOUT)
        return PendingIntent.getBroadcast(
            context,
            (CallActionReceiver.ACTION_RING_TIMEOUT + payload.callId).hashCode(),
            intent,
            pendingIntentFlags(mutable = false),
        )
    }

    private fun notificationId(callId: String): Int = callId.hashCode() xor 0x51_1D_E

    private fun pendingIntentFlags(mutable: Boolean): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        flags = flags or if (mutable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID)?.importance ==
                NotificationManager.IMPORTANCE_NONE
            ) return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }
}
