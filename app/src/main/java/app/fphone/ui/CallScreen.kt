package app.fphone.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
private fun VideoSurface(engine: RtcEngine, local: Boolean, mirror: Boolean = false, modifier: Modifier) {
    val holder = remember { arrayOfNulls<SurfaceViewRenderer>(1) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).also { v ->
                v.init(engine.eglContext, null)
                v.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                v.setEnableHardwareScaler(true)
                if (local) {
                    v.setMirror(mirror)
                    v.setZOrderMediaOverlay(true)
                    engine.setLocalSink(v)
                } else {
                    engine.setRemoteSink(v)
                }
                holder[0] = v
            }
        }
    )
    SideEffect { if (local) holder[0]?.setMirror(mirror) }
    DisposableEffect(engine) {
        onDispose {
            if (local) engine.setLocalSink(null) else engine.setRemoteSink(null)
            holder[0]?.release()
            holder[0] = null
        }
    }
}

/**
 * The call screen. Controls stay in one bottom row; video surfaces are independent of the UI layer.
 */
@Composable
fun CallScreen(onAccept: () -> Unit, onToggleCamera: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val calls = app.calls
    val st = calls.state
    val current = calls.call

    var last by remember { mutableStateOf<CallManager.CallInfo?>(null) }
    SideEffect { if (current != null) last = current }
    val info = current ?: last

    var sawCall by remember { mutableStateOf(false) }
    LaunchedEffect(st) {
        if (st == CallManager.State.IDLE) {
            if (sawCall) delay(when (calls.lastEndReason) {
                "busy", "already_in_call" -> 0L // CallManager keeps this result visible for two seconds.
                "server_unavailable" -> 2200L
                else -> 1500L
            })
            onFinish()
        } else sawCall = true
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
    val videoCall = info?.video == true
    val videoCapable = info?.videoCapable == true
    val remoteVideoVisible = videoCapable && engine != null && inCall && calls.remoteCameraOn
    val peerName = info?.peerName ?: calls.lastPeerName
    val avatarVersion = calls.contacts.firstOrNull { it.id == info?.peerId }?.avatar ?: 0L
    val foreground = if (remoteVideoVisible) Color.White else MaterialTheme.colorScheme.onBackground

    val status = when (st) {
        CallManager.State.INCOMING -> stringResource(if (videoCall) R.string.call_incoming_video else R.string.call_incoming_audio)
        CallManager.State.OUTGOING -> if (calls.lastEndReason != null) Texts.reason(ctx, calls.lastEndReason)
            else stringResource(if (calls.ringing) R.string.call_ringing else R.string.call_calling)
        CallManager.State.CONNECTING -> stringResource(R.string.call_connecting)
        CallManager.State.ACTIVE -> "%d:%02d".format(seconds / 60, seconds % 60)
        CallManager.State.IDLE -> Texts.reason(ctx, calls.lastEndReason)
    }

    var previewX by remember { mutableFloatStateOf(0f) }
    var previewY by remember { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize().background(if (remoteVideoVisible) Color.Black else MaterialTheme.colorScheme.background)) {
        if (remoteVideoVisible && engine != null) {
            VideoSurface(engine, local = false, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(info?.peerId ?: 0L, peerName, avatarVersion, 144.dp)
                Spacer(Modifier.height(20.dp))
                Text(peerName, style = MaterialTheme.typography.headlineMedium, color = foreground)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (remoteVideoVisible) {
            Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp)) {
                Text(peerName, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(status, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            }
        }

        if (videoCapable && engine?.hasLocalVideo == true && inCall && calls.cameraOn) {
            VideoSurface(
                engine, local = true, mirror = engine.isFrontCamera,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 112.dp)
                    .size(112.dp, 150.dp)
                    .offset { IntOffset(previewX.roundToInt(), previewY.roundToInt()) }
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            previewX += drag.x
                            previewY += drag.y
                        }
                    }
            )
        }

        if (st == CallManager.State.INCOMING) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                CallButton(R.drawable.ic_call_end, "", CallRed, Color.White, 72.dp) { calls.reject() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (videoCall) CallButton(
                        if (calls.cameraOn) R.drawable.ic_videocam else R.drawable.ic_videocam_off,
                        "", if (calls.cameraOn) MaterialTheme.colorScheme.secondaryContainer else Color(0xFF8B1E1E),
                        if (calls.cameraOn) MaterialTheme.colorScheme.onSecondaryContainer else Color.White, 44.dp
                    ) { calls.toggleIncomingCamera() }
                    Spacer(Modifier.height(8.dp))
                    CallButton(R.drawable.ic_call, "", CallGreen, Color.White, 72.dp) { onAccept() }
                }
            }
        } else if (st != CallManager.State.IDLE) {
            val idle = if (remoteVideoVisible) Color(0x99000000) else MaterialTheme.colorScheme.secondaryContainer
            val idleContent = Color.White
            val active = MaterialTheme.colorScheme.primary
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 6.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (videoCapable) CallButton(R.drawable.ic_flip_camera, "", idle, idleContent, 52.dp) { calls.switchCamera() }
                CallButton(
                    R.drawable.ic_volume_up, "",
                    if (calls.speaker) active else idle,
                    if (calls.speaker) MaterialTheme.colorScheme.onPrimary else idleContent,
                    52.dp
                ) { calls.toggleSpeaker() }
                if (videoCapable) CallButton(
                    if (calls.cameraOn) R.drawable.ic_videocam else R.drawable.ic_videocam_off, "",
                    if (calls.cameraOn) idle else Color(0xFF8B1E1E),
                    Color.White,
                    52.dp
                ) { onToggleCamera() }
                CallButton(
                    if (calls.muted) R.drawable.ic_mic_off else R.drawable.ic_mic, "",
                    if (calls.muted) Color(0xFF8B1E1E) else idle,
                    Color.White,
                    52.dp
                ) { calls.toggleMute() }
                CallButton(R.drawable.ic_call_end, "", CallRed, Color.White, 64.dp) { calls.hangup() }
            }
        }
    }
}
