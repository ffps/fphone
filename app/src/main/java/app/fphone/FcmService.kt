package app.fphone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives push messages from the fPhone server. This test version only logs them and shows a
 * notification, which is enough to prove that the app is woken up when it is not running.
 */
class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        getSharedPreferences("fphone", Context.MODE_PRIVATE).edit().putString("token", token).apply()
        MessageLog.add(this, "new token (${token.length} chars)")
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        // priority = what Android delivered, originalPriority = what the server asked for
        // (1 = high, 2 = normal, 0 = unknown). If priority is 2 for a call message, the phone
        // delayed it (battery saving).
        MessageLog.add(this, "push: priority=${msg.priority} original=${msg.originalPriority} data=$d")

        when (d["type"]) {
            "call.incoming" -> {
                val who = d["from_name"] ?: ("user #" + d["from_id"])
                showNotification("Incoming call", "$who (${d["media"]})")
            }
            "call.cancelled" -> {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
                MessageLog.add(this, "call cancelled, notification removed")
            }
            "test" -> showNotification("Test message", d["text"] ?: "")
            else -> showNotification("Message", d.toString())
        }
    }

    private fun showNotification(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            MessageLog.add(this, "notification not shown: permission is not granted")
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL = "calls"
        private const val NOTIFICATION_ID = 1001
    }
}
