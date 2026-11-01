package com.example.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/**
 * Звонок поверх WebRTC — mesh, каждый участник соединяется с каждым напрямую (своё PeerConnection на пару),
 * сервер лишь пересылает сигналинг адресно (peerId — id собеседника на сервере). Личный звонок — тот же
 * механизм с одним peerId, ничем не отличается. Микрофон и камера захватываются один раз и общие для всех
 * соединений — своя аудио/видеодорожка на каждого участника не нужна, WebRTC сам размножает исходящий поток.
 * Все публичные методы вызываются из главного потока; колбэки WebRTC переносятся туда через Handler.
 * Сигналинг (SDP/ICE) совместим с веб-клиентом: {"sdp":{"type","sdp"}} и
 * {"candidate":{"candidate","sdpMid","sdpMLineIndex"}}.
 */
class CallManager(
    private val app: Context,
    private val onSignal: (peerId: Long, JSONObject) -> Unit,
    private val onPeerConnected: (peerId: Long) -> Unit,
    private val onPeerFailed: (peerId: Long) -> Unit,
    private val onHoldChanged: (Boolean) -> Unit = {},
) {
    val eglBase: EglBase = EglBase.create()
    val localVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideos = MutableStateFlow<Map<Long, VideoTrack>>(emptyMap())

    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var wantVideo = false
    private var focusRequest: AudioFocusRequest? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    private class PeerState(val pc: PeerConnection) {
        val pending = mutableListOf<IceCandidate>()
        var remoteSet = false
    }
    private val peers = mutableMapOf<Long, PeerState>()

    companion object {
        private var initialized = false
    }

    /** Готовит общее для всего звонка: фабрику соединений, микрофон и (для видеозвонка) камеру.
        PeerConnection с конкретными участниками создаются потом отдельно, через addPeer(). Нужны разрешения. */
    fun prepareLocal(video: Boolean, ice: List<IceCfg>) {
        wantVideo = video
        if (!initialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app.applicationContext)
                    .createInitializationOptions()
            )
            initialized = true
        }
        val f = factory ?: PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }

        iceServers = ice.map { cfg ->
            val b = PeerConnection.IceServer.builder(cfg.urls)
            cfg.username?.let { b.setUsername(it) }
            cfg.credential?.let { b.setPassword(it) }
            b.createIceServer()
        }

        val aSrc = f.createAudioSource(MediaConstraints())
        val aTrack = f.createAudioTrack("audio0", aSrc)
        audioSource = aSrc
        audioTrack = aTrack

        if (video) {
            val enumerator = Camera2Enumerator(app)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (name != null) {
                val cap = enumerator.createCapturer(name, null)
                if (cap != null) {
                    val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
                    val vSrc = f.createVideoSource(false)
                    cap.initialize(helper, app, vSrc.capturerObserver)
                    cap.startCapture(640, 480, 24)
                    val vTrack = f.createVideoTrack("video0", vSrc)
                    capturer = cap
                    surfaceHelper = helper
                    videoSource = vSrc
                    videoTrack = vTrack
                    localVideo.value = vTrack
                }
            }
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus()
        applyAudioRoute(preferSpeaker = video)
    }

    /** Новое соединение с конкретным участником — сразу с локальными аудио/видео дорожками (общими на всех). */
    fun addPeer(peerId: Long) {
        if (peers.containsKey(peerId)) return
        val f = factory ?: return
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val p = f.createPeerConnection(config, observerFor(peerId)) ?: return
        audioTrack?.let { p.addTrack(it, listOf("stream0")) }
        videoTrack?.let { p.addTrack(it, listOf("stream0")) }
        peers[peerId] = PeerState(p)
    }

    /** Новичок в звонке (то есть я) сам предлагает оффер каждому уже подключённому. */
    fun createOffer(peerId: Long) {
        val p = peers[peerId]?.pc ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", wantVideo.toString()))
        }
        p.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) {
                d ?: return
                p.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() { main.post { emitSdp(peerId, d) } }
                    override fun onSetFailure(e: String?) { main.post { onPeerFailed(peerId) } }
                }, d)
            }
            override fun onCreateFailure(e: String?) { main.post { onPeerFailed(peerId) } }
        }, constraints)
    }

    private fun createAnswer(peerId: Long) {
        val p = peers[peerId]?.pc ?: return
        p.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) {
                d ?: return
                p.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() { main.post { emitSdp(peerId, d) } }
                    override fun onSetFailure(e: String?) { main.post { onPeerFailed(peerId) } }
                }, d)
            }
            override fun onCreateFailure(e: String?) { main.post { onPeerFailed(peerId) } }
        }, MediaConstraints())
    }

    private fun emitSdp(peerId: Long, d: SessionDescription) {
        onSignal(peerId, JSONObject().put("sdp", JSONObject()
            .put("type", d.type.canonicalForm())
            .put("sdp", d.description)))
    }

    /** Сигнал от конкретного участника: {"sdp":...} или {"candidate":...}. Если соединения с ним ещё нет
        (пришёл его оффер раньше, чем мы сами узнали о нём) — создаёт его на лету. */
    fun onRemoteSignal(peerId: Long, data: JSONObject) {
        if (!peers.containsKey(peerId)) addPeer(peerId)
        val state = peers[peerId] ?: return
        val p = state.pc
        data.optJSONObject("sdp")?.let { sdp ->
            val type = SessionDescription.Type.fromCanonicalForm(sdp.getString("type"))
            p.setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() {
                    main.post {
                        state.remoteSet = true
                        state.pending.forEach { p.addIceCandidate(it) }
                        state.pending.clear()
                        if (type == SessionDescription.Type.OFFER) createAnswer(peerId)
                    }
                }
                override fun onSetFailure(e: String?) { main.post { onPeerFailed(peerId) } }
            }, SessionDescription(type, sdp.getString("sdp")))
        }
        data.optJSONObject("candidate")?.let { c ->
            val mid = if (c.isNull("sdpMid")) null else c.getString("sdpMid")
            val cand = IceCandidate(mid, c.optInt("sdpMLineIndex", 0), c.getString("candidate"))
            if (state.remoteSet) p.addIceCandidate(cand) else state.pending.add(cand)
        }
    }

    private fun observerFor(peerId: Long) = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate?) {
            c ?: return
            main.post {
                onSignal(peerId, JSONObject().put("candidate", JSONObject()
                    .put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid)
                    .put("sdpMLineIndex", c.sdpMLineIndex)))
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            if (track is VideoTrack) main.post { remoteVideos.value = remoteVideos.value + (peerId to track) }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            main.post {
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> onPeerConnected(peerId)
                    PeerConnection.PeerConnectionState.FAILED -> onPeerFailed(peerId)
                    else -> Unit
                }
            }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
    }

    /** Один участник вышел — закрывает именно его соединение, остальных не трогает. */
    fun removePeer(peerId: Long) {
        val state = peers.remove(peerId) ?: return
        remoteVideos.value = remoteVideos.value - peerId
        try { state.pc.close() } catch (e: Exception) { /* уже закрыто */ }
        main.postDelayed({ runCatching { state.pc.dispose() } }, 600)
    }

    // ---------- управление (действует сразу на все соединения — треки общие) ----------

    fun setMuted(muted: Boolean) { audioTrack?.setEnabled(!muted) }
    fun setCameraOff(off: Boolean) { videoTrack?.setEnabled(!off) }
    fun switchCamera() { capturer?.switchCamera(null) }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) = applyAudioRoute(preferSpeaker = on)

    /** true, если сейчас доступна Bluetooth-гарнитура (SCO для звонков или A2DP) — ей отдаём наивысший приоритет. */
    private fun hasBluetoothHeadset(): Boolean = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    } catch (e: Exception) {
        false
    }

    /** Обычные проводные наушники/гарнитура (разъём 3,5 мм или USB-C с адаптером). Android сам маршрутизирует
        звук на них, пока приложение явно не включит динамик — именно это раньше и ломало провод на видеозвонках. */
    private fun hasWiredHeadset(): Boolean = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
    } catch (e: Exception) {
        false
    }

    /** Порядок приоритета: Bluetooth-гарнитура > проводная гарнитура > preferSpeaker (динамик/наушник телефона).
        Вызывается и при старте звонка, и по кнопке «громкая связь» — так переключатель в UI не перебивает
        ни Bluetooth, ни провод: пока они подключены, звук идёт только туда. */
    @Suppress("DEPRECATION")
    private fun applyAudioRoute(preferSpeaker: Boolean) {
        when {
            hasBluetoothHeadset() -> {
                audio.isSpeakerphoneOn = false
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
            }
            hasWiredHeadset() -> {
                audio.isBluetoothScoOn = false
                runCatching { audio.stopBluetoothSco() }
                audio.isSpeakerphoneOn = false // провод — это уже осознанный выбор, а не то, что переключает preferSpeaker
            }
            else -> {
                audio.isBluetoothScoOn = false
                runCatching { audio.stopBluetoothSco() }
                audio.isSpeakerphoneOn = preferSpeaker
            }
        }
    }

    /** Звонок — это VOICE_COMMUNICATION с точки зрения системы: так её слышит и другой одновременный звонок
        (обычный по SIM), и наоборот — при его ответе мы получим AUDIOFOCUS_LOSS и должны встать «на удержание». */
    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focusRequest = req
        audio.requestAudioFocus(req)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                main.post { onHoldChanged(true) }
            AudioManager.AUDIOFOCUS_GAIN -> main.post { onHoldChanged(false) }
            else -> {}
        }
    }

    /** Освобождает всё — все соединения и общие локальные треки; менеджер можно использовать снова. */
    fun close() {
        val allPeers = peers.values.toList()
        peers.clear()
        remoteVideos.value = emptyMap()
        allPeers.forEach { try { it.pc.close() } catch (e: Exception) { /* уже закрыто */ } }

        val oldAudioTrack = audioTrack; val oldAudioSource = audioSource
        val oldVideoTrack = videoTrack; val oldVideoSource = videoSource
        val oldCapturer = capturer; val oldHelper = surfaceHelper
        audioTrack = null; audioSource = null
        videoTrack = null; videoSource = null; capturer = null; surfaceHelper = null
        // сначала UI снимает рендереры с треков, и только потом уничтожаем нативные объекты
        localVideo.value = null
        try { oldCapturer?.stopCapture() } catch (_: InterruptedException) {}
        main.postDelayed({
            runCatching {
                allPeers.forEach { it.pc.dispose() }
                oldCapturer?.dispose()
                oldHelper?.dispose()
                oldVideoTrack?.dispose(); oldVideoSource?.dispose()
                oldAudioTrack?.dispose(); oldAudioSource?.dispose()
            }
        }, 600)
        audio.mode = AudioManager.MODE_NORMAL
        abandonAudioFocus()
        @Suppress("DEPRECATION")
        run {
            audio.isSpeakerphoneOn = false
            audio.isBluetoothScoOn = false
        }
        runCatching { audio.stopBluetoothSco() }
    }

    fun destroy() {
        close()
        main.postDelayed({ runCatching { factory?.dispose(); eglBase.release() } }, 900)
    }
}
