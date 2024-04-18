package cn.cleartv.webrtchelper

import cn.cleartv.webrtchelper.WebRTCUtils.isExists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.text.DecimalFormat
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
class PeerConnectionHelper(
    val id: String,
    constraints: MediaConstraints = MediaConstraints(),
    configuration: RTCConfiguration = RTCConfiguration()
) : PeerConnection.Observer {

    companion object {
        const val TYPE_SENDONLY = "sendonly"
        const val TYPE_RECEVIEONLY = "receiveonly"
        const val TYPE_SENDRECEVIE = "sendreceive"
    }

    data class RTCConfiguration(
        var iceServers: List<PeerConnection.IceServer> = arrayListOf()
    )

    private val peerConnection: PeerConnection
    var localVideoTrack: VideoTrack? = null
    var localAudioTrack: AudioTrack? = null
    var remoteVideoTrack: VideoTrack? = null
    var remoteAudioTrack: AudioTrack? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private fun launch(
        context: kotlin.coroutines.CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): kotlinx.coroutines.Job {
        return scope.launch(block = block)
    }

    var onAudioConnected: (audioTrack: AudioTrack?) -> Unit = { _ -> }
    var onVideoConnected: (videoTrack: VideoTrack?) -> Unit = { _ -> }
    var onConnectFailed: () -> Unit = { }
    var onConnected: () -> Unit = { }
    var onDisconnected: () -> Unit = { }

    private val iceList = mutableListOf<IceCandidate>()
    var onIce: (ice: IceCandidate) -> Unit = {}
    var onIceComplete: (ice: List<IceCandidate>) -> Unit = { }

    var iceConnectionState: PeerConnection.IceConnectionState =
        PeerConnection.IceConnectionState.NEW
        private set

    val isConnected: Boolean
        get() = (peerConnection.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
                || peerConnection.connectionState() == PeerConnection.PeerConnectionState.CONNECTING)

    var isDisposed = false
        private set

    var videoStats: VideoStats = VideoStats()
        private set

    var audioStats: AudioStats = AudioStats()
        private set

    private var lastTotalEnergy = 0.0
    private var lastTotalDuration = 0.0


    init {
        L.i("init: $id")
        peerConnection = WebRTCHelper.factory.createPeerConnection(
            PeerConnection.RTCConfiguration(configuration.iceServers),
            constraints,
            this
        ) ?: throw Exception("Create Native PeerConnectionObserver Failed!")
        setBitrate(
            WebRTCHelper.minBitrate,
            WebRTCHelper.curBitrate,
            WebRTCHelper.maxBitrate
        )
    }

    fun resetAverageAudioLevelTime(audioStats: AudioStats) {
        L.i("resetAverageAudioLevelTime")
        lastTotalEnergy = audioStats.totalEnergy
        lastTotalDuration = audioStats.totalDuration
    }

    fun getAverageAudioLevel(audioStats: AudioStats): Double {
        if (lastTotalDuration == 0.0 || audioStats.totalDuration == lastTotalDuration) {
            lastTotalEnergy = audioStats.totalEnergy
            lastTotalDuration = audioStats.totalDuration
            return audioStats.audioLevel
        }
        val averageLevel =
            kotlin.math.sqrt(((audioStats.totalEnergy - lastTotalEnergy) / (audioStats.totalDuration - lastTotalDuration))) * 100000
        lastTotalEnergy = audioStats.totalEnergy
        lastTotalDuration = audioStats.totalDuration
        return averageLevel.roundToInt() / 100000.0
    }

    fun dispose() {
        L.i("$id dispose")
        scope.cancel()
        if (isDisposed) return
        isDisposed = true
        localAudioTrack = null
        localVideoTrack = null
        remoteAudioTrack = null
        remoteVideoTrack = null
        iceList.clear()
        onVideoConnected = {}
        onAudioConnected = {}
        onConnected = {}
        onDisconnected = {}
        onConnectFailed = {}
        onIce = {}
        onIceComplete = {}
        try {
            peerConnection.dispose() // todo 这里可能会阻塞，所以新建了个线程
        } catch (e: Exception) {
            e.printStackTrace()
        }
        L.i("$id dispose done")
    }

//    // 最新版本webrtc创建peerconnection后addStream会报错
//    @Deprecated(message = "最新版本webrtc创建peerconnection后addStream会报错", replaceWith = ReplaceWith("addTrack(track: MediaStreamTrack)"))
//    fun addStream(stream: MediaStream) {
//        localStream = stream
//        peerConnection.addStream(stream)
//    }
//
//    var localVideoRtpSender: RtpSender? = null
//    var localAudioRtpSender: RtpSender? = null


    fun addTrack(track: MediaStreamTrack) {
        if (track is VideoTrack) {
            localVideoTrack = track
        }
        if (track is AudioTrack) {
            localAudioTrack = track
        }
        peerConnection.addTrack(track)
    }


    suspend fun createAnswer(
        type: String = TYPE_SENDRECEVIE,
        constraints: MediaConstraints = MediaConstraints()
    ): SessionDescription {
        return suspendCoroutine { cont ->
            createAnswer(
                type,
                constraints,
                { cont.resume(it) },
                { cont.resumeWithException(Exception(it)) })
        }
    }

    fun createAnswer(
        type: String = TYPE_SENDRECEVIE,
        constraints: MediaConstraints,
        onSuccess: (sdp: SessionDescription) -> Unit,
        onFailure: (msg: String) -> Unit
    ) {
        L.i("$id createAnswer")
        peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription) {
                L.i("$id createAnswer success!")
                L.d("$id \n${p0.description}")
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {
                    }

                    override fun onSetSuccess() {
                        L.i("$id set local SDP Answer success!")
                        onSuccess(p0)
                    }

                    override fun onCreateFailure(p0: String?) {
                    }

                    override fun onSetFailure(p0: String?) {
                        L.w("$id setLocalDescription failure: $p0")
                        onFailure("$id ${p0 ?: "set local SDP Answer failure"}")
                    }
                }, p0)
            }

            override fun onSetSuccess() {
            }

            override fun onCreateFailure(p0: String?) {
                L.w("$id createAnswer failure: $p0")
            }

            override fun onSetFailure(p0: String?) {
            }

        }, constraints.apply {
            when (type) {
                TYPE_SENDONLY -> {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }

                TYPE_RECEVIEONLY -> {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }

                else -> {

                }
            }
        })
    }

    fun setBitrate(min: Int, current: Int, max: Int) {
//        peerConnection.setBitrate(100000, 100000, 320000)
        peerConnection.setBitrate(min, current, max)
    }

    fun updateAudioStats() {
        remoteAudioTrack?.let { audioTrack ->
            if (!audioTrack.isExists()) {
                return@let
            }
            peerConnection.getStats({
//                it.forEach { report ->
//                    L.d("remoteAudioTrack: $report")
//                }
            }, audioTrack)
        }
        localAudioTrack?.let { audioTrack ->
            if (!audioTrack.isExists()) {
                L.w("checkMediaStreamTrackExists: false")
                return@let
            }
            peerConnection.getStats({ statsReports ->
                val lastAudioTimestamp = audioStats.audioStatsTimestamp
                val lastBytesSent = audioStats.audioBytesSent
                val lastAudioPacketsLost = audioStats.audioPacketsLost
                val lastAudioPacketsSend = audioStats.audioPacketsSend

                var newAudioPacketsLost = 0
                var newAudioPacketsSend = 0
                val curAudioStats = AudioStats()
                statsReports.forEach { report ->
                    L.d(report.toString())
                    curAudioStats.audioStatsTimestamp = report.timestamp
                    for (value in report.values) {
                        when (value.name) {
                            "audioInputLevel" -> curAudioStats.audioLevel =
                                (value.value.toInt() / 32768.0 * 100000).roundToInt() / 100000.0

                            "totalAudioEnergy" -> curAudioStats.totalEnergy = value.value.toDouble()
                            "totalSamplesDuration" -> curAudioStats.totalDuration =
                                value.value.toDouble()

                            "googRtt" -> curAudioStats.audioRtt = value.value.toInt()
                            "packetsLost" -> {
                                newAudioPacketsLost = value.value.toInt()
                                curAudioStats.audioPacketsLost = value.value.toInt()
                            }

                            "packetsSent" -> {
                                newAudioPacketsSend = value.value.toInt()
                                curAudioStats.audioPacketsSend = value.value.toInt()
                            }

                            "bytesSent" -> {
                                curAudioStats.audioBytesSent = value.value.toLong()
                                if (lastBytesSent > 0) {
                                    curAudioStats.audioBitRate =
                                        (curAudioStats.audioBytesSent - lastBytesSent) * 1000 / (curAudioStats.audioStatsTimestamp - lastAudioTimestamp)
                                }
                            }
                        }
                    }
                }

                if (newAudioPacketsSend - lastAudioPacketsSend > 100) {
                    curAudioStats.audioPacketsLost = newAudioPacketsLost
                    curAudioStats.audioPacketsSend = newAudioPacketsSend
                    curAudioStats.recentAudioPacketsLostRate =
                        (curAudioStats.audioPacketsLost - lastAudioPacketsLost) * 10000 / (curAudioStats.audioPacketsSend - lastAudioPacketsSend) / 100.0
                } else if (newAudioPacketsSend < lastAudioPacketsSend) {
                    curAudioStats.audioPacketsLost = newAudioPacketsLost
                    curAudioStats.audioPacketsSend = newAudioPacketsSend
                    curAudioStats.recentAudioPacketsLostRate = 0.0
                }
                audioStats = curAudioStats
//                L.d("${audioTrack.id()} \n" + audioStats())
            }, audioTrack)
        }

    }

    fun updateVideoStats() {
        remoteVideoTrack?.let { videoTrack ->
            if (!videoTrack.isExists()) {
                return@let
            }
            peerConnection.getStats({
//                it.forEach { report ->
//                    L.d("remoteVideoTrack: $report")
//                }
            }, videoTrack)
        }
        localVideoTrack?.let { videoTrack ->
            if (!videoTrack.isExists()) {
                return@let
            }
            peerConnection.getStats({ statsReports ->
                val newVideoStats = VideoStats().apply {
                    val lastVideoTimestamp = videoStats.videoStatsTimestamp
                    val lastVideoPacketsLost = videoStats.videoPacketsLost
                    val lastVideoPacketsSend = videoStats.videoPacketsSend
                    val lastVideoBytesSent = videoStats.videoBytesSent

                    var newVideoPacketsLost = 0
                    var newVideoPacketsSend = 0
                    statsReports.forEach { report ->
//                    L.d(report.toString())
                        videoStatsTimestamp = report.timestamp
                        for (value in report.values) {
                            when (value.name) {
                                "googFrameWidthInput" -> inputVideoWidth = value.value.toInt()
                                "googFrameHeightInput" -> inputVideoHeight = value.value.toInt()
                                "googFrameRateInput" -> inputVideoFrameRate = value.value.toInt()

                                "googFrameWidthSent" -> sentVideoWidth = value.value.toInt()
                                "googFrameHeightSent" -> sentVideoHeight = value.value.toInt()
                                "googFrameRateSent" -> sentVideoFrameRate = value.value.toInt()

                                "googRtt" -> videoRtt = value.value.toInt()
                                "googAvgEncodeMs" -> videoAvgEncodeMs = value.value.toInt()

                                "packetsLost" -> newVideoPacketsLost =
                                    value.value.toInt().let { if (it < 0) 0 else it }

                                "packetsSent" -> newVideoPacketsSend = value.value.toInt()

                                "bytesSent" -> {
                                    videoBytesSent = value.value.toLong()
                                    if (lastVideoBytesSent > 0) {
                                        // 码率， 每秒的Bytes
                                        videoBitRate =
                                            (videoBytesSent - lastVideoBytesSent) * 1000 / (videoStatsTimestamp - lastVideoTimestamp)
                                    }
                                }
                            }
                        }
                    }
                    if (newVideoPacketsSend - lastVideoPacketsSend > 200) {
                        recentVideoPacketsLostRate =
                            (newVideoPacketsLost - lastVideoPacketsLost) * 10000 / (newVideoPacketsSend - lastVideoPacketsSend) / 100.0
                        videoPacketsLost = newVideoPacketsLost
                        videoPacketsSend = newVideoPacketsSend
                    } else if (newVideoPacketsSend < lastVideoPacketsSend) {
                        recentVideoPacketsLostRate = 0.0
                        videoPacketsLost = newVideoPacketsLost
                        videoPacketsSend = newVideoPacketsSend
                    }
                }
                videoStats = newVideoStats
            }, videoTrack)
            L.d("${videoTrack.id()} \n" + videoStats)
        }
    }


    suspend fun createOffer(
        type: String = TYPE_SENDRECEVIE,
        constraints: MediaConstraints = MediaConstraints()
    ): SessionDescription {
        return suspendCoroutine { cont ->
            createOffer(type, constraints, {
                cont.resume(it)
            }, {
                cont.resumeWithException(Exception(it))
            })
        }
    }

    fun createOffer(
        type: String = TYPE_SENDRECEVIE,
        constraints: MediaConstraints = MediaConstraints(),
        onSuccess: (sdp: SessionDescription) -> Unit,
        onFailure: (msg: String) -> Unit
    ) {
        L.i("$id createOffer")
        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription) {
                L.i("$id createOffer success!")
                L.d("$id  \n${p0.description}")
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {
                    }

                    override fun onSetSuccess() {
                        L.i("$id set local SDP Offer success!")
                        onSuccess(p0)
                    }

                    override fun onCreateFailure(p0: String?) {
                    }

                    override fun onSetFailure(p0: String?) {
                        L.w("$id setLocalDescription failure: $p0")
                        onFailure("$id ${p0 ?: "set local SDP Offer failure"}")
                    }
                }, p0)
            }

            override fun onSetSuccess() {
            }

            override fun onCreateFailure(p0: String?) {
                L.w("$id createOffer failure: $p0")
                onFailure("$id ${p0 ?: "create SDP Offer failure"}")
            }

            override fun onSetFailure(p0: String?) {
            }

        }, constraints.apply {
            when (type) {
                TYPE_SENDONLY -> {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }

                TYPE_RECEVIEONLY -> {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }

                else -> {

                }
            }
        })
    }


    suspend fun setRemoteDescriptionAnswer(sdpAnswer: String) {
        return suspendCoroutine { cont ->
            setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer), {
                cont.resume(Unit)
            }, {
                cont.resumeWithException(Exception(it))
            })
        }
    }


    suspend fun setRemoteDescriptionOffer(sdpOffer: String) {
        return suspendCoroutine { cont ->
            setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdpOffer), {
                cont.resume(Unit)
            }, {
                cont.resumeWithException(Exception(it))
            })
        }
    }


    suspend fun setRemoteDescription(sdp: SessionDescription) {
        return suspendCoroutine { cont ->
            setRemoteDescription(sdp, {
                cont.resume(Unit)
            }, {
                cont.resumeWithException(Exception(it))
            })
        }
    }

    fun setRemoteDescription(
        sdp: SessionDescription,
        onSetSuccess: () -> Unit,
        onSetFailure: (msg: String) -> Unit
    ) {
        L.i("$id setRemoteDescription: ${sdp.type}")
        L.d("$id  \n${sdp.description}")
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
            }

            override fun onSetSuccess() {
                L.i("$id setRemoteDescription success!")
                onSetSuccess()
            }

            override fun onCreateFailure(p0: String?) {
            }

            override fun onSetFailure(p0: String?) {
                L.w("$id setRemoteDescription failure: $p0")
                onSetFailure(p0 ?: "set Remote SDP failure")
            }

        }, sdp)
    }

    fun addIceCandidate(ice: IceCandidate) {
        L.i("$id addIceCandidate: $ice")
        peerConnection.addIceCandidate(ice)
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
        L.i("$id onSignalingChange $p0")
    }

    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState) {
        L.i("$id onIceConnectionChange $p0")
        iceConnectionState = p0
        when (p0) {
            PeerConnection.IceConnectionState.CONNECTED -> {
                launch { onConnected() }
            }

            PeerConnection.IceConnectionState.FAILED -> {
                launch { onConnectFailed() }
            }

            PeerConnection.IceConnectionState.CLOSED -> {
                launch { onDisconnected() }
            }

            PeerConnection.IceConnectionState.DISCONNECTED -> {
                launch { onDisconnected() }
            }

            else -> {

            }
        }
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {
        L.i("$id onIceConnectionReceivingChange $p0")
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
        L.i("$id onIceGatheringChange $p0")
        if (p0 == PeerConnection.IceGatheringState.COMPLETE) {
            launch { onIceComplete(iceList) }
        }
    }

    override fun onIceCandidate(p0: IceCandidate) {
        L.i("$id onIceCandidate $p0")
        iceList.add(p0)
        launch { onIce(p0) }
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
        L.i("$id onIceCandidatesRemoved $p0")
    }

    override fun onAddStream(p0: MediaStream?) {
        L.i("$id onAddStream $p0")
        p0?.videoTracks?.firstOrNull()?.let { track ->
            L.i("$id remoteVideoTrack $track")
            if (remoteVideoTrack == null) {
                remoteVideoTrack = track
                launch {
                    onVideoConnected(track)
                }
            }
        }
        p0?.audioTracks?.firstOrNull()?.let { track ->
            L.i("$id remoteAudioTrack $track")
            if (remoteAudioTrack == null) {
                remoteAudioTrack = track
                launch {
                    onAudioConnected(track)
                }
            }
        }
//        p0?.let {
//            callback?.onAddStream(p0)
//        }
    }

    override fun onRemoveStream(p0: MediaStream?) {
        L.i("$id onRemoveStream $p0")
//        p0?.let {
//            callback?.onRemoveStream(p0)
//        }
    }

    override fun onDataChannel(p0: DataChannel?) {
        L.i("$id onDataChannel $p0")
    }

    override fun onRenegotiationNeeded() {
        L.i("$id onRenegotiationNeeded")
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
        L.i("$id onAddTrack $p0 ${p1?.joinToString()}")
//        p0?.setJitterBufferMinimumDelay(ClearRTCEngineInternalImpl.config.jitterBufferMinimumDelay)
        when (val track = p0?.track()) {
            is VideoTrack -> {
                L.i("$id remoteVideoTrack $track")
                remoteVideoTrack = track
                launch { onVideoConnected(track) }
            }

            is AudioTrack -> {
                L.i("$id remoteAudioTrack $track")
                remoteAudioTrack = track
                launch { onAudioConnected(track) }
            }
        }
    }




}