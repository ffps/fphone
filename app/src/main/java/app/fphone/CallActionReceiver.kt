package app.fphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the "Decline" button of the incoming call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Notifications.ACTION_DECLINE) {
            context.app.calls.reject()
        }
    }
}
