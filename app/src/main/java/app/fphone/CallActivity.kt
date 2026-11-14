package app.fphone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * The call screen: incoming call (Accept / Decline), outgoing call, and the running call with
 * video. It is shown over the lock screen for incoming calls.
 */
class CallActivity : Activity(), CallManager.Listener {

    companion object {
        const val EXTRA_ACCEPT = "accept"

        fun intent(ctx: Context): Intent =
            Intent(ctx, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var incomingRow: LinearLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var hangupButton: Button
    private lateinit var muteButton: Button
    private lateinit var cameraButton: Button
    private lateinit var flipButton: Button
    private lateinit var speakerButton: Button

    private var renderersInited = false
    private var attachedEngine: RtcEngine? = null
    private var sawCall = false
    private var endScheduled = false

    private val tick = object : Runnable {
        override fun run() {
            update()
            ui.postDelayed(this, 1000)
        }
    }
    private val finishLater = Runnable { finish() }

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
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        buildViews()
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
        ui.post(tick)
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
        app.calls.removeListener(this)
        attachedEngine?.detachRenderers()
        attachedEngine = null
        app.calls.onActivityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacks(finishLater)
        releaseRenderers()
        super.onDestroy()
    }

    override fun onChanged() {
        update()
    }

    // ---------- views ----------

    private fun buildViews() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        remoteView = SurfaceViewRenderer(this)
        root.addView(remoteView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        localView = SurfaceViewRenderer(this)
        localView.setZOrderMediaOverlay(true)
        localView.setMirror(true)
        val lp = FrameLayout.LayoutParams(dp(110), dp(150), Gravity.TOP or Gravity.END)
        lp.setMargins(dp(12), dp(12), dp(12), dp(12))
        root.addView(localView, lp)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(140), dp(16))
        }
        nameView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        info.addView(nameView)
        info.addView(statusView)
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        incomingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        incomingRow.addView(button(R.string.btn_decline, "#c62828") { app.calls.reject() })
        incomingRow.addView(button(R.string.btn_accept, "#2e7d32") { acceptClicked() })

        controlsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        muteButton = button(R.string.btn_mute, "#455a64") { app.calls.toggleMute() }
        cameraButton = button(R.string.btn_camera_off, "#455a64") { app.calls.toggleCamera() }
        flipButton = button(R.string.btn_flip, "#455a64") { app.calls.switchCamera() }
        speakerButton = button(R.string.btn_speaker, "#455a64") { app.calls.toggleSpeaker() }
        controlsRow.addView(muteButton)
        controlsRow.addView(cameraButton)
        controlsRow.addView(flipButton)
        controlsRow.addView(speakerButton)

        hangupButton = button(R.string.btn_hangup, "#c62828") { app.calls.hangup() }

        bottom.addView(incomingRow)
        bottom.addView(controlsRow)
        bottom.addView(hangupButton)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
    }

    private fun button(label: Int, color: String, onClick: () -> Unit): Button = Button(this).apply {
        setText(label)
        setTextColor(Color.WHITE)
        isAllCaps = false
        setBackgroundColor(Color.parseColor(color))
        setOnClickListener { onClick() }
        val p = LinearLayout.LayoutParams(0, dp(56), 1f)
        p.setMargins(dp(4), dp(4), dp(4), dp(4))
        layoutParams = p
    }

    // ---------- state ----------

    private fun update() {
        val calls = app.calls
        val c = calls.call
        if (calls.state == CallManager.State.IDLE || c == null) {
            showEnded(calls)
            return
        }
        sawCall = true
        endScheduled = false
        ui.removeCallbacks(finishLater)

        nameView.text = c.peerName
        val st = calls.state
        statusView.text = when (st) {
            CallManager.State.INCOMING -> getString(if (c.video) R.string.call_incoming_video else R.string.call_incoming_audio)
            CallManager.State.OUTGOING -> getString(if (calls.ringing) R.string.call_ringing else R.string.call_calling)
            CallManager.State.CONNECTING -> getString(R.string.call_connecting)
            CallManager.State.ACTIVE -> duration(calls)
            else -> ""
        }

        val incoming = st == CallManager.State.INCOMING
        val inCall = st == CallManager.State.CONNECTING || st == CallManager.State.ACTIVE
        incomingRow.visibility = if (incoming) View.VISIBLE else View.GONE
        controlsRow.visibility = if (inCall) View.VISIBLE else View.GONE
        hangupButton.visibility = if (incoming) View.GONE else View.VISIBLE

        muteButton.setText(if (calls.muted) R.string.btn_unmute else R.string.btn_mute)
        speakerButton.setText(if (calls.speaker) R.string.btn_earpiece else R.string.btn_speaker)
        cameraButton.setText(if (calls.cameraOn) R.string.btn_camera_off else R.string.btn_camera_on)
        val videoButtons = if (c.video) View.VISIBLE else View.GONE
        cameraButton.visibility = videoButtons
        flipButton.visibility = videoButtons

        // Video views are used only in video calls.
        remoteView.visibility = if (c.video) View.VISIBLE else View.GONE
        localView.visibility = if (c.video && calls.cameraOn) View.VISIBLE else View.GONE

        val e = calls.engine
        if (e != null && c.video) {
            ensureRenderers(e)
            if (attachedEngine !== e) {
                e.attachRenderers(localView, remoteView)
                attachedEngine = e
            }
        }
    }

    private fun showEnded(calls: CallManager) {
        if (!sawCall) {
            finish() // nothing to show (for example, a stale notification)
            return
        }
        statusView.text = Texts.reason(this, calls.lastEndReason)
        incomingRow.visibility = View.GONE
        controlsRow.visibility = View.GONE
        hangupButton.visibility = View.GONE
        attachedEngine = null
        if (!endScheduled) {
            endScheduled = true
            ui.postDelayed(finishLater, 1500)
        }
    }

    private fun duration(calls: CallManager): String {
        val sec = ((SystemClock.elapsedRealtime() - calls.connectedSince) / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", sec / 60, sec % 60)
    }

    private fun ensureRenderers(e: RtcEngine) {
        if (renderersInited) return
        remoteView.init(e.eglContext, null)
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        remoteView.setEnableHardwareScaler(true)
        localView.init(e.eglContext, null)
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localView.setEnableHardwareScaler(true)
        renderersInited = true
    }

    private fun releaseRenderers() {
        if (!renderersInited) return
        remoteView.release()
        localView.release()
        renderersInited = false
    }

    // ---------- answering ----------

    private fun granted(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun acceptClicked() {
        val c = app.calls.call ?: return
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (c.video && !granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (need.isEmpty()) {
            app.calls.accept(c.video)
        } else {
            requestPermissions(need.toTypedArray(), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 2) return
        val c = app.calls.call ?: return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            app.calls.reject() // a call is not possible without the microphone
            return
        }
        // Without the camera permission the video call is answered with sound only.
        app.calls.accept(c.video && granted(Manifest.permission.CAMERA))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
