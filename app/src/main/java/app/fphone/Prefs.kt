package app.fphone

import android.content.Context
import java.util.UUID

/** Settings and session data of this installation. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fphone", Context.MODE_PRIVATE)

    private fun str(key: String): String = sp.getString(key, "") ?: ""
    private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()

    /** Server address, normalized as https://domain.com/. */
    var server: String
        get() = str("server")
        set(v) = put("server", v)

    /** Optional pin of the server certificate key: "sha256/<base64>". Empty = normal trust store. */
    var pin: String
        get() = str("pin")
        set(v) = put("pin", v)

    var login: String
        get() = str("login")
        set(v) = put("login", v)

    var sessionToken: String
        get() = str("session_token")
        set(v) = put("session_token", v)

    var userId: Long
        get() = sp.getLong("user_id", 0L)
        set(v) = sp.edit().putLong("user_id", v).apply()

    var userName: String
        get() = str("user_name")
        set(v) = put("user_name", v)

    /** Video capture and sender limits received from GET /me. */
    var videoConfig: String
        get() = str("video_config")
        set(v) = put("video_config", v)

    /** Last push token that Firebase gave to this installation. */
    var fcmToken: String
        get() = str("fcm_token")
        set(v) = put("fcm_token", v)

    var registeredFcmToken: String
        get() = str("registered_fcm_token")
        set(v) = put("registered_fcm_token", v)

    var pendingFcmToken: String
        get() = str("pending_fcm_token")
        set(v) = put("pending_fcm_token", v)

    var lastFcmRefreshAt: Long
        get() = sp.getLong("last_fcm_refresh_at", 0L)
        set(v) = sp.edit().putLong("last_fcm_refresh_at", v).apply()

    var pushIssue: Boolean
        get() = sp.getBoolean("push_issue", false)
        set(v) = sp.edit().putBoolean("push_issue", v).apply()

    /** The order of the contact list that the user made by dragging (user IDs). */
    var contactOrder: List<Long>
        get() = str("contact_order").split(',').mapNotNull { it.toLongOrNull() }
        set(v) = put("contact_order", v.joinToString(","))

    /** Use the colors of the wallpaper (Material You) instead of the fPhone colors. */
    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamic_color", false)
        set(v) = sp.edit().putBoolean("dynamic_color", v).apply()

    val loggedIn: Boolean
        get() = sessionToken.isNotEmpty() && server.isNotEmpty()

    /** Stable random ID of this installation (the server calls it device.id). */
    val deviceId: String
        get() {
            var d = sp.getString("device_id", null)
            if (d == null) {
                d = UUID.randomUUID().toString()
                sp.edit().putString("device_id", d).apply()
            }
            return d
        }

    fun saveSession(token: String, userId: Long, name: String, login: String) {
        this.sessionToken = token
        this.userId = userId
        this.userName = name
        this.login = login
    }

    fun clearSession() {
        sp.edit().remove("session_token").remove("user_id").remove("user_name").apply()
    }
}
