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
        MessageLog.add(this, "PUSH", "Firebase messaging service started")
    }

    override fun onNewToken(token: String) {
        val calls = applicationContext.app.calls
        Handler(Looper.getMainLooper()).post { calls.updatePushToken(token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        // priority = what Android delivered, originalPriority = what the server asked for
        // (1 = high, 2 = normal). priority 2 for a call message means the phone delayed it.
        MessageLog.add(this, "PUSH", "push received: type=${d["type"]} priority=${msg.priority} original=${msg.originalPriority} call=${d["call"]}")

        val calls = applicationContext.app.calls
        val main = Handler(Looper.getMainLooper())
        when (d["type"]) {
            "call.incoming" -> {
                val id = d["call"] ?: return
                val fromId = d["from_id"]?.toLongOrNull() ?: 0L
                val exp = d["exp"]?.toLongOrNull() ?: 0L
                val name = d["from_name"] ?: ""
                val video = d["media"] == "video"
                val ackToken = d["ack_token"].orEmpty()
                main.post {
                    calls.onPushIncoming(id, fromId, name, video, exp)
                    if (ackToken.isNotEmpty()) {
                        Bg.run({
                            var failure: Exception? = null
                            for (attempt in 0 until 3) {
                                try {
                                    applicationContext.app.net.ackCallPush(id, ackToken)
                                    failure = null
                                    break
                                } catch (e: Exception) {
                                    failure = e
                                    if (attempt < 2) Thread.sleep(1000L * (attempt + 1))
                                }
                            }
                            if (failure != null) throw failure!!
                        }, { result ->
                            result.exceptionOrNull()?.let {
                                MessageLog.add(this, "PUSH", "call push acknowledgement failed: ${it.message}")
                            }
                        })
                    }
                }
            }
            "call.cancelled" -> {
                val id = d["call"] ?: return
                main.post { calls.onPushCancelled(id) }
            }
            "message" -> {
                val notificationId = d["time"]?.toLongOrNull()?.toInt() ?: 0
                Notifications.showTest(this, d["title"] ?: "Сообщение", d["text"] ?: "", notificationId)
                val messageId = d["message_id"].orEmpty()
                val ackToken = d["ack_token"].orEmpty()
                if (messageId.isNotEmpty() && ackToken.isNotEmpty()) {
                    Bg.run({
                        var failure: Exception? = null
                        for (attempt in 0 until 3) {
                            try {
                                applicationContext.app.net.ackMessage(messageId, ackToken)
                                failure = null
                                break
                            } catch (e: Exception) {
                                failure = e
                                if (attempt < 2) Thread.sleep(1000L * (attempt + 1))
                            }
                        }
                        if (failure != null) throw failure!!
                    }, { result ->
                        result.exceptionOrNull()?.let {
                            MessageLog.add(this, "PUSH", "message delivery acknowledgement failed: ${it.message}")
                        }
                    })
                }
            }
            "message.delivered" -> {
                val notificationId = d["time"]?.toLongOrNull()?.toInt() ?: 0
                Notifications.showTest(this, d["title"] ?: "Кукуха", d["text"] ?: "", notificationId)
            }
            "test" -> {
                val notificationId = d["time"]?.toLongOrNull()?.toInt() ?: 0
                Notifications.showTest(this, d["title"] ?: getString(R.string.notif_test_title), d["text"] ?: "", notificationId)
            }
        }
    }

    override fun onDeletedMessages() {
        MessageLog.add(this, "push messages were dropped by the server (too many or too old)")
    }
}
