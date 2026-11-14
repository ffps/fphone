package app.fphone

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process (and the microphone and camera) alive while a
 * call is going on, also when the screen is off or the app is in the background.
 * It is started by CallManager from a visible screen and stopped when the call ends.
 */
class CallService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            applicationContext.app.calls.hangup()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_NAME) ?: ""
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false

        val hangup = PendingIntent.getService(
            this, 4,
            Intent(this, CallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notifications.ongoing(this, name, hangup)

        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (video && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_ONGOING, notification, types)
        } catch (e: Exception) {
            MessageLog.add(this, "cannot start the call service: $e")
            stopSelf()
            return START_NOT_STICKY
        }

        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fphone:call").apply {
                acquire(4 * 60 * 60 * 1000L) // safety limit: 4 hours
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_HANGUP = "app.fphone.HANGUP"
        const val EXTRA_NAME = "name"
        const val EXTRA_VIDEO = "video"

        fun start(ctx: Context, name: String, video: Boolean) {
            val i = Intent(ctx, CallService::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_VIDEO, video)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CallService::class.java))
        }
    }
}
