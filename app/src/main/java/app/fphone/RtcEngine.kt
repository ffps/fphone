package app.fphone

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One WebRTC peer connection with local audio (and optional camera video).
 * Create it for a call, call start(), and close() it when the call is over.
 * Everything is called from the main thread, and events are delivered on the main thread.
 */
class RtcEngine(private val ctx: Context, private val events: Events) {

    interface Events {
        fun onLocalCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onConnectionState(state: PeerConnection.PeerConnectionState)
        fun onError(message: String)
    }

    companion object {
        // Weak mobile networks: modest video, so that audio stays clear.
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 360
        private const val VIDEO_FPS = 20
        private const val MAX_VIDEO_BPS = 450_000

        @Volatile
        private var initialized = false

        private fun ensureInit(ctx: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val egl: EglBase = EglBase.create()

    /** Needed to initialize the video views (SurfaceViewRenderer). */
    val eglContext: EglBase.Context
        get() = egl.eglBaseContext

    private var factory: PeerConnectionFactory? = null
    private var adm: AudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    var isFrontCamera: Boolean = true
        private set
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteVideo: VideoTrack? = null
    private var localSink: VideoSink? = null
    private var remoteSink: VideoSink? = null

    private val pendingCandidates = ArrayList<IceCandidate>()
    private var remoteDescriptionSet = false
    private var closed = false

    val hasLocalVideo: Boolean
        get() = videoTrack != null

    // ---------- setup ----------

    fun start(ice: JSONObject, withVideo: Boolean, frontCamera: Boolean = true) {
        ensureInit(ctx)
        val voiceAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val audioModule = JavaAudioDeviceModule.builder(ctx.applicationContext)
            .setAudioAttributes(voiceAttributes)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                    main.post { if (!closed) events.onError("microphone init failed: $errorMessage") }
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    errorMessage: String
                ) {
                    main.post {
                        if (!closed) events.onError("microphone start failed ($errorCode): $errorMessage")
                    }
                }

                override fun onWebRtcAudioRecordError(errorMessage: String) {
                    main.post { if (!closed) events.onError("microphone error: $errorMessage") }
                }
            })
            .createAudioDeviceModule()
        adm = audioModule
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f

        val p = f.createPeerConnection(buildConfig(ice), observer)
        if (p == null) {
            events.onError("cannot create the peer connection")
            return
        }
        pc = p

        val src = f.createAudioSource(MediaConstraints())
        audioSource = src
        val track = f.createAudioTrack("audio0", src)
        audioTrack = track
        p.addTrack(track, listOf("stream0"))

