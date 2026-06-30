package ai.exla.slide.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Secure, persistent token + identity storage backed by the Android Keystore
 * via EncryptedSharedPreferences.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "slide_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var phone: String?
        get() = prefs.getString(KEY_PHONE, null)
        set(value) = prefs.edit().putString(KEY_PHONE, value).apply()

    var displayName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    var pushToken: String?
        get() = prefs.getString(KEY_PUSH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_PUSH_TOKEN, value).apply()

    /** Provider token received before server registration has completed. */
    var pendingPushToken: String?
        get() = prefs.getString(KEY_PENDING_PUSH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_PENDING_PUSH_TOKEN, value).apply()

    /** Synchronous handoff used by Firebase callbacks before their service returns. */
    fun persistPendingPushToken(token: String) {
        prefs.edit().putString(KEY_PENDING_PUSH_TOKEN, token).commit()
    }

    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrEmpty() && !refreshToken.isNullOrEmpty()

    fun saveTokens(access: String, refresh: String) {
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .apply()
    }

    /** Stable URL-safe idempotency identity for this app installation. */
    val callAcceptKey: String
        get() = synchronized(prefs) {
            prefs.getString(KEY_CALL_ACCEPT, null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { generated ->
                    prefs.edit().putString(KEY_CALL_ACCEPT, generated).commit()
                }
        }

    fun clear() {
        // Account credentials/tokens are cleared on logout; installation
        // identity intentionally survives for stable accept retry semantics.
        val acceptKey = prefs.getString(KEY_CALL_ACCEPT, null)
        val editor = prefs.edit().clear()
        if (acceptKey != null) editor.putString(KEY_CALL_ACCEPT, acceptKey)
        editor.apply()
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PHONE = "phone"
        private const val KEY_NAME = "display_name"
        private const val KEY_PUSH_TOKEN = "push_token"
        private const val KEY_PENDING_PUSH_TOKEN = "pending_push_token"
        private const val KEY_CALL_ACCEPT = "call_accept_key"
    }
}
