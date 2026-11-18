package app.fphone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The brain of the app: keeps the connection to the server, the list of contacts and the state of
 * the current call, and drives the WebRTC engine. Screens only show its state and call its methods.
 * Everything runs on the main thread.
 *
 * The WebSocket connection to the server is kept only while it is needed: while a screen of the
 * app is visible, while a call is going on, or briefly after a push message. When the app is not
 * in use the server wakes the phone with a push message instead (saves battery).
 */
class CallManager(private val app: App) : Signaling.Listener, RtcEngine.Events {

    enum class State { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE }

    class CallInfo(
        var id: String,
        val outgoing: Boolean,
        val peerId: Long,
        var peerName: String,
        var video: Boolean,
        val videoCapable: Boolean = video
    )

    interface Listener {
        fun onChanged()
    }

    private enum class Pending { NONE, ACCEPT }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val prefs: Prefs
        get() = app.prefs

    // ---------- state shown by the screens ----------

    var state = State.IDLE
        private set
    var call: CallInfo? = null
        private set
    var ringing = false
        private set
    var connected = false // authenticated WebSocket connection to the server
        private set
    var contacts: List<Contact> = emptyList()
        private set
    var online: Set<Long> = emptySet()
        private set
    var engine: RtcEngine? = null
        private set
    var muted = false
        private set
    var cameraOn = true
        private set
    var remoteCameraOn = true
        private set
    var speaker = false
        private set
    var connectedSince = 0L // SystemClock.elapsedRealtime() when the media connection came up
        private set
    var lastEndReason: String? = null
        private set
    var lastPeerName = ""
        private set

    // ---------- internal state ----------

    private var sig: Signaling? = null
    private var visible = 0
    private var reconnectDelay = 1000L
    private var ice: JSONObject? = null
    private var pending = Pending.NONE
    private var pendingInvite: JSONObject? = null
    private var pendingRejectId: String? = null
    private var cameraWanted = true
    private var restarts = 0
    private var audioStarted = false
    private var audioFocus: AudioFocusRequest? = null
    private var ringback: ToneGenerator? = null
    private var frontCameraWanted = true
    private var pushSyncing = false
    private var refreshingFcm = false
    private val retryPushRegistration = Runnable { syncPendingPushToken() }

    private val reconnect = Runnable {
        if (sig == null && wantConnection()) connect()
    }
    private val expire = Runnable {
        if (call != null && state == State.INCOMING) endLocal("no_answer")
    }
    private val clearReject = Runnable {
        pendingRejectId = null
        maybeDisconnect()
    }

