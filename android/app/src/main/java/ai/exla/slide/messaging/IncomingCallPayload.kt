package ai.exla.slide.messaging

import ai.exla.slide.call.CallPeer
import ai.exla.slide.data.model.SignalEnvelope
import android.content.Intent
import android.os.Bundle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decoded incoming push payload. [type] is "incoming_call" or "knock"; a knock
 * is rendered like a call but labelled differently.
 */
data class IncomingCallPayload(
    val type: String,
    val callId: String,
    val fromUserId: String,
    val fromName: String,
    val callType: String,
    val videoEnabled: Boolean,
    val ringStyle: String,
    val sentAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long? = null,
) {
    val isKnock: Boolean get() = type == "knock" || ringStyle == "knock"

    fun toPeer() = CallPeer(
        userId = fromUserId,
        displayName = fromName,
    )

    fun isStale(nowMillis: Long = System.currentTimeMillis()): Boolean =
        remainingIncomingRingMs(nowMillis, sentAtMillis, expiresAtMillis) <= 0L

    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_TYPE, type)
        putExtra(EXTRA_CALL_ID, callId)
        putExtra(EXTRA_FROM_USER_ID, fromUserId)
        putExtra(EXTRA_FROM_NAME, fromName)
        putExtra(EXTRA_CALL_TYPE, callType)
        putExtra(EXTRA_VIDEO_ENABLED, videoEnabled)
        putExtra(EXTRA_RING_STYLE, ringStyle)
        putExtra(EXTRA_SENT_AT, sentAtMillis)
        expiresAtMillis?.let { putExtra(EXTRA_EXPIRES_AT, it) }
    }

    companion object {
        const val EXTRA_TYPE = "ai.exla.slide.push.TYPE"
        const val EXTRA_CALL_ID = "ai.exla.slide.push.CALL_ID"
        const val EXTRA_FROM_USER_ID = "ai.exla.slide.push.FROM_USER_ID"
        const val EXTRA_FROM_NAME = "ai.exla.slide.push.FROM_NAME"
        const val EXTRA_CALL_TYPE = "ai.exla.slide.push.CALL_TYPE"
        const val EXTRA_VIDEO_ENABLED = "ai.exla.slide.push.VIDEO_ENABLED"
        const val EXTRA_RING_STYLE = "ai.exla.slide.push.RING_STYLE"
        const val EXTRA_SENT_AT = "ai.exla.slide.push.SENT_AT"
        const val EXTRA_EXPIRES_AT = "ai.exla.slide.push.EXPIRES_AT"

        fun fromExtras(extras: Bundle?): IncomingCallPayload? {
            extras ?: return null
            val callId = extras.getString(EXTRA_CALL_ID) ?: return null
            return IncomingCallPayload(
                type = extras.getString(EXTRA_TYPE) ?: "incoming_call",
                callId = callId,
                fromUserId = extras.getString(EXTRA_FROM_USER_ID).orEmpty(),
                fromName = sanitizeCallerName(extras.getString(EXTRA_FROM_NAME)),
                callType = extras.getString(EXTRA_CALL_TYPE) ?: "one_to_one",
                videoEnabled = extras.videoEnabled(),
                ringStyle = extras.getString(EXTRA_RING_STYLE)
                    ?: if (extras.getString(EXTRA_TYPE) == "knock") "knock" else "call",
                sentAtMillis = extras.getLong(EXTRA_SENT_AT, System.currentTimeMillis()),
                expiresAtMillis = extras.getLong(EXTRA_EXPIRES_AT)
                    .takeIf { extras.containsKey(EXTRA_EXPIRES_AT) && it > 0L },
            )
        }

        fun fromSignal(event: SignalEnvelope): IncomingCallPayload? {
            if (event.type != "incoming_call") return null
            val id = event.callId ?: event.call?.id ?: return null
            val callerId = event.fromUserId
                ?: (event.from as? JsonPrimitive)?.contentOrNull
                ?: (event.from as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                ?: event.call?.createdBy
                ?: return null
            val name = event.fromName?.cleanDisplayName()
                ?: (event.from as? JsonObject)?.get("displayName")?.jsonPrimitive?.contentOrNull
                    ?.cleanDisplayName()
                ?: (event.from as? JsonObject)?.get("phone")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                ?: "Slide"
            val style = event.ringStyle
                ?: event.call?.ringStyle
                ?: if (event.knock == true) "knock" else "call"
            return IncomingCallPayload(
                type = "incoming_call",
                callId = id,
                fromUserId = callerId,
                fromName = name,
                callType = event.callType ?: event.call?.type ?: "one_to_one",
                videoEnabled = event.videoEnabled ?: event.call?.videoEnabled ?: true,
                ringStyle = style,
                expiresAtMillis = event.expiresAt,
            )
        }

        @Suppress("DEPRECATION")
        private fun Bundle.videoEnabled(): Boolean {
            if (!containsKey(EXTRA_VIDEO_ENABLED)) return true
            val raw = get(EXTRA_VIDEO_ENABLED)
            return when (raw) {
                is Boolean -> raw
                is String -> raw.toBooleanStrictOrNull() ?: true
                else -> getBoolean(EXTRA_VIDEO_ENABLED, true)
            }
        }
    }
}

internal fun sanitizeCallerName(value: String?, fallback: String = "Slide"): String {
    val cleaned = value?.trim().orEmpty()
    if (cleaned.isBlank()) return fallback
    if (cleaned.equals("unknown", ignoreCase = true)) return fallback
    if (cleaned.equals("someone", ignoreCase = true)) return fallback
    return cleaned
}

private fun String.cleanDisplayName(): String? {
    val cleaned = trim()
    if (cleaned.isBlank()) return null
    if (cleaned.equals("unknown", ignoreCase = true)) return null
    if (cleaned.equals("someone", ignoreCase = true)) return null
    return cleaned
}
