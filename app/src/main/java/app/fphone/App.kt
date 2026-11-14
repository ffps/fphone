package app.fphone

import android.app.Application
import android.content.Context

/** Process-wide objects: settings, network client and the call manager. */
class App : Application() {
    val prefs: Prefs by lazy { Prefs(this) }
    val net: Net by lazy { Net(prefs) }
    val calls: CallManager by lazy { CallManager(this) }
    val updater: Updater by lazy { Updater(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }
}

val Context.app: App
    get() = applicationContext as App
