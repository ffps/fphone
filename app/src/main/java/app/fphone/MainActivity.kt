package app.fphone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.fphone.ui.FPhoneTheme
import app.fphone.ui.LoginScreen
import app.fphone.ui.MainScreen
import app.fphone.ui.rememberApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Hosts the login screen and the main screen (all drawn with Compose, see the ui package).
 * It also does what needs an Activity: asking for permissions, starting the call screen, login.
 */
class MainActivity : ComponentActivity(), CallManager.Listener, Updater.Listener, History.Listener {

    companion object {
        /** 0 = Contacts, 1 = Recents (used by the "missed call" notification). */
        const val EXTRA_TAB = "tab"
    }

    private val dynamicColor = mutableStateOf(false)
    private val loginBusy = mutableStateOf(false)
    private val loginError = mutableStateOf<String?>(null)
    private val startTab = mutableIntStateOf(0)
    private var installRequested = false
    private data class PendingCall(val peerId: Long, val name: String, val video: Boolean, val fromRecents: Boolean)
    private var pendingCall: PendingCall? = null

    /** Background permission request (audio, camera, notifications) � no follow-up action. */
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Permission states may have changed; redraw the screens.
        app.uiTick.intValue++
    }

    /** Permission request triggered right before a call; resumes [pendingCall] afterwards. */
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        app.uiTick.intValue++
        val p = pendingCall
        pendingCall = null
        if (p == null) return@registerForActivityResult
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, R.string.perm_audio_needed, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        // Without the camera permission a video call becomes an audio call.
        startCall(p.peerId, p.name, p.video && granted(Manifest.permission.CAMERA), p.fromRecents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        dynamicColor.value = app.prefs.dynamicColor
        startTab.intValue = intent.getIntExtra(EXTRA_TAB, 0)
        if (startTab.intValue == 1) Notifications.clearMissed(this)
        if (app.prefs.loggedIn) refreshFcmTokenAtStartup()
        setContent {
            val prefs = rememberApp().prefs
            FPhoneTheme(dynamicColor.value, forceLight = !prefs.loggedIn) {
                if (!prefs.loggedIn) {
                    LoginScreen(
                        initialServer = prefs.server,
                        initialPin = prefs.pin,
                        initialLogin = prefs.login,
                        busy = loginBusy.value,
                        error = loginError.value,
                        onLogin = { server, pin, login, password -> doLogin(server, pin, login, password) }
                    )
                } else {
                    MainScreen(
                        startTab = startTab.intValue,
                        dynamicColor = dynamicColor.value,
                        onDynamicColor = { v ->
                            dynamicColor.value = v
                            app.prefs.dynamicColor = v
                        },
                        onCall = { id, name, video, fromRecents -> callClicked(id, name, video, fromRecents) },
                        onUpdate = { updateClicked() },
                        onLogout = { logout() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startTab.intValue = intent.getIntExtra(EXTRA_TAB, 0)
        if (startTab.intValue == 1) Notifications.clearMissed(this)
    }

    override fun onStart() {
        super.onStart()
        app.calls.addListener(this)
        app.updater.addListener(this)
        app.history.addListener(this)
        app.calls.onActivityStarted()
        app.updater.autoCheck()
        if (app.prefs.loggedIn) {
            requestMissingPermissions()
            app.calls.loadContacts()
        }
        app.uiTick.intValue++ // permission states may have changed while the app was away
    }

    override fun onStop() {
        app.calls.removeListener(this)
        app.updater.removeListener(this)
        app.history.removeListener(this)
        app.calls.onActivityStopped()
        super.onStop()
    }

    // Every change of the call manager, the updater or the history makes the screens redraw.
    override fun onChanged() {
        app.uiTick.intValue++
    }

    override fun onHistoryChanged() {
        app.uiTick.intValue++
    }

    override fun onUpdateChanged() {
        app.uiTick.intValue++
        if (app.updater.kind == Updater.Kind.READY && installRequested) {
            installRequested = false
            app.updater.install(this)
        }
    }

    // ---------- permissions and calls ----------

    private fun granted(p: String): Boolean =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) permissionsLauncher.launch(need.toTypedArray())
    }

    private fun callClicked(peerId: Long, name: String, video: Boolean, fromRecents: Boolean) {
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (video && !granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (need.isNotEmpty()) {
            pendingCall = PendingCall(peerId, name, video, fromRecents)
            callPermissionLauncher.launch(need.toTypedArray())
            return
        }
        startCall(peerId, name, video, fromRecents)
    }

    private fun startCall(peerId: Long, name: String, video: Boolean, fromRecents: Boolean) {
        app.calls.startCall(peerId, name, video, fromRecents)
        startActivity(CallActivity.intent(this))
    }

    // ---------- login / logout / update ----------

    private fun refreshFcmTokenAtStartup() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result.orEmpty() else ""
                if (token.isNotBlank()) app.calls.updatePushToken(token)
                else {
                    app.prefs.pushIssue = true
                    MessageLog.add(this, "PUSH", "startup token fetch failed; keeping previous token: ${task.exception}")
                    app.uiTick.intValue++
                }
            }
        } catch (e: Exception) {
            app.prefs.pushIssue = true
            MessageLog.add(this, "PUSH", "Firebase unavailable at startup; keeping previous token: $e")
            app.uiTick.intValue++
        }
    }

    private fun doLogin(serverText: String, pin: String, login: String, password: String) {
        var url = serverText.trim().trimEnd('/')
        if (!url.contains("://")) url = "https://$url"
        val parsed = try { Uri.parse(url) } catch (_: Exception) { null }
        if (!url.startsWith("https://", true) || parsed?.host.isNullOrBlank()) {
            loginError.value = getString(R.string.err_bad_url)
            return
        }
        url += "/"
        if (login.isBlank() || password.isEmpty()) return
        val prefs = app.prefs
        prefs.server = url
        prefs.pin = pin.trim()
        loginError.value = null
        loginBusy.value = true
        val submit: (String) -> Unit = { fcm ->
            if (fcm.isNotEmpty()) prefs.fcmToken = fcm
            val deviceName = Build.MANUFACTURER + " " + Build.MODEL
            Bg.run({ app.net.login(login.trim(), password, deviceName, fcm) }) { r ->
                loginBusy.value = false
                r.onSuccess { j ->
                    val u = j.getJSONObject("user")
                    prefs.saveSession(j.getString("token"), u.getLong("id"), u.optString("name"), login.trim())
                    if (fcm.isNotEmpty()) {
                        prefs.registeredFcmToken = fcm
                        if (prefs.pendingFcmToken == fcm) prefs.pendingFcmToken = ""
                        prefs.pushIssue = prefs.pendingFcmToken.isNotEmpty()
                    }
                    app.calls.onLoggedIn()
                    requestMissingPermissions()
                }
                r.onFailure { e -> loginError.value = Texts.apiError(this, e) }
            }
        }
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result.orEmpty() else ""
                if (token.isNotBlank()) submit(token)
                else {
                    prefs.pushIssue = true
                    MessageLog.add(this, "PUSH", "Firebase token fetch failed during login; using previous token if available: ${task.exception}")
                    app.uiTick.intValue++
                    submit(prefs.fcmToken)
                }
            }
        } catch (e: Exception) {
            prefs.pushIssue = true
            MessageLog.add(this, "PUSH", "Firebase unavailable during login; using previous token if available: $e")
            app.uiTick.intValue++
            submit(prefs.fcmToken)
        }
    }

    private fun logout() {
        // Keep the token for the background logout request before clearing the session.
        val token = app.prefs.sessionToken
        Bg.run({ app.net.logout(token) }) { }
        app.prefs.clearSession()
        app.calls.onLoggedOut()
    }

    private fun updateClicked() {
        if (app.calls.state != CallManager.State.IDLE) {
            Toast.makeText(this, R.string.update_busy_call, Toast.LENGTH_LONG).show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            // The user has to allow installing from this app once; then press "Update" again.
            Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        installRequested = true
        app.updater.download()
    }
}