    // ---------- listeners and screen visibility ----------

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        for (l in listeners) l.onChanged()
    }

    fun onActivityStarted() {
        visible++
        ensureConnected()
    }

    fun onActivityStopped() {
        visible--
        if (visible < 0) visible = 0
        maybeDisconnect()
    }

    // ---------- login state ----------

    fun onLoggedIn() {
        reconnectDelay = 1000L
        ensureConnected()
        notifyChanged()
    }

    fun onLoggedOut() {
        if (call != null) endLocal("hangup")
        closeSignaling()
        contacts = emptyList()
        online = emptySet()
        notifyChanged()
    }

    private fun authFailed() {
        prefs.clearSession()
        if (call != null) endLocal("error")
        closeSignaling()
        contacts = emptyList()
        online = emptySet()
        notifyChanged()
    }

    fun updatePushToken(token: String) {
        if (token.isBlank()) return
        prefs.fcmToken = token
        if (token == prefs.registeredFcmToken && prefs.pendingFcmToken.isEmpty()) {
            prefs.lastFcmRefreshAt = System.currentTimeMillis()
            prefs.pushIssue = false
            notifyChanged()
            return
        }
        prefs.pendingFcmToken = token
        prefs.pushIssue = true
        MessageLog.add(app, "PUSH", "new token stored locally; server registration pending (${token.length} chars)")
        syncPendingPushToken()
        notifyChanged()
    }

    private fun syncPendingPushToken() {
        val token = prefs.pendingFcmToken
        if (!prefs.loggedIn || token.isEmpty() || pushSyncing) return
        pushSyncing = true
        Bg.run({ app.net.putPushToken(token) }) { result ->
            pushSyncing = false
            result.onSuccess {
                main.removeCallbacks(retryPushRegistration)
                prefs.registeredFcmToken = token
                if (prefs.pendingFcmToken == token) prefs.pendingFcmToken = ""
                prefs.pushIssue = false
                prefs.lastFcmRefreshAt = System.currentTimeMillis()
                MessageLog.add(app, "PUSH", "server confirmed FCM token registration")
            }
            result.onFailure { e ->
                prefs.pushIssue = true
                MessageLog.add(app, "PUSH", "token registration failed; retry scheduled: $e")
                main.removeCallbacks(retryPushRegistration)
                main.postDelayed(retryPushRegistration, 15_000L)
            }
            notifyChanged()
        }
    }

    private fun maybeRefreshFcmToken() {
        if (!connected || refreshingFcm || System.currentTimeMillis() - prefs.lastFcmRefreshAt < 30L * 24 * 60 * 60 * 1000) return
        refreshingFcm = true
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                refreshingFcm = false
                val token = if (task.isSuccessful) task.result.orEmpty() else ""
                if (token.isNotBlank()) updatePushToken(token)
                else {
                    prefs.pushIssue = true
                    MessageLog.add(app, "PUSH", "monthly token refresh failed; keeping previous token: ${task.exception}")
                    notifyChanged()
                }
            }
        } catch (e: Exception) {
            refreshingFcm = false
            prefs.pushIssue = true
            MessageLog.add(app, "PUSH", "monthly token refresh unavailable; keeping previous token: $e")
            notifyChanged()
        }
    }

    private fun startRingback() {
        stopRingback()
        try {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
            MessageLog.add(app, "CALL", "local ringback tone started")
        } catch (e: Exception) { MessageLog.add(app, "CALL", "ringback tone unavailable: $e") }
    }

    private fun stopRingback() {
        try { ringback?.stopTone(); ringback?.release() } catch (_: Exception) { }
        ringback = null
    }

    // ---------- connection to the server ----------

    private fun wantConnection(): Boolean =
        prefs.loggedIn && (visible > 0 || state != State.IDLE || pendingRejectId != null)

    private fun ensureConnected() {
        if (sig == null && wantConnection()) connect()
    }

    private fun maybeDisconnect() {
        if (!wantConnection()) closeSignaling()
    }

    private fun connect() {
        main.removeCallbacks(reconnect)
        val s = Signaling(app.net, prefs, this)
        sig = s
        connected = false
        val c = call
        // After a push message the server wants to know which call woke the app.
        val wake = if (c != null && !c.outgoing) c.id else pendingRejectId
        s.connect(wake)
    }

    private fun closeSignaling() {
        main.removeCallbacks(reconnect)
        sig?.close()
        sig = null
        connected = false
    }

    private fun send(o: JSONObject): Boolean = sig?.send(o) ?: false

    override fun onSigOpen(s: Signaling) {
        // The auth message was already sent by Signaling; the answer is "auth.ok".
    }

    override fun onSigClosed(s: Signaling, code: Int, reason: String) {
        if (s !== sig) return
        sig = null
        connected = false
        MessageLog.add(app, "server connection closed: $code $reason")
        if (code == 4401) { // the token is not valid any more
            authFailed()
            return
        }
        notifyChanged()
        if (wantConnection()) {
            main.postDelayed(reconnect, reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, 15_000L)
        }
    }

    override fun onSigMessage(s: Signaling, m: JSONObject) {
        if (s !== sig) return
        when (m.optString("t")) {
            "auth.ok" -> onAuthOk(m)
            "presence" -> {
                val id = m.optLong("user")
                online = if (m.optBoolean("online")) online + id else online - id
                notifyChanged()
            }
            "call.created" -> onCreated(m)
            "call.incoming" -> onIncoming(m)
            "call.ringing" -> {
                if (m.optString("call") == call?.id) {
                    ringing = true
                    notifyChanged()
                }
            }
            "call.accepted" -> onAccepted(m)
            "call.media" -> {
                if (m.optString("call") == call?.id) {
                    call?.video = m.optString("media") == "video"
                    remoteCameraOn = call?.video == true
                    notifyChanged()
                }
            }
            "device.log.request" -> {
                val request = m.optString("request")
                if (visible > 0) {
                    MessageLog.add(app, "DIAG", "remote diagnostic log requested")
                    val journal = MessageLog.text(app).takeLast(32 * 1024)
                    send(JSONObject().put("t", "device.log.response").put("request", request).put("text", journal))
                } else {
                    send(JSONObject().put("t", "device.log.response").put("request", request).put("error", "phone is not in foreground"))
                }
            }
            "call.signal" -> onSignal(m)
            "call.ended" -> onEnded(m)
            "ice" -> onIce(m)
            "error" -> onServerError(m)
        }
    }

    private fun onAuthOk(m: JSONObject) {
        connected = true
        reconnectDelay = 1000L
        syncPendingPushToken()
        maybeRefreshFcmToken()
        val ids = HashSet<Long>()
        val arr = m.optJSONArray("online")
        if (arr != null) for (i in 0 until arr.length()) ids.add(arr.getLong(i))
        online = ids
        loadContacts()

        // Calls that the server still knows for this device (for example after a lost connection).
        val calls = m.optJSONArray("calls")
        if (calls != null && calls.length() > 0) {
            val c = calls.getJSONObject(0)
            val id = c.optString("id")
            val local = call
            if (local != null && (local.id.isEmpty() || local.id == id)) {
                local.id = id
                val newIce = c.optJSONObject("ice")
                if (newIce != null) {
                    ice = newIce
                    engine?.updateIce(newIce)
                }
                // The caller moves the media to a new network path; the callee waits for its offer.
                if (local.outgoing && engine != null && (state == State.CONNECTING || state == State.ACTIVE)) {
                    restartIce()
                }
            } else if (local == null) {
                // A call of an earlier run of the app: it cannot be continued.
                send(JSONObject().put("t", "call.hangup").put("call", id))
            }
        }

        val invite = pendingInvite
        if (invite != null) {
            pendingInvite = null
            send(invite)
        }
        val currentCall = call
        if (currentCall != null && currentCall.id.isNotEmpty() && currentCall.videoCapable &&
            (state == State.CONNECTING || state == State.ACTIVE)) {
            send(JSONObject().put("t", "call.signal").put("call", currentCall.id).put("kind", "camera").put("enabled", cameraOn))
        }
        notifyChanged()
    }

    fun loadContacts() {
        if (!prefs.loggedIn) return
        Bg.run({ app.net.contacts() }) { r ->
            r.onSuccess { list ->
                contacts = list
                if (!connected) online = list.filter { it.online }.map { it.id }.toSet()
                notifyChanged()
            }
            r.onFailure { e ->
                if ((e as? ApiException)?.code == "unauthorized") authFailed()
            }
        }
    }

    // ---------- starting and answering calls (from the screens) ----------

    fun startCall(peerId: Long, peerName: String, video: Boolean) {
        if (state != State.IDLE || !prefs.loggedIn) return
        val c = CallInfo("", true, peerId, peerName, video)
        call = c
        state = State.OUTGOING
        ringing = false
        lastEndReason = null
        cameraWanted = video
        cameraOn = video
        frontCameraWanted = true
        remoteCameraOn = video
        // Start the communication route before local ringback so it follows the selected speaker.
        startAudio(video)
        startRingback()
        val invite = JSONObject()
            .put("t", "call.invite")
            .put("to", peerId)
            .put("media", if (video) "video" else "audio")
        startService(c)
        if (connected) {
            send(invite)
        } else {
            pendingInvite = invite
            ensureConnected()
        }
        notifyChanged()
    }

    fun accept(withCamera: Boolean) {
        val c = call ?: return
        if (state != State.INCOMING || c.outgoing) return
        cameraWanted = withCamera
        cameraOn = withCamera
        c.video = c.video && withCamera
        Notifications.cancelIncoming(app)
        main.removeCallbacks(expire)
        state = State.CONNECTING
        pending = Pending.ACCEPT
        startService(c)
        if (connected && ice != null) doAccept() else ensureConnected()
        notifyChanged()
    }

    private fun doAccept() {
        val c = call ?: return
        pending = Pending.NONE
        startEngine()
        send(JSONObject().put("t", "call.accept").put("call", c.id))
        if (c.videoCapable && !cameraWanted) send(JSONObject().put("t", "call.signal").put("call", c.id).put("kind", "camera").put("enabled", false))
    }

    fun reject() {
        val c = call ?: return
        if (state != State.INCOMING || c.outgoing) return
        Notifications.cancelIncoming(app)
        if (connected) {
            send(JSONObject().put("t", "call.reject").put("call", c.id))
        } else {
            // Not connected yet (the call came from a push message): connect and decline when the
            // server tells us about the call.
            pendingRejectId = c.id
            main.removeCallbacks(clearReject)
            main.postDelayed(clearReject, 20_000L)
            ensureConnected()
        }
        endLocal("declined")
    }

    fun hangup() {
        val c = call ?: return
        if (state == State.INCOMING && !c.outgoing) {
            reject()
            return
        }
        pendingInvite = null
        if (c.id.isNotEmpty()) send(JSONObject().put("t", "call.hangup").put("call", c.id))
        endLocal("hangup")
    }

    fun toggleMute() {
        muted = !muted
        engine?.setMuted(muted)
        notifyChanged()
    }

    fun toggleCamera() {
        cameraOn = !cameraOn
        val c = call
        if (state == State.OUTGOING) {
            cameraWanted = cameraOn
            if (c != null) c.video = cameraOn
        }
        val e = engine
        if (cameraOn && e != null && !e.hasLocalVideo) {
            ensureLocalVideoTrack()
        } else {
            e?.setVideoEnabled(cameraOn)
        }
        if (c != null && c.id.isNotEmpty()) send(JSONObject().put("t", "call.signal").put("call", c.id).put("kind", "camera").put("enabled", cameraOn))
        notifyChanged()
    }

    private fun ensureLocalVideoTrack() {
        val e = engine ?: return
        val c = call ?: return
        if (!cameraOn || !c.videoCapable || e.hasLocalVideo || state != State.ACTIVE) return
        if (e.enableVideo(frontCameraWanted)) {
            e.createOffer(false) { sdp -> sendSignal("offer", sdp, false) }
        } else {
            cameraOn = false
            MessageLog.add(app, "CALL", "camera could not be enabled")
            notifyChanged()
        }
    }

    fun toggleIncomingCamera() {
        if (state != State.INCOMING) return
        cameraOn = !cameraOn
        notifyChanged()
    }

    fun switchCamera() {
        val e = engine
        if (e == null || !e.hasLocalVideo) frontCameraWanted = !frontCameraWanted else e.switchCamera()
        notifyChanged()
    }

    fun toggleSpeaker() {
        speaker = !speaker
        applySpeaker()
        notifyChanged()
    }

    // ---------- push messages (from FcmService) ----------

    fun onPushIncoming(callId: String, fromId: Long, fromName: String, video: Boolean, expSeconds: Long) {
        if (!prefs.loggedIn || state != State.IDLE) return
        val remaining = if (expSeconds > 0) expSeconds * 1000L - System.currentTimeMillis() else 45_000L
        if (remaining <= 0) {
            MessageLog.add(app, "PUSH", "push for call $callId arrived too late, ignored")
            return
        }
        val name = when {
            fromName.isNotEmpty() -> fromName
            else -> contacts.firstOrNull { it.id == fromId }?.name ?: app.getString(R.string.unknown_caller)
        }
        val c = CallInfo(callId, false, fromId, name, video)
        remoteCameraOn = video
        call = c
        state = State.INCOMING
        cameraOn = video
        cameraWanted = video
        lastEndReason = null
        MessageLog.add(app, "PUSH", "incoming call received id=$callId from=$fromId")
        Notifications.showIncoming(app, name, video, remaining)
        main.removeCallbacks(expire)
        main.postDelayed(expire, remaining)
        ensureConnected() // the server then sends the call details (with TURN credentials)
        notifyChanged()
    }

    fun onPushCancelled(callId: String) {
        val c = call ?: return
        if (c.id == callId && state == State.INCOMING) endLocal("cancelled")
    }

    // ---------- messages about calls from the server ----------

    private fun onCreated(m: JSONObject) {
        val id = m.getJSONObject("call").getString("id")
        val c = call
        if (c == null || !c.outgoing) {
            // We gave up before the server answered.
            send(JSONObject().put("t", "call.hangup").put("call", id))
            return
        }
        c.id = id
        c.video = cameraOn
        ice = m.optJSONObject("ice")
        if (!cameraOn) send(JSONObject().put("t", "call.signal").put("call", id).put("kind", "camera").put("enabled", false))
        notifyChanged()
    }

    private fun onIncoming(m: JSONObject) {
        val info = m.getJSONObject("call")
        val id = info.getString("id")
        val peer = info.getJSONObject("peer")

        // A decline that was waiting for the connection.
        if (pendingRejectId == id) {
            pendingRejectId = null
            main.removeCallbacks(clearReject)
            send(JSONObject().put("t", "call.reject").put("call", id))
            maybeDisconnect()
            return
        }

        var c = call
        if (c != null && c.id != id) return // another call is going on
        if (c == null) {
            c = CallInfo(id, false, peer.getLong("id"), peer.optString("name"), info.optString("media") == "video")
            remoteCameraOn = c.video
            cameraOn = c.video
            cameraWanted = c.video
            call = c
            state = State.INCOMING
            lastEndReason = null
            MessageLog.add(app, "CALL", "incoming call screen started id=$id")
            Notifications.showIncoming(app, c.peerName, c.video, 50_000L)
            if (visible > 0) app.startActivity(CallActivity.intent(app))
        }
        val newIce = m.optJSONObject("ice")
        if (newIce != null) ice = newIce

        if (pending == Pending.ACCEPT) doAccept() else send(JSONObject().put("t", "call.ringing").put("call", id))
        notifyChanged()
    }

    private fun onAccepted(m: JSONObject) {
        val c = call ?: return
        if (m.optString("call") != c.id) return
        if (c.outgoing && engine == null) {
            stopRingback()
            c.video = cameraWanted && cameraOn
            state = State.CONNECTING
            startEngine()
            engine?.createOffer(false) { sdp -> sendSignal("offer", sdp, false) }
            notifyChanged()
        }
    }

    private fun onSignal(m: JSONObject) {
        val c = call ?: return
        if (m.optString("call") != c.id) return
        if (m.optString("kind") == "camera") {
            remoteCameraOn = m.optBoolean("enabled", true)
            notifyChanged()
            return
        }
        val e = engine ?: return
        when (m.optString("kind")) {
            "offer" -> e.onRemoteOffer(m.optString("sdp")) { sdp -> sendSignal("answer", sdp, false) }
            "answer" -> e.onRemoteAnswer(m.optString("sdp"))
            "candidate" -> {
                val cand = m.optJSONObject("candidate") ?: return
                val text = cand.optString("candidate")
                if (text.isEmpty()) return
                val mid = if (cand.isNull("sdpMid")) null else cand.optString("sdpMid")
                e.addRemoteCandidate(mid, cand.optInt("sdpMLineIndex", 0), text)
            }
        }
    }

    private fun onEnded(m: JSONObject) {
        val c = call ?: return
        if (m.optString("call") != c.id) return
        endLocal(m.optString("reason", "hangup"))
    }

    private fun onIce(m: JSONObject) {
        val c = call ?: return
        if (m.optString("call") != c.id) return
        val newIce = m.optJSONObject("ice") ?: return
        ice = newIce
        engine?.updateIce(newIce)
    }

    private fun onServerError(m: JSONObject) {
        val code = m.optString("code")
        val ref = m.optString("ref")
        MessageLog.add(app, "server error: $code ($ref)")
        if (ref == "call.invite" && state == State.OUTGOING) endLocal(code)
    }

    private fun sendSignal(kind: String, sdp: String, restart: Boolean) {
        val c = call ?: return
        val o = JSONObject()
            .put("t", "call.signal")
            .put("call", c.id)
            .put("kind", kind)
            .put("sdp", sdp)
        if (restart) o.put("ice_restart", true)
        send(o)
    }

    // ---------- media engine ----------

    private fun startEngine() {
        val c = call ?: return
        if (engine != null) return
        startAudio(c.video)
        val e = RtcEngine(app, this)
        engine = e
        try {
            val config = ice ?: JSONObject().put("servers", JSONArray()).put("policy", "all")
            e.start(config, c.videoCapable && cameraWanted && cameraOn, frontCameraWanted)
            e.setVideoEnabled(c.videoCapable && cameraWanted && cameraOn)
        } catch (t: Throwable) {
            MessageLog.add(app, "cannot start WebRTC: $t")
            if (c.id.isNotEmpty()) send(JSONObject().put("t", "call.hangup").put("call", c.id))
            endLocal("error")
        }
    }

    override fun onLocalCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val c = call ?: return
        val cand = JSONObject().put("candidate", candidate).put("sdpMLineIndex", sdpMLineIndex)
        if (sdpMid != null) cand.put("sdpMid", sdpMid)
        send(
            JSONObject()
                .put("t", "call.signal")
                .put("call", c.id)
                .put("kind", "candidate")
                .put("candidate", cand)
        )
    }

    override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
        MessageLog.add(app, "media connection: $state")
        val c = call ?: return
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                restarts = 0
                if (this.state == State.CONNECTING) {
                    this.state = State.ACTIVE
                    connectedSince = SystemClock.elapsedRealtime()
                    send(JSONObject().put("t", "call.connected").put("call", c.id))
                    engine?.limitBitrate()
                    engine?.logSelectedPair { MessageLog.add(app, it) }
                    ensureLocalVideoTrack()
                    notifyChanged()
                }
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                if (c.outgoing) {
                    if (restarts < 3) {
                        restartIce()
                    } else {
                        send(JSONObject().put("t", "call.hangup").put("call", c.id))
                        endLocal("timeout")
                    }
                }
                // The callee waits for new offers of the caller (or for the server to end the call).
            }
            else -> {}
        }
    }

    override fun onError(message: String) {
        MessageLog.add(app, "WebRTC: $message")
    }

    private fun restartIce() {
        val c = call ?: return
        val e = engine ?: return
        restarts++
        MessageLog.add(app, "ICE restart $restarts")
        send(JSONObject().put("t", "ice.get").put("call", c.id)) // fresh TURN credentials come as "ice"
        e.createOffer(true) { sdp -> sendSignal("offer", sdp, true) }
    }

    // ---------- audio routing ----------

    private fun startAudio(video: Boolean) {
        if (audioStarted) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
        am.requestAudioFocus(req)
        audioFocus = req
        audioStarted = true
        speaker = true // use loudspeaker by default for both audio and video calls
        applySpeaker()
    }

    private fun applySpeaker() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speaker
    }

    private fun stopAudio() {
        if (!audioStarted) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
        audioFocus?.let { am.abandonAudioFocusRequest(it) }
        audioFocus = null
        am.mode = AudioManager.MODE_NORMAL
        audioStarted = false
    }

    // ---------- foreground service ----------

    private fun startService(c: CallInfo) {
        try {
            CallService.start(app, c.peerName, c.video)
        } catch (e: Exception) {
            MessageLog.add(app, "cannot start the call service: $e")
        }
    }

    // ---------- end of a call ----------

    /** Writes the call into the history ("Recents") and notifies about a missed call. */
    private fun recordHistory(c: CallInfo, reason: String) {
        val talk = if (connectedSince > 0L) ((SystemClock.elapsedRealtime() - connectedSince) / 1000L).toInt() else 0
        val result = when {
            connectedSince > 0L -> "answered"
            c.outgoing -> when (reason) {
                "no_answer", "busy", "offline" -> reason
                "rejected" -> "rejected"
                "hangup", "cancelled" -> "cancelled"
                else -> "failed"
            }
            else -> when (reason) {
                "cancelled", "no_answer", "gone" -> "missed"
                "declined", "hangup" -> "declined"
                "answered_elsewhere" -> "elsewhere"
                else -> "failed"
            }
        }
        app.history.add(
            HistoryEntry(System.currentTimeMillis(), c.peerId, c.peerName, c.outgoing, c.video, result, talk)
        )
        if (result == "missed") Notifications.showMissed(app, c.peerId, c.peerName)
    }

    private fun endLocal(reason: String) {
        val c = call
        if (c != null) recordHistory(c, reason)
        MessageLog.add(app, "call ended: $reason")
        lastEndReason = reason
        lastPeerName = c?.peerName ?: ""
        main.removeCallbacks(expire)
        stopRingback()
        Notifications.cancelIncoming(app)
        engine?.close()
        engine = null
        stopAudio()
        CallService.stop(app)
        call = null
        ice = null
        state = State.IDLE
        ringing = false
        connectedSince = 0L
        pending = Pending.NONE
        pendingInvite = null
        restarts = 0
        muted = false
        cameraOn = true
        cameraWanted = true
        frontCameraWanted = true
        remoteCameraOn = true
        speaker = false
        notifyChanged()
        maybeDisconnect()
    }
}
