package app.fphone

import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives push messages from the fPhone server (data messages with high priority).
 * An incoming call arrives as "call.incoming"; if the caller hangs up before the phone answered,
 * "call.cancelled" follows. The messages are handed to CallManager on the main thread.
 */
class FcmService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        MessageLog.add(this, "push service started")
    }

    override fun onNewToken(token: String) {
        MessageLog.add(this, "new push token (${token.length} chars)")
        val calls = applicationContext.app.calls
        Handler(Looper.getMainLooper()).post { calls.updatePushToken(token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        // priority = what Android delivered, originalPriority = what the server asked for
        // (1 = high, 2 = normal). priority 2 for a call message means the phone delayed it.
        MessageLog.add(this, "push: type=${d["type"]} priority=${msg.priority} original=${msg.originalPriority}")

        val calls = applicationContext.app.calls
        val main = Handler(Looper.getMainLooper())
        when (d["type"]) {
            "call.incoming" -> {
                val id = d["call"] ?: return
                val fromId = d["from_id"]?.toLongOrNull() ?: 0L
                val exp = d["exp"]?.toLongOrNull() ?: 0L
                val name = d["from_name"] ?: ""
                val video = d["media"] == "video"
                main.post { calls.onPushIncoming(id, fromId, name, video, exp) }
            }
            "call.cancelled" -> {
                val id = d["call"] ?: return
                main.post { calls.onPushCancelled(id) }
            }
            "test" -> Notifications.showTest(this, d["text"] ?: "")
        }
    }

    override fun onDeletedMessages() {
        MessageLog.add(this, "push messages were dropped by the server (too many or too old)")
    }
}