        if (withVideo) startCamera(f, p, frontCamera)
    }

    private fun startCamera(f: PeerConnectionFactory, p: PeerConnection, frontCamera: Boolean) {
        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(ctx)) Camera2Enumerator(ctx) else Camera1Enumerator(true)
        val names = enumerator.deviceNames
        val preferred = names.firstOrNull { enumerator.isFrontFacing(it) == frontCamera }
        val name = preferred ?: names.firstOrNull()
        if (name == null) {
            events.onError("no camera found")
            return
        }
        isFrontCamera = enumerator.isFrontFacing(name)
        val cap = enumerator.createCapturer(name, null)
        if (cap == null) {
            events.onError("cannot open the camera")
            return
        }
        val helper = SurfaceTextureHelper.create("capture-thread", egl.eglBaseContext)
        val source = f.createVideoSource(cap.isScreencast)
        cap.initialize(helper, ctx, source.capturerObserver)
        cap.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        val track = f.createVideoTrack("video0", source)
        p.addTrack(track, listOf("stream0"))
        capturer = cap
        surfaceHelper = helper
        videoSource = source
        videoTrack = track
        localSink?.let { track.addSink(it) }
    }

    private fun buildConfig(ice: JSONObject): PeerConnection.RTCConfiguration {
        val servers = ArrayList<PeerConnection.IceServer>()
        val arr = ice.optJSONArray("servers")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val urlsJson = o.getJSONArray("urls")
                val urls = ArrayList<String>()
                for (j in 0 until urlsJson.length()) urls.add(urlsJson.getString(j))
                val b = PeerConnection.IceServer.builder(urls)
                if (o.has("username")) b.setUsername(o.getString("username"))
                if (o.has("credential")) b.setPassword(o.getString("credential"))
                servers.add(b.createIceServer())
            }
        }
        val cfg = PeerConnection.RTCConfiguration(servers)
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        cfg.iceTransportsType =
            if (ice.optString("policy") == "relay") PeerConnection.IceTransportsType.RELAY
            else PeerConnection.IceTransportsType.ALL
        return cfg
    }

    /** New TURN credentials (the server hands them out per call and they expire). */
    fun updateIce(ice: JSONObject) {
        pc?.setConfiguration(buildConfig(ice))
    }

    // ---------- negotiation ----------

    private open inner class SdpAdapter(private val what: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            main.post { events.onError("$what failed: $error") }
        }
        override fun onSetFailure(error: String?) {
            main.post { events.onError("$what failed: $error") }
        }
    }

    /** Creates an offer (also for an ICE restart) and passes its SDP text on the main thread. */
    fun createOffer(iceRestart: Boolean, onSdp: (String) -> Unit) {
        val p = pc ?: return
        val constraints = MediaConstraints()
        if (iceRestart) constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        p.createOffer(object : SdpAdapter("create offer") {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                p.setLocalDescription(object : SdpAdapter("set local offer") {
                    override fun onSetSuccess() {
                        main.post { if (!closed) onSdp(sdp.description) }
                    }
                }, sdp)
            }
        }, constraints)
    }

    /** Applies a remote offer and passes the SDP text of our answer on the main thread. */
    fun onRemoteOffer(sdp: String, onAnswer: (String) -> Unit) {
        val p = pc ?: return
        p.setRemoteDescription(object : SdpAdapter("set remote offer") {
            override fun onSetSuccess() {
                main.post { remoteReady() }
                p.createAnswer(object : SdpAdapter("create answer") {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return
                        p.setLocalDescription(object : SdpAdapter("set local answer") {
                            override fun onSetSuccess() {
                                main.post { if (!closed) onAnswer(answer.description) }
                            }
                        }, answer)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun onRemoteAnswer(sdp: String) {
        val p = pc ?: return
        p.setRemoteDescription(object : SdpAdapter("set remote answer") {
            override fun onSetSuccess() {
                main.post { remoteReady() }
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    // Candidates that arrive before the remote description is set must wait.
    private fun remoteReady() {
        remoteDescriptionSet = true
        val p = pc ?: return
        for (c in pendingCandidates) p.addIceCandidate(c)
        pendingCandidates.clear()
    }

    fun addRemoteCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val c = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        val p = pc ?: return
        if (remoteDescriptionSet) p.addIceCandidate(c) else pendingCandidates.add(c)
    }

    // ---------- controls ----------

    fun setMuted(muted: Boolean) {
        // Mute the capture device without disabling the track or restarting AudioRecord.
        adm?.setMicrophoneMute(muted)
    }

    fun setVideoEnabled(on: Boolean) {
        videoTrack?.setEnabled(on)
    }

    /** Adds a video sender after an audio-only answer, then the caller renegotiates the session. */
    fun enableVideo(frontCamera: Boolean): Boolean {
        val existing = videoTrack
        if (existing != null) { existing.setEnabled(true); return true }
        val f = factory ?: return false
        val p = pc ?: return false
        startCamera(f, p, frontCamera)
        val track = videoTrack ?: return false
        track.setEnabled(true)
        return true
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
        isFrontCamera = !isFrontCamera
    }

    /** Limits the video bitrate, so that audio keeps enough room on a weak connection. */
    fun limitBitrate() {
        val p = pc ?: return
        for (s in p.senders) {
            if (s.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                val params = s.parameters
                for (e in params.encodings) {
                    e.maxBitrateBps = MAX_VIDEO_BPS
                    e.maxFramerate = VIDEO_FPS
                }
                s.setParameters(params)
            }
        }
    }

    /** Writes the type of the selected network path (for example relay/udp) with the given logger. */
    fun logSelectedPair(log: (String) -> Unit) {
        val p = pc ?: return
        p.getStats { report ->
            try {
                val stats = report.statsMap
                var pairId: String? = null
                for (s in stats.values) {
                    if (s.type == "transport") {
                        val id = s.members["selectedCandidatePairId"]
                        if (id is String) pairId = id
                    }
                }
                val pair = if (pairId != null) stats[pairId] else null
                if (pair != null) {
                    val localId = pair.members["localCandidateId"]
                    val remoteId = pair.members["remoteCandidateId"]
                    val l = if (localId is String) stats[localId] else null
                    val r = if (remoteId is String) stats[remoteId] else null
                    log(
                        "ICE path: local ${l?.members?.get("candidateType")}/${l?.members?.get("protocol")}" +
                            " (relay over ${l?.members?.get("relayProtocol")}), " +
                            "remote ${r?.members?.get("candidateType")}/${r?.members?.get("protocol")}"
                    )
                }
            } catch (e: Exception) {
                // statistics are only a diagnostic aid
            }
        }
    }

    // ---------- video views ----------

    fun attachRenderers(local: VideoSink?, remote: VideoSink?) {
        detachRenderers()
        localSink = local
        remoteSink = remote
        if (local != null) videoTrack?.addSink(local)
        if (remote != null) remoteVideo?.addSink(remote)
    }

    fun setLocalSink(sink: VideoSink?) {
        localSink?.let { videoTrack?.removeSink(it) }
        localSink = sink
        if (sink != null) videoTrack?.addSink(sink)
    }

    fun setRemoteSink(sink: VideoSink?) {
        remoteSink?.let { remoteVideo?.removeSink(it) }
        remoteSink = sink
        if (sink != null) remoteVideo?.addSink(sink)
    }

    fun detachRenderers() {
        localSink?.let { videoTrack?.removeSink(it) }
        remoteSink?.let { remoteVideo?.removeSink(it) }
        localSink = null
        remoteSink = null
    }

    private fun onRemoteTrack(track: MediaStreamTrack?) {
        if (track is VideoTrack) {
            main.post {
                if (closed) return@post
                remoteVideo = track
                remoteSink?.let { track.addSink(it) }
            }
        }
    }

    // ---------- events from the peer connection ----------

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            main.post {
                if (!closed) events.onLocalCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            if (state == null) return
            main.post { if (!closed) events.onConnectionState(state) }
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            onRemoteTrack(receiver?.track())
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            onRemoteTrack(transceiver?.receiver?.track())
        }
    }

    // ---------- shutdown ----------

    fun close() {
        if (closed) return
        closed = true
        detachRenderers()
        val p = pc
        val a = audioSource
        val cap = capturer
        val vs = videoSource
        val helper = surfaceHelper
        val f = factory
        val m = adm
        pc = null
        // The tracks are owned by the peer connection and become invalid with it.
        videoTrack = null
        remoteVideo = null
        audioTrack = null
        // Releasing native objects can take a moment: do it off the main thread.
        Thread {
            try {
                p?.dispose()
                a?.dispose()
                try {
                    cap?.stopCapture()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                cap?.dispose()
                vs?.dispose()
                helper?.dispose()
                f?.dispose()
                m?.release()
                egl.release()
            } catch (e: Exception) {
                // nothing to do: the call is already over
            }
        }.start()
    }
}
