package app.fphone.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.fphone.CallManager
import app.fphone.R
import app.fphone.RtcEngine
import app.fphone.Texts
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** A video view of the WebRTC library (own camera or the other person). */
@Composable
private fun VideoSurface(engine: RtcEngine, local: Boolean, modifier: Modifier) {
    val holder = remember { arrayOfNulls<SurfaceViewRenderer>(1) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).also { v ->
                v.init(engine.eglContext, null)
                v.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                v.setEnableHardwareScaler(true)
                if (local) {
                    v.setMirror(true)
                    v.setZOrderMediaOverlay(true)
                    engine.setLocalSink(v)
                } else {
                    engine.setRemoteSink(v)
                }
                holder[0] = v
            }
        }
    )
    DisposableEffect(engine) {
        onDispose {
            if (local) engine.setLocalSink(null) else engine.setRemoteSink(null)
            holder[0]?.release()
            holder[0] = null
        }
    }
}

/**
 * The call screen. It reads everything from CallManager: incoming call (Decline / Answer),
 * calling, connecting, and the running call (audio or video) with the control buttons.
 */
@Composable
fun CallScreen(onAccept: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val calls = app.calls
    val st = calls.state
    val current = calls.call

    // Remember the call while it exists: the screen still shows its name for a moment after the end.
    var last by remember { mutableStateOf<CallManager.CallInfo?>(null) }
    SideEffect { if (current != null) last = current }
    val info = current ?: last

    var sawCall by remember { mutableStateOf(false) }
    LaunchedEffect(st) {
        if (st == CallManager.State.IDLE) {
            if (sawCall) delay(1500)
            onFinish()
        } else {
            sawCall = true
        }
    }

    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(st, calls.connectedSince) {
        while (st == CallManager.State.ACTIVE) {
            seconds = (SystemClock.elapsedRealtime() - calls.connectedSince) / 1000L
            delay(1000)
        }
    }

    val engine = calls.engine
    val inCall = st == CallManager.State.CONNECTING || st == CallManager.State.ACTIVE
    val videoOn = info?.video == true && engine != null && inCall
    val peerName = info?.peerName ?: calls.lastPeerName
    val avatarVersion = calls.contacts.firstOrNull { it.id == info?.peerId }?.avatar ?: 0L
    val foreground = if (videoOn) Color.White else MaterialTheme.colorScheme.onBackground

    val status = when (st) {
        CallManager.State.INCOMING -> stringResource(if (info?.video == true) R.string.call_incoming_video else R.string.call_incoming_audio)
        CallManager.State.OUTGOING -> stringResource(if (calls.ringing) R.string.call_ringing else R.string.call_calling)
        CallManager.State.CONNECTING -> stringResource(R.string.call_connecting)
        CallManager.State.ACTIVE -> "%d:%02d".format(seconds / 60, seconds % 60)
        CallManager.State.IDLE -> Texts.reason(ctx, calls.lastEndReason)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (videoOn) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        if (videoOn && engine != null) {
            VideoSurface(engine, local = false, modifier = Modifier.fillMaxSize())
            if (calls.cameraOn) {
                VideoSurface(
                    engine, local = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(12.dp)
                        .size(110.dp, 150.dp)
                )
            }
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (videoOn) {
                // Video: the name and time in the corner, the picture is the main thing.
                Column(Modifier.fillMaxWidth().padding(end = 130.dp)) {
                    Text(peerName, style = MaterialTheme.typography.titleLarge, color = foreground)
                    Text(status, style = MaterialTheme.typography.bodyLarge, color = foreground)
                }
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(0.6f))
                Avatar(info?.peerId ?: 0L, peerName, avatarVersion, 144.dp)
                Spacer(Modifier.height(20.dp))
                Text(peerName, style = MaterialTheme.typography.headlineMedium, color = foreground)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
            }

            if (st == CallManager.State.INCOMING) {
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallButton(R.drawable.ic_call_end, stringResource(R.string.btn_decline), CallRed, Color.White, 72.dp) {
                        calls.reject()
                    }
                    CallButton(R.drawable.ic_call, stringResource(R.string.btn_accept), CallGreen, Color.White, 72.dp) {
                        onAccept()
                    }
                }
            } else if (st != CallManager.State.IDLE) {
                if (inCall) {
                    val idle = if (videoOn) Color(0x66FFFFFF) else MaterialTheme.colorScheme.secondaryContainer
                    val idleContent = if (videoOn) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                    val on = MaterialTheme.colorScheme.primary
                    val onContent = MaterialTheme.colorScheme.onPrimary
                    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CallButton(
                            if (calls.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                            stringResource(R.string.call_mic),
                            if (calls.muted) on else idle, if (calls.muted) onContent else idleContent
                        ) { calls.toggleMute() }
                        CallButton(
                            R.drawable.ic_volume_up, stringResource(R.string.call_speaker),
                            if (calls.speaker) on else idle, if (calls.speaker) onContent else idleContent
                        ) { calls.toggleSpeaker() }
                        if (info?.video == true) {
                            CallButton(
                                if (calls.cameraOn) R.drawable.ic_videocam else R.drawable.ic_videocam_off,
                                stringResource(R.string.call_camera),
                                if (calls.cameraOn) idle else on, if (calls.cameraOn) idleContent else onContent
                            ) { calls.toggleCamera() }
                            CallButton(
                                R.drawable.ic_videocam, stringResource(R.string.call_flip), idle, idleContent
                            ) { calls.switchCamera() }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
                    CallButton(R.drawable.ic_call_end, stringResource(R.string.btn_hangup), CallRed, Color.White, 72.dp) {
                        calls.hangup()
                    }
                }
            }
        }
    }
}
