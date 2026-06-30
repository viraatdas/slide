package ai.exla.slide.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

/* ---------------- Core domain (camelCase per AGENTS.md) ---------------- */

@Serializable
data class User(
    val id: String,
    val phone: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
data class Device(
    val id: String,
    val userId: String? = null,
    val pushToken: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class Contact(
    val id: String? = null,
    val ownerUserId: String? = null,
    val contactUserId: String? = null,
    val userId: String? = null,
    val phone: String,
    val displayName: String? = null,
    @SerialName("onSlide") val serverOnSlide: Boolean = false,
) {
    val onSlide: Boolean
        get() = serverOnSlide || contactUserId != null || userId != null
}

/** IceServer.urls is an array of URLs per AGENTS.md. */
@Serializable
data class IceServer(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class CallParticipant(
    val userId: String,
    val state: String,                 // invited|ringing|joined|left|declined
    val joinedAt: String? = null,
    val leftAt: String? = null,
    val displayName: String? = null,
    val phone: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class Call(
    val id: String,
    val roomId: String? = null,
    val sfuNodeId: String? = null,
    val type: String = "one_to_one",   // one_to_one|group
    val createdBy: String? = null,
    val status: String = "ringing",    // ringing|active|ended|missed|declined
    val videoEnabled: Boolean = true,
    val ringStyle: String = "call",    // call|knock
    val startedAt: String? = null,
    val endedAt: String? = null,
    val createdAt: String? = null,
    val participants: List<CallParticipant> = emptyList(),
)

/* ---------------- Auth ---------------- */

@Serializable
data class RequestOtpBody(val phone: String)

@Serializable
data class RequestOtpResponse(val devCode: String? = null)

@Serializable
data class VerifyOtpBody(val phone: String, val code: String)

@Serializable
data class VerifyOtpResponse(
    val accessToken: String,
    val refreshToken: String,
    val isNewUser: Boolean = false,
    val user: User,
)

@Serializable
data class RefreshBody(val refreshToken: String)

@Serializable
data class RefreshResponse(val accessToken: String, val refreshToken: String)

@Serializable
data class LogoutBody(val refreshToken: String)

/* ---------------- Me & devices ---------------- */

@Serializable
data class PatchMeBody(val displayName: String? = null, val avatarUrl: String? = null)

@Serializable
data class RegisterDeviceBody(
    val pushToken: String,
    val platform: String,
    val kind: String,
    val appVersion: String,
)

@Serializable
data class UnregisterPushBody(val pushToken: String)

/* ---------------- Contacts ---------------- */

@Serializable
data class SyncContactsBody(val phones: List<String>, val names: List<String> = emptyList())

/* ---------------- Calls ---------------- */

@Serializable
data class CreateCallBody(
    val type: String = "one_to_one",
    val participantUserIds: List<String> = emptyList(),
    val videoEnabled: Boolean = true,
    val ringStyle: String = "call",
)

/** Shared response for POST /calls and POST /calls/:id/accept. */
@Serializable
data class CallSession(
    val call: Call,
    val joinToken: String,
    val sfuUrl: String,
    val iceServers: List<IceServer> = emptyList(),
)

@Serializable
data class CallsResponse(
    val calls: List<Call> = emptyList(),
    val nextCursor: String? = null,
)

/* ---------------- Errors ---------------- */

@Serializable
data class ApiError(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val retryAfter: Int? = null,
)

/* ---------------- WebSocket signaling (plane A) ---------------- */

/**
 * Server → client: incoming_call, call_accepted, call_declined, call_ended,
 * participant_joined, participant_left, presence_update, contacts_updated, knock.
 * Client → server: presence_ping, heartbeat, knock.
 *
 * Knock fields (a lightweight real-time presence ping):
 *  - send:    type=knock, to=<calleeUserId>, fromName, seq, dt
 *  - receive: type=knock, fromUserId, fromName, seq, dt
 */
@Serializable
data class SignalEnvelope(
    val type: String,
    val callId: String? = null,
    val callType: String? = null,
    val userId: String? = null,
    val fromUserId: String? = null,
    val fromName: String? = null,
    val phone: String? = null,
    val videoEnabled: Boolean? = null,
    val ringStyle: String? = null,
    val knock: Boolean? = null,
    /** Absolute server ring deadline (Unix epoch milliseconds). */
    val expiresAt: Long? = null,
    val call: Call? = null,
    val from: JsonElement? = null,
    // Knock relay fields.
    val to: String? = null,
    val seq: Int? = null,
    val dt: Int? = null,
)
