package app.fphone

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/** Process-wide objects: settings, network client and the call manager. */
class App : Application() {
    val prefs: Prefs by lazy { Prefs(this) }
    val net: Net by lazy { Net(prefs) }
    val calls: CallManager by lazy { CallManager(this) }
    val updater: Updater by lazy { Updater(this) }
    val history: History by lazy { History(this) }
    val avatars: Avatars by lazy { Avatars(this) }

    /**
     * Raised whenever the call manager, the updater or the history changed. The Compose screens read
     * it (see rememberApp) and so redraw themselves.
     */
    val uiTick = mutableIntStateOf(0)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }
}

val Context.app: App
    get() = applicationContext as App
