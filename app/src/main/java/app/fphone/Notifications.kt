package app.fphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat

object Notifications {
    const val CH_INCOMING = "incoming_v1"
    const val CH_ONGOING = "ongoing_v1"
    const val CH_MISC = "misc_v1"
    const val ID_TEST = 1003
    const val ID_INCOMING = 1001
    const val ID_ONGOING = 1002
    const val ACTION_DECLINE = "app.fphone.DECLINE"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val incoming = NotificationChannel(
            CH_INCOMING, ctx.getString(R.string.channel_incoming), NotificationManager.IMPORTANCE_HIGH
        )
        incoming.setSound(ringtone, attrs)
        incoming.enableVibration(true)
        incoming.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(incoming)

        val ongoing = NotificationChannel(
            CH_ONGOING, ctx.getString(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ongoing)

        val misc = NotificationChannel(
            CH_MISC, ctx.getString(R.string.channel_misc), NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(misc)
    }

    /** Aggregated notification about missed incoming calls. */
    fun showMissed(ctx: Context, name: String) {
        ensureChannels(ctx)
        val prefs = ctx.getSharedPreferences("fphone_notifications", Context.MODE_PRIVATE)
        val count = prefs.getInt("missed_count", 0) + 1
        prefs.edit().putInt("missed_count", count).putString("missed_last_name", name).apply()
        val open = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1), immutable()
        )
        val title = if (count == 1) ctx.getString(R.string.notif_missed_title)
            else "Пропущенные звонки: $count"
        val n = NotificationCompat.Builder(ctx, CH_MISC)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(name)
            .setNumber(count)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(2000, n)
    }

    fun clearMissed(ctx: Context) {
        ctx.getSharedPreferences("fphone_notifications", Context.MODE_PRIVATE).edit()
            .remove("missed_count").remove("missed_last_name").apply()
        ctx.getSystemService(NotificationManager::class.java).cancel(2000)
    }

    /** Simple notification for the test message of "fphone push-test" on the server. */
    fun showTest(ctx: Context, text: String) {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(ctx, 6, Intent(ctx, MainActivity::class.java), immutable())
        val n = NotificationCompat.Builder(ctx, CH_MISC)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(ctx.getString(R.string.notif_test_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_TEST, n)
    }

    private fun immutable(): Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** Heads-up / full-screen notification with ringtone for an incoming call. */
    fun showIncoming(ctx: Context, name: String, video: Boolean, timeoutMs: Long) {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(ctx, 1, CallActivity.intent(ctx), immutable())
        val accept = PendingIntent.getActivity(
            ctx, 2, CallActivity.intent(ctx).putExtra(CallActivity.EXTRA_ACCEPT, true), immutable()
        )
        val decline = PendingIntent.getBroadcast(
            ctx, 3, Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_DECLINE), immutable()
        )
        val b = NotificationCompat.Builder(ctx, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(name)
            .setContentText(ctx.getString(if (video) R.string.call_incoming_video else R.string.call_incoming_audio))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.btn_decline), decline)
            .addAction(0, ctx.getString(R.string.btn_accept), accept)
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // repeat the ringtone until the notification is removed
        ctx.getSystemService(NotificationManager::class.java).notify(ID_INCOMING, n)
    }

    fun cancelIncoming(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_INCOMING)
    }

    /** Notification of the foreground service that keeps a call alive. */
    fun ongoing(ctx: Context, name: String, hangup: PendingIntent): Notification {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(ctx, 5, CallActivity.intent(ctx), immutable())
        return NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(ctx.getString(R.string.notif_ongoing_title))
            .setContentText(ctx.getString(R.string.notif_ongoing_text, name))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.btn_hangup), hangup)
            .build()
    }
}
