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

    private val pchMap: ConcurrentHashMap<String, PeerConnectionHelper> = ConcurrentHashMap()

    var minBitrate: Int = 1000000
    var curBitrate: Int = 2000000
    var maxBitrate: Int = 4000000

    private lateinit var handler: Handler

    /**
     * 初始化，在合适的地方调用
     */
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

    /**
     * 开启日志
     */
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

    /**
     * 给流的连接设置ICE信息
     */
    fun addIceCandidate(
        streamId: String,
        iceJsonStr: String
    ) {
        if (iceJsonStr.isNotBlank()) {
            L.i("addIceCandidate: $streamId, $iceJsonStr")
            pchMap[streamId]?.let {
                try {
                    val ice = JSONObject(iceJsonStr)
                    it.addIceCandidate(
                        IceCandidate(
                            ice.optString("sdpMid"),
                            ice.optInt("sdpMLineIndex"),
                            ice.optString("candidate"),
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * 推流， 可用于推流给WHIP服务器，local -> server
     * @param streamId 自定义流的唯一ID，用于区分每个连接
     * @param videoTrack 可通过[VideoSourceHelper]获取
     * @param audioTrack 可通过[AudioSourceHelper]获取
     * @param onSdp 生成SdpOffer回调，需要返回SdpAnswer； offer中包含ice信息，可直接用于推流给whip服务器并获取服务器返回的sdpAnswer。 (sdpOffer) -> sdpAnswer
     * @param onIce 生成ice消息回调, 当回调信息为空白字符串时表示ice生成完毕 (iceString) -> Unit
     * @param onDisconnect 连接断开回调
     */
    suspend fun pushStream(
        streamId: String,
        videoTrack: VideoTrack?,
        audioTrack: AudioTrack?,
        onSdp: suspend (String) -> String,
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
                onIce("")
                cont.resume(it)
            }
        }
        iceList.forEach {
            iceSdp.append("a=")
            iceSdp.append(it.sdp)
            iceSdp.append("\n")
        }
        val sdpAnswer = onSdp.invoke("${sdpoffer.description}${iceSdp}")
        pch.setRemoteDescriptionAnswer(sdpAnswer)
    }

    /**
     * 推流， 可用于推流给WHEP客户端，local -> server
     * @param streamId 自定义流的唯一ID，用于区分每个连接
     * @param videoTrack 可通过[VideoSourceHelper]获取
     * @param audioTrack 可通过[AudioSourceHelper]获取
     * @param sdpOffer 发起请求的sdpOffer信息
     * @param onIce 生成ice消息回调, 当回调信息为空白字符串时表示ice生成完毕 (iceString) -> Unit
     * @param onDisconnect 连接断开回调
     * @return 返回生成的sdpAnswer
     */
    suspend fun pushStream(
        streamId: String,
        videoTrack: VideoTrack?,
        audioTrack: AudioTrack?,
        sdpOffer: String,
        onIce: (String) -> Unit = {},
        onDisconnect: () -> Unit,
    ): String {
        L.d("pushStream $streamId \n$sdpOffer")
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
                onIce("")
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
     * 拉流， 可用于从WHEP服务器拉流，local -> server
     * @param streamId 自定义流的唯一ID，用于区分每个连接
     * @param onSdp 生成SdpOffer回调，需要返回SdpAnswer； offer中包含ice信息，可直接用于推流给whip服务器并获取服务器返回的sdpAnswer。 (sdpOffer) -> sdpAnswer
     * @param onAudioConnected 远端音频连接成功
     * @param onVideoConnected 远端视频连接成功，可以开始预览远端画面
     * @param onIce 生成ice消息回调, 当回调信息为空白字符串时表示ice生成完毕 (iceString) -> Unit
     * @param onDisconnect 连接断开回调
     */
    suspend fun pullStream(
        streamId: String,
        onSdp: suspend (String) -> String,
        onAudioConnected: (audioTrack: AudioTrack) -> Unit,
        onVideoConnected: (audioTrack: VideoTrack) -> Unit,
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
        pch.onIceComplete = {
            onIce("")
        }
        pch.onAudioConnected = onAudioConnected
        pch.onVideoConnected = onVideoConnected
        val sdpoffer = pch.createOffer(PeerConnectionHelper.TYPE_RECEVIEONLY)
        val sdpAnswer = onSdp.invoke(sdpoffer.description)
        pch.setRemoteDescriptionAnswer(sdpAnswer)
    }

    /**
     * WHIP 拉流， 获取WHIP客户端推的流 client -> local
     * @param streamId 自定义流的唯一ID，用于区分每个连接
     * @param sdpOffer 发起请求的sdpOffer信息
     * @param onAudioConnected 远端音频连接成功
     * @param onVideoConnected 远端视频连接成功，可以开始预览远端画面
     * @param onIce 生成ice消息回调, 当回调信息为空白字符串时表示ice生成完毕 (iceString) -> Unit
     * @param onDisconnect 连接断开回调
     * @return 返回生成的sdpAnswer
     */
    suspend fun pullStream(
        streamId: String,
        sdpOffer: String,
        onAudioConnected: (audioTrack: AudioTrack) -> Unit,
        onVideoConnected: (audioTrack: VideoTrack) -> Unit,
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
        pch.onIceComplete = {
            onIce("")
        }
        pch.onAudioConnected = onAudioConnected
        pch.onVideoConnected = onVideoConnected

        pch.setRemoteDescriptionOffer(sdpOffer)
        return pch.createAnswer(PeerConnectionHelper.TYPE_RECEVIEONLY).description
    }

    /**
     * 释放连接
     * @param streamId 自定义流的唯一ID，用于区分每个连接
     */
    fun releaseConnection(streamId: String) {
        pchMap.remove(streamId)?.dispose()
    }

    /**
     * 释放所有连接
     */
    fun releaseAllConnection() {
        pchMap.values.forEach {
            it.dispose()
        }
        pchMap.clear()
    }

    /**
     * 获取音频推流状态，调用时才会去获取，因为获取的过程是异步的，所以返回的是上一次调用本方法时的状态
     * @param streamId 自定义流的唯一ID，用于区分每个连接
     */
    fun getAudioStats(streamId: String): AudioStats? {
        return pchMap[streamId]?.let {
            it.updateAudioStats()
            it.audioStats.copy()
        }
    }

    /**
     * 获取视频推流状态，调用时才会去获取，因为获取的过程是异步的，所以返回的是上一次调用本方法时的状态
     * @param streamId 自定义流的唯一ID，用于区分每个连接
     */
    fun getVideoStats(streamId: String): VideoStats? {
        return pchMap[streamId]?.let {
            it.updateVideoStats()
            it.videoStats.copy()
        }
    }
}