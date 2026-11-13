package app.fphone

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Diagnostic screen for the push test: shows the push token (to paste into
 * "fphone push-test TOKEN" on the server) and a log of everything that was received.
 * It is plain Android views on purpose: no extra libraries, nothing to break.
 */
class MainActivity : Activity() {

    private lateinit var infoView: TextView
    private lateinit var tokenView: TextView
    private lateinit var logView: TextView
    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "fPhone push test  v${versionName()}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
        })
        infoView = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(infoView)

        root.addView(TextView(this).apply { text = "Push token:" })
        tokenView = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        root.addView(tokenView)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Get token") { fetchToken() })
        row1.addView(button("Copy token") { copyToken() })
        row1.addView(button("Clear log") { MessageLog.clear(this) })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Battery settings") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        row2.addView(button("Check FCM ports") { checkPorts() })
        root.addView(row2)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        root.addView(
            ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        fetchToken()
    }

    override fun onResume() {
        super.onResume()
        MessageLog.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        MessageLog.listener = null
    }

    private fun fetchToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                MessageLog.add(this, "cannot get token: ${task.exception}")
                return@addOnCompleteListener
            }
            token = task.result ?: ""
            getSharedPreferences("fphone", Context.MODE_PRIVATE).edit().putString("token", token).apply()
            MessageLog.add(this, "token received (${token.length} chars)")
        }
    }

    private fun copyToken() {
        if (token.isEmpty()) {
            Toast.makeText(this, "No token yet", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("push token", token))
        Toast.makeText(this, "Token copied", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = if (pm.isIgnoringBatteryOptimizations(packageName)) "ignored (good)" else "active (may delay pushes)"
        val notif = if (NotificationManagerCompat.from(this).areNotificationsEnabled()) "allowed" else "BLOCKED"
        infoView.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "network: ${networkName()}\n" +
            "battery optimization: $battery\nnotifications: $notif"
        tokenView.text = if (token.isEmpty()) "(no token yet)" else token
        logView.text = MessageLog.text(this)
    }

    private fun networkName(): String {
        if (Build.VERSION.SDK_INT < 23) return "n/a"
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    }

    /**
     * Android keeps one permanent connection to Google push servers (mtalk.google.com, ports
     * 5228-5230; 443 is the fallback). If this network blocks them, no push can arrive.
     * This check tries to open a TCP connection to each port and writes the result to the log.
     */
    private fun checkPorts() {
        MessageLog.add(this, "checking connections to Google push servers (network: ${networkName()})")
        Thread {
            for (port in intArrayOf(5228, 5229, 5230, 443)) {
                val start = System.currentTimeMillis()
                val result = try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("mtalk.google.com", port), 5000)
                    }
                    "OK"
                } catch (e: Exception) {
                    "FAILED (${e.javaClass.simpleName}: ${e.message})"
                }
                MessageLog.add(this, "mtalk.google.com:$port $result, ${System.currentTimeMillis() - start} ms")
            }
        }.start()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun versionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
