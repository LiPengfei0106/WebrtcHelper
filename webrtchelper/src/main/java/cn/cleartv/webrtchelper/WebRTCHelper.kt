package cn.cleartv.webrtchelper

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object WebRTCHelper {

    lateinit var appContext: Context
        private set

    // 全局PeerConnectionFactory
    private var _factory: PeerConnectionFactory? = null
    val factory: PeerConnectionFactory get() = initializePeerConnectionFactory()

    private val rootEglBase: EglBase by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) EglBase.createEgl10(EglBase.CONFIG_PLAIN) else EglBase.create()
    }

    val rootEglBaseContext: EglBase.Context = rootEglBase.eglBaseContext

    val pchMap: ConcurrentHashMap<String, PeerConnectionHelper> = ConcurrentHashMap()

    var minBitrate: Int = 1000000
    var curBitrate: Int = 2000000
    var maxBitrate: Int = 4000000

    private lateinit var handler: Handler

    fun init(context: Context) {
        appContext = context.applicationContext
        handler = Handler(Looper.getMainLooper())
        initializePeerConnectionFactory()
    }

    @Synchronized
    private fun initializePeerConnectionFactory(): PeerConnectionFactory {
        _factory?.let {
            return it
        }
        L.i("Init PeerConnectionFactory")

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        return PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    rootEglBaseContext,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(
                    rootEglBaseContext// else null,// 全景模式下需要将openGL的Texture中的画面导出，会出现色彩偏差，这里传null将不会走OpenGL但是会导致性能变差
                )
            )
            .setAudioDeviceModule(AudioDeviceManager.initializeAudioDeviceModule(appContext))
            .createPeerConnectionFactory().apply {
                _factory = this
            }
    }

    fun enableLog(enable: Boolean) {
        if (enable) {
            Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
            L.setLogLevel(L.Level.INFO)
        } else {
            Logging.enableLogToDebugOutput(Logging.Severity.LS_ERROR)
            L.setLogLevel(L.Level.ERROR)
        }
    }

    @Synchronized
    fun releasePeerConnectionFactory() {
        L.i("Release PeerConnectionFactory")
        _factory?.stopAecDump()
        _factory?.dispose()
        _factory = null
        try {
            PeerConnectionFactory.stopInternalTracingCapture()
        } catch (e: UnsatisfiedLinkError) {
            L.w("stopInternalTracingCapture", e)
        }
        try {
            PeerConnectionFactory.shutdownInternalTracer()
        } catch (e: UnsatisfiedLinkError) {
            L.w("shutdownInternalTracer", e)
        }

    }

    fun addIceCandidate(
        streamId: String,
        iceJsonStr: String
    ) {
        L.i("addIceCandidate: $streamId, $iceJsonStr")
        pchMap[streamId]?.let {
            val ice = JSONObject(iceJsonStr)
            it.addIceCandidate(
                IceCandidate(
                    ice.optString("sdpMid"),
                    ice.optInt("sdpMLineIndex"),
                    ice.optString("candidate"),
                )
            )
        }
    }

    /**
     * WHIP 推流， 推流给WHIP服务器，local -> server
     */
    suspend fun pushStream(
        streamId: String,
        videoTrack: VideoTrack?,
        audioTrack: AudioTrack?,
        onSdpAnswer: suspend (String) -> String,
        onIce: (String) -> Unit = {},
        onDisconnect: () -> Unit
    ) {
        L.i("pushStream $streamId")
        pchMap[streamId]?.dispose()
        if (videoTrack == null && audioTrack == null) throw IllegalArgumentException("video and audio both null")
        val pch = PeerConnectionHelper(streamId)
        pchMap[streamId] = pch
        pch.onDisconnected = {
            releaseConnection(streamId)
            onDisconnect()
        }
        pch.onConnectFailed = {
            releaseConnection(streamId)
            onDisconnect()
        }
        pch.onIce = {
            onIce(JSONObject().apply {
                put("sdpMid", it.sdpMid)
                put("sdpMLineIndex", it.sdpMLineIndex)
                put("candidate", it.sdp)
            }.toString())
        }
        videoTrack?.let { pch.addTrack(it) }
        audioTrack?.let { pch.addTrack(it) }
        val sdpoffer = pch.createOffer(PeerConnectionHelper.TYPE_SENDONLY)
        val iceSdp = StringBuilder()
        val iceList = suspendCoroutine { cont ->
            pch.onIceComplete = {
                cont.resume(it)
            }
        }
        iceList.forEach {
            iceSdp.append("a=")
            iceSdp.append(it.sdp)
            iceSdp.append("\n")
        }
        val sdpAnswer = onSdpAnswer.invoke("${sdpoffer.description}${iceSdp}")
        pch.setRemoteDescriptionAnswer(sdpAnswer)
    }

    /**
     * WHEP 推流， 推流给WHEP客户端，local -> client
     */
    suspend fun pushStream(
        streamId: String,
        videoTrack: VideoTrack?,
        audioTrack: AudioTrack?,
        sdpOffer: String,
        onIce: (String) -> Unit = {},
        onDisconnect: () -> Unit,
    ): String {
        L.d("pushStream $streamId: \n$sdpOffer")
        pchMap[streamId]?.dispose()
        if (videoTrack == null && audioTrack == null) throw IllegalArgumentException("video and audio both null")
        val pch = PeerConnectionHelper(streamId)
        pchMap[streamId] = pch
        pch.onDisconnected = {
            releaseConnection(streamId)
            onDisconnect()
        }
        pch.onConnectFailed = {
            releaseConnection(streamId)
            onDisconnect()
        }
        pch.onIce = {
            onIce(JSONObject().apply {
                put("sdpMid", it.sdpMid)
                put("sdpMLineIndex", it.sdpMLineIndex)
                put("candidate", it.sdp)
            }.toString())
        }
        videoTrack?.let { pch.addTrack(it) }
        audioTrack?.let { pch.addTrack(it) }

        pch.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdpOffer))
        val sdp = pch.createAnswer(PeerConnectionHelper.TYPE_SENDONLY).description
        val iceSdp = StringBuilder()
        val iceList = suspendCoroutine { cont ->
            pch.onIceComplete = {
                cont.resume(it)
            }
        }
        iceList.forEach {
            iceSdp.append("a=")
            iceSdp.append(it.sdp)
            iceSdp.append("\n")
        }
        return "$sdp${iceSdp}"
    }

    /**
     * WHEP 拉流， 从WHEP服务器拉流 server -> local
     */
    suspend fun pullStream(
        streamId: String,
        onSdpAnswer: suspend (String) -> String,
        onAudioConnected: (audioTrack: AudioTrack?) -> Unit,
        onVideoConnected: (audioTrack: VideoTrack?) -> Unit,
        onIce: (String) -> Unit = {},
        onDisconnect: () -> Unit,
    ) {
        L.d("pullStream $streamId")
        pchMap[streamId]?.dispose()

        val pch = PeerConnectionHelper(streamId)
        pchMap[streamId] = pch
        pch.onDisconnected = {
            releaseConnection(streamId)
            onDisconnect()
        }
        pch.onConnectFailed = {
            releaseConnection(streamId)
            onDisconnect()
        }
        pch.onIce = {
            onIce(JSONObject().apply {
                put("sdpMid", it.sdpMid)
                put("sdpMLineIndex", it.sdpMLineIndex)
                put("candidate", it.sdp)
            }.toString())
        }
        pch.onAudioConnected = onAudioConnected
        pch.onVideoConnected = onVideoConnected
        val sdpoffer = pch.createOffer(PeerConnectionHelper.TYPE_RECEVIEONLY)
        val sdpAnswer = onSdpAnswer.invoke(sdpoffer.description)
        pch.setRemoteDescriptionAnswer(sdpAnswer)
    }

    /**
     * WHIP 拉流， 获取WHIP客户端推的流 client -> local
     */
    suspend fun pullStream(
        streamId: String,
        sdpOffer: String,
        onAudioConnected: (audioTrack: AudioTrack?) -> Unit,
        onVideoConnected: (audioTrack: VideoTrack?) -> Unit,
        onIce: (String) -> Unit = {},
        onDisconnect: () -> Unit,
    ): String {
        L.d("pullStream $streamId\n$sdpOffer")
        pchMap[streamId]?.dispose()

        val pch = PeerConnectionHelper(streamId)
        pchMap[streamId] = pch
        pch.onDisconnected = {
            releaseConnection(streamId)
            onDisconnect()
        }
        pch.onConnectFailed = {
            releaseConnection(streamId)
            onDisconnect()
        }
        pch.onIce = {
            onIce(JSONObject().apply {
                put("sdpMid", it.sdpMid)
                put("sdpMLineIndex", it.sdpMLineIndex)
                put("candidate", it.sdp)
            }.toString())
        }
        pch.onAudioConnected = onAudioConnected
        pch.onVideoConnected = onVideoConnected

        pch.setRemoteDescriptionOffer(sdpOffer)
        return pch.createAnswer(PeerConnectionHelper.TYPE_RECEVIEONLY).description
    }

    fun releaseConnection(connectId: String) {
        pchMap.remove(connectId)?.dispose()
    }

    fun releaseAllConnection() {
        pchMap.values.forEach {
            it.dispose()
        }
        pchMap.clear()
    }


    fun getAudioStats(connectId: String): AudioStats? {
        return pchMap[connectId]?.let {
            it.updateAudioStats()
            it.audioStats.copy()
        }
    }

    fun getVideoStats(connectId: String): VideoStats? {
        return pchMap[connectId]?.let {
            it.updateVideoStats()
            it.videoStats.copy()
        }
    }
}