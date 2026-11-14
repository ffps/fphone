package app.fphone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Login form (until the user is logged in) and the list of contacts with Audio / Video buttons.
 * The whole content is rebuilt when something that is shown changes.
 */
class MainActivity : Activity(), CallManager.Listener {

    private lateinit var content: LinearLayout
    private var renderedKey = ""
    private var errorView: TextView? = null
    private var loginButton: Button? = null
    private var pendingCall: Pair<Contact, Boolean>? = null

    private val prefs: Prefs
        get() = app.prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(content)
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        app.calls.addListener(this)
        app.calls.onActivityStarted()
        render(true)
        if (prefs.loggedIn) {
            requestMissingPermissions()
            app.calls.loadContacts()
        }
    }

    override fun onStop() {
        app.calls.removeListener(this)
        app.calls.onActivityStopped()
        super.onStop()
    }

    override fun onChanged() {
        render(false)
    }

    // ---------- permissions ----------

    private fun granted(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            val p = pendingCall
            pendingCall = null
            if (p == null) return
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                Toast.makeText(this, R.string.perm_audio_needed, Toast.LENGTH_LONG).show()
                return
            }
            // Without the camera permission a video call becomes an audio call.
            startCall(p.first, p.second && granted(Manifest.permission.CAMERA))
        }
    }

    private fun callClicked(c: Contact, video: Boolean) {
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (video && !granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (need.isNotEmpty()) {
            pendingCall = Pair(c, video)
            requestPermissions(need.toTypedArray(), 10)
            return
        }
        startCall(c, video)
    }

    private fun startCall(c: Contact, video: Boolean) {
        app.calls.startCall(c.id, c.name, video)
        startActivity(CallActivity.intent(this))
    }

    // ---------- screens ----------

    private fun render(force: Boolean) {
        val calls = app.calls
        val key = if (!prefs.loggedIn) {
            "login"
        } else {
            "main|${calls.connected}|${calls.state}|" +
                calls.contacts.joinToString(",") { "${it.id}:${it.name}:${it.id in calls.online}:${it.reachable}" } +
                "|${prefs.userName}|${bannersKey()}"
        }
        if (!force && key == renderedKey) return
        renderedKey = key
        content.removeAllViews()
        errorView = null
        loginButton = null
        if (prefs.loggedIn) buildMain() else buildLogin()
    }

    private fun buildLogin() {
        content.addView(title(getString(R.string.title_login)))
        val server = field(R.string.hint_server, prefs.server, InputType.TYPE_TEXT_VARIATION_URI)
        val pin = field(R.string.hint_pin, prefs.pin, InputType.TYPE_CLASS_TEXT)
        val login = field(R.string.hint_login, prefs.login, InputType.TYPE_CLASS_TEXT)
        val password = field(
            R.string.hint_password, "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        content.addView(server)
        content.addView(pin)
        content.addView(login)
        content.addView(password)
        val err = TextView(this).apply {
            setTextColor(Color.parseColor("#c62828"))
            setPadding(0, dp(8), 0, dp(8))
        }
        errorView = err
        content.addView(err)
        val b = Button(this).apply {
            setText(R.string.btn_login)
            isAllCaps = false
            setOnClickListener {
                doLogin(server.text.toString(), pin.text.toString(), login.text.toString(), password.text.toString())
            }
        }
        loginButton = b
        content.addView(b)
    }

    private fun doLogin(serverText: String, pin: String, login: String, password: String) {
        val url = serverText.trim().trimEnd('/')
        if (!url.startsWith("https://")) {
            errorView?.text = getString(R.string.err_bad_url)
            return
        }
        if (login.isBlank() || password.isEmpty()) return
        prefs.server = url
        prefs.pin = pin.trim()
        errorView?.text = getString(R.string.logging_in)
        loginButton?.isEnabled = false

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcm = if (task.isSuccessful) (task.result ?: "") else ""
            if (fcm.isNotEmpty()) prefs.fcmToken = fcm
            val deviceName = Build.MANUFACTURER + " " + Build.MODEL
            Bg.run({ app.net.login(login.trim(), password, deviceName, fcm) }) { r ->
                r.onSuccess { j ->
                    val u = j.getJSONObject("user")
                    prefs.saveSession(j.getString("token"), u.getLong("id"), u.optString("name"), login.trim())
                    app.calls.onLoggedIn()
                    render(true)
                    requestMissingPermissions()
                }
                r.onFailure { e ->
                    loginButton?.isEnabled = true
                    errorView?.text = Texts.apiError(this, e)
                }
            }
        }
    }

    private fun buildMain() {
        val calls = app.calls
        content.addView(title(getString(R.string.hello, prefs.userName)))
        content.addView(TextView(this).apply {
            text = getString(if (calls.connected) R.string.conn_ok else R.string.conn_no)
            setTextColor(Color.parseColor(if (calls.connected) "#2e7d32" else "#c62828"))
            setPadding(0, 0, 0, dp(8))
        })

        addBanners()

        if (calls.contacts.isEmpty()) {
            content.addView(TextView(this).apply { setText(R.string.no_contacts) })
        }
        for (c in calls.contacts) {
            val on = c.id in calls.online
            // A user whose app is closed can still be called: the server wakes the phone with a push.
            val canCall = (on || c.reachable) && calls.state == CallManager.State.IDLE
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = (if (on) "\u25CF " else if (c.reachable) "\u25D0 " else "\u25CB ") + c.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(smallButton(R.string.btn_audio, canCall) { callClicked(c, false) })
            row.addView(smallButton(R.string.btn_video, canCall) { callClicked(c, true) })
            content.addView(row)
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        bottom.addView(smallButton(R.string.btn_log, true) { showLog() })
        bottom.addView(smallButton(R.string.btn_logout, true) { logout() })
        content.addView(bottom)
    }

    // Hints that help calls to arrive reliably.
    private fun bannersKey(): String = "${needFullScreenSetting()}|${!ignoringBattery()}"

    private fun needFullScreenSetting(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    private fun ignoringBattery(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun addBanners() {
        if (needFullScreenSetting()) {
            content.addView(TextView(this).apply { setText(R.string.hint_fullscreen) })
            content.addView(Button(this).apply {
                setText(R.string.btn_open_settings)
                isAllCaps = false
                setOnClickListener {
                    if (Build.VERSION.SDK_INT >= 34) {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                        )
                    }
                }
            })
        }
        if (!ignoringBattery()) {
            content.addView(TextView(this).apply {
                setText(R.string.hint_battery)
                setPadding(0, dp(8), 0, 0)
            })
            content.addView(Button(this).apply {
                setText(R.string.btn_battery)
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            })
        }
    }

    private fun logout() {
        Bg.run({ app.net.logout() }) { }
        prefs.clearSession()
        app.calls.onLoggedOut()
        render(true)
    }

    private fun showLog() {
        val text = MessageLog.text(this) + "\n\nPush token:\n" + prefs.fcmToken
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_log)
            .setMessage(text)
            .setPositiveButton(R.string.btn_close, null)
            .setNeutralButton(R.string.btn_copy_token) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("push token", prefs.fcmToken))
                Toast.makeText(this, R.string.toast_token_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------- small view helpers ----------

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    }

    private fun field(hint: Int, value: String, type: Int) = EditText(this).apply {
        setHint(hint)
        setText(value)
        inputType = type
        setSingleLine(true)
    }

    private fun smallButton(label: Int, enabled: Boolean, onClick: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        isEnabled = enabled
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
