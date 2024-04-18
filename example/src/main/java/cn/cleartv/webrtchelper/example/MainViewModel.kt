package cn.cleartv.webrtchelper.example

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.cleartv.webrtchelper.AudioSourceHelper
import cn.cleartv.webrtchelper.VideoSourceHelper
import cn.cleartv.webrtchelper.VideoTrackView
import cn.cleartv.webrtchelper.WebRTCHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    val videoSourceHelper: VideoSourceHelper by lazy { VideoSourceHelper().apply { initCameraCapturer() } }
    val audioSourceHelper: AudioSourceHelper by lazy { AudioSourceHelper() }

    override fun onCleared() {
        super.onCleared()
        WebRTCHelper.releaseAllConnection()
        videoSourceHelper.release()
        audioSourceHelper.release()
    }

    /**
     * 本地测试，推流端发起。
     */
    fun localTestPushStart(localSurfaceView: VideoTrackView, remoteSurfaceView: VideoTrackView) {
        WebRTCHelper.releaseAllConnection()
        viewModelScope.launch {
            val videoTrack = videoSourceHelper.getOrCreateVideoTrack("localVideo", false)
            val audioTrack = audioSourceHelper.getOrCreateAudioTrack("localAudio")
            if (!videoSourceHelper.isCapturerStarted) {
                videoSourceHelper.startCapture(1920, 1080, 30)
            }
            localSurfaceView.showVideoTrack(videoTrack)
            // 推流
            WebRTCHelper.pushStream(
                "pushStartTestPusher",
                videoTrack,
                audioTrack,
                onSdp = { sdpOffer ->
                    // 拉流
                    val sdpAnswer = WebRTCHelper.pullStream(
                        "pushStartTestPuller",
                        sdpOffer,
                        onAudioConnected = {

                        },
                        onVideoConnected = {
                            launch(Dispatchers.Main) {
                                remoteSurfaceView.showVideoTrack(it)
                            }
                        },
                        onIce = {
                            WebRTCHelper.addIceCandidate("pushStartTestPusher", it)
                        },
                        onDisconnect = {

                        })
                    return@pushStream sdpAnswer
                },
                onIce = {
                    // 发流端的sdp中会携带ice，这里可以不用给拉流端设置
                    WebRTCHelper.addIceCandidate("pushStartTestPuller", it)
                },
                onDisconnect = {
                    videoSourceHelper.stopCapture()
                })
        }
    }

    /**
     * 本地测试，拉流端发起
     */
    fun localTestPullStart(localSurfaceView: VideoTrackView, remoteSurfaceView: VideoTrackView) {
        WebRTCHelper.releaseAllConnection()
//        localVideoTrackView.bindSurfaceView(localSurfaceView)
//        remoteVideoTrackView.bindSurfaceView(remoteSurfaceView)
        viewModelScope.launch {
            // 拉流
            WebRTCHelper.pullStream(
                "pullStartTestPuller",
                onSdp = { sdpOffer ->
                    // 推流
                    val sdpAnswer = withContext(Dispatchers.Main) {
                        val videoTrack =
                            videoSourceHelper.getOrCreateVideoTrack("localVideo", false)
                        val audioTrack = audioSourceHelper.getOrCreateAudioTrack("localAudio")
                        if (!videoSourceHelper.isCapturerStarted) {
                            videoSourceHelper.startCapture(1920, 1080, 30)
                        }
                        localSurfaceView.showVideoTrack(videoTrack)
                        WebRTCHelper.pushStream(
                            "pullStartTestPusher",
                            videoTrack,
                            audioTrack,
                            sdpOffer,
                            onIce = {
                                // 发流端的sdp中会携带ice，这里可以不用给拉流端设置
                                WebRTCHelper.addIceCandidate("pullStartTestPuller", it)
                            },
                            onDisconnect = {
                                videoSourceHelper.stopCapture()
                            })
                    }
                    return@pullStream sdpAnswer
                },
                onAudioConnected = {

                },
                onVideoConnected = {
                    launch(Dispatchers.Main) {
                        remoteSurfaceView.showVideoTrack(it)
                    }
                },
                onIce = {
                    WebRTCHelper.addIceCandidate("pullStartTestPusher", it)
                },
                onDisconnect = {

                })
        }
    }

}