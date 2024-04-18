package cn.cleartv.webrtchelper

import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import cn.cleartv.webrtchelper.WebRTCUtils.getCameraName
import cn.cleartv.webrtchelper.WebRTCUtils.setRotation
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler
import org.webrtc.CapturerObserver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.YuvConverter
import java.util.concurrent.ConcurrentHashMap

class VideoSourceHelper(
    val capturerObserver: CapturerObserver? = null,
) : CameraVideoCapturer.CameraEventsHandler, CapturerObserver {

    var customRotation: Int = 0
        set(value) {
            if (value % 90 == 0) {
                field = value % 360
            } else {
                L.w("rotation should be multiple of 90!")
            }
        }

    var useCamera2: Boolean = false
    var cameraName: String = ""
    val cameraEnumerator: CameraEnumerator by lazy {
        if (useCamera2) {
            Camera2Enumerator(WebRTCHelper.appContext)
        } else {
            Camera1Enumerator(false)
        }
    }

    // 视频捕获是否开始
    var isCapturerStarted = false
        private set

    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var captureFrameRate: Int = 0

    private var yuvConverter: YuvConverter? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null

    private val videoSourceMap: ConcurrentHashMap<String, VideoSource> = ConcurrentHashMap()
    private val videoTrackMap: ConcurrentHashMap<String, VideoTrack> = ConcurrentHashMap()

    @Synchronized
    fun release() {

        yuvConverter?.release()
        yuvConverter = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        videoSourceMap.values.forEach {
            it.dispose()
        }
        videoSourceMap.clear()
        videoTrackMap.values.forEach {

        }
        videoTrackMap.clear()
    }

    fun initScreenCapturer(intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            throw Exception("Android version not support! need >= lollipop")
        }
        release()

        videoCapturer = ScreenCapturerAndroid(
            intent,
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            object : MediaProjection.Callback() {
                override fun onStop() {
                    L.i("User revoked permission to capture the screen.")
                }
            }).apply {
            yuvConverter?.release()
            yuvConverter = YuvConverter()
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper =
                SurfaceTextureHelper.create(
                    this.toString(),
                    WebRTCHelper.rootEglBaseContext,
                    false,
                    yuvConverter
                )
            initialize(surfaceTextureHelper, WebRTCHelper.appContext, this@VideoSourceHelper)
        }
    }

    private fun checkCameraName(deviceName: String) {
        val cameraDeviceNames = cameraEnumerator.deviceNames
        if (!cameraDeviceNames.contains(deviceName)) {
            cameraDeviceNames.firstOrNull()?.let {
                cameraName = it
            }
        } else {
            cameraName = deviceName
        }
    }

    fun initCameraCapturer(deviceName: String = "", useCamera2: Boolean = false) {
        release()

        this.useCamera2 = useCamera2
        checkCameraName(deviceName)

        videoCapturer = cameraEnumerator.createCapturer(cameraName, this)?.apply {
            yuvConverter?.release()
            yuvConverter = YuvConverter()
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper =
                SurfaceTextureHelper.create(
                    this.toString(),
                    WebRTCHelper.rootEglBaseContext,
                    false,
                    yuvConverter
                )
            initialize(surfaceTextureHelper, WebRTCHelper.appContext, this@VideoSourceHelper)
        } ?: throw Exception("create camera capturer failed!")
    }

    fun startCapture(width: Int, height: Int, framerate: Int) {
        captureWidth = width
        captureHeight = height
        captureFrameRate = framerate
        videoCapturer?.startCapture(width, height, framerate)
    }

    fun stopCapture() {
        videoCapturer?.stopCapture()
    }


    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(object : CameraSwitchHandler {
            override fun onCameraSwitchDone(p0: Boolean) {
                val cameraName = (videoCapturer as? CameraVideoCapturer)?.getCameraName() ?: ""
                L.i("onCameraSwitchDone: $p0; ${this@VideoSourceHelper.cameraName} to $cameraName")
                this@VideoSourceHelper.cameraName = cameraName
            }

            override fun onCameraSwitchError(p0: String?) {
                L.w("onCameraSwitchError: $p0")

            }
        })
    }


    fun switchCamera(cameraName: String) {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(object : CameraSwitchHandler {
            override fun onCameraSwitchDone(p0: Boolean) {
                L.d("onCameraSwitchDone: $p0")
                this@VideoSourceHelper.cameraName = cameraName
            }

            override fun onCameraSwitchError(p0: String?) {
                L.w("onCameraSwitchError: $p0")

            }
        }, cameraName)
    }

    fun switchCamera(cameraIndex: Int) {
        val cameraDeviceNames = cameraEnumerator.deviceNames
        cameraDeviceNames?.let {
            switchCamera(it[cameraIndex % it.size])
        }
    }

    override fun onCameraError(p0: String?) {
        L.w("onCameraError: $p0")
        val lastCameraStatus = isCapturerStarted
        // 重新启动相机
        initCameraCapturer()
        if (lastCameraStatus) {
            startCapture(captureWidth, captureHeight, captureFrameRate)
        } else {
            stopCapture()
        }
    }

    override fun onCameraDisconnected() {
        L.d("onCameraDisconnected")
    }

    override fun onCameraFreezed(p0: String?) {
        L.w("onCameraFreezed: $p0")
    }

    override fun onCameraOpening(p0: String?) {
        L.d("onCameraOpening: $p0")
    }

    override fun onFirstFrameAvailable() {
        L.d("onFirstFrameAvailable")
    }

    override fun onCameraClosed() {
        L.d("onCameraClosed")
    }

    override fun onCapturerStarted(p0: Boolean) {
        isCapturerStarted = p0
        videoSourceMap.values.forEach {
            it.capturerObserver.onCapturerStarted(p0)
        }
        capturerObserver?.onCapturerStarted(p0)
    }

    override fun onCapturerStopped() {
        isCapturerStarted = false
        videoSourceMap.values.forEach {
            it.capturerObserver.onCapturerStopped()
        }
        capturerObserver?.onCapturerStopped()
    }

    override fun onFrameCaptured(p0: VideoFrame?) {
        if (customRotation != 0) {
            p0?.setRotation((customRotation + p0.rotation) % 360)
        }
        videoSourceMap.values.forEach {
            it.capturerObserver.onFrameCaptured(p0)
        }
        capturerObserver?.onFrameCaptured(p0)
    }

    @Synchronized
    fun getOrCreateVideoTrack(trackId: String, isScreencast: Boolean = false): VideoTrack {
        return videoTrackMap.getOrPut(trackId) {
            val videoSource: VideoSource =
                WebRTCHelper.factory.createVideoSource(isScreencast)
            videoSourceMap[trackId] = videoSource
            WebRTCHelper.factory.createVideoTrack(
                trackId,
                videoSource
            )
        }
    }

    @Synchronized
    fun removeVideoTrack(trackId: String) {
        videoSourceMap.remove(trackId)?.dispose()
        videoTrackMap.remove(trackId)?.dispose()
    }

}