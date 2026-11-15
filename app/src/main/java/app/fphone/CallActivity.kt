package app.fphone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.fphone.ui.CallScreen
import app.fphone.ui.FPhoneTheme

/**
 * Hosts the call screen (see ui/CallScreen.kt). It is shown over the lock screen for an incoming
 * call and keeps the display on during the call.
 */
class CallActivity : ComponentActivity(), CallManager.Listener {

    companion object {
        const val EXTRA_ACCEPT = "accept"

        fun intent(ctx: Context): Intent =
            Intent(ctx, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    // Registered before onCreate; must not be called after STARTED.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true

        val c = app.calls.call ?: return@registerForActivityResult
        if (!audioGranted) {
            // A call is not possible without the microphone.
            app.calls.reject()
        } else {
            // Without the camera permission the video call is answered with sound only.
            app.calls.accept(c.video && cameraGranted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and wake the display (incoming call).
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge()
        setContent {
            FPhoneTheme(app.prefs.dynamicColor) {
                CallScreen(onAccept = { acceptClicked() }, onFinish = { finish() })
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent) {
        if (i.getBooleanExtra(EXTRA_ACCEPT, false)) {
            i.removeExtra(EXTRA_ACCEPT)
            if (app.calls.state == CallManager.State.INCOMING) acceptClicked()
        }
    }

    override fun onStart() {
        super.onStart()
        app.calls.addListener(this)
        app.calls.onActivityStarted()
        app.uiTick.intValue++
    }

    override fun onStop() {
        app.calls.removeListener(this)
        app.calls.onActivityStopped()
        super.onStop()
    }

    override fun onChanged() {
        app.uiTick.intValue++
    }

    // ---------- answering ----------

    private fun granted(p: String): Boolean =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun acceptClicked() {
        val c = app.calls.call ?: return
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (c.video && !granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (need.isEmpty()) {
            app.calls.accept(c.video)
        } else {
            permissionLauncher.launch(need.toTypedArray())
        }
    }
}