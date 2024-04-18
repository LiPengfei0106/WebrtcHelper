package cn.cleartv.webrtchelper

import android.content.Context
import android.os.Looper
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.RendererCommon.GlDrawer
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.Objects
import java.util.concurrent.CountDownLatch

class VideoTrackView : ConstraintLayout, RendererCommon.RendererEvents,
    SurfaceHolder.Callback {
    private val eglRenderer: EglRenderer by lazy { EglRenderer(resources.getResourceEntryName(id)) }

    private val videoSink: VideoSink by lazy {
        VideoSink { frame ->
            updateFrameDimensionsAndReportEvents(frame)
            eglRenderer.onFrame(frame)
        }
    }

    private var videoTrack: VideoTrack? = null

    /**
     * 监听渲染事件
     */
    var rendererEvents: RendererCommon.RendererEvents? = null

    private val layoutLock = Any()
    private var isRenderingPaused = false
    private var isFirstFrameRendered = false
    private var rotatedFrameWidth = 0
    private var rotatedFrameHeight = 0
    private var frameRotation = 0
    private var surfaceView: SurfaceView
    private val surfaceViewId: Int = R.id.video_track_view_surfaceview


    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        surfaceView = SurfaceView(context)
        surfaceView.id = surfaceViewId
        surfaceView.holder.addCallback(this)
        if (surfaceView.holder.surface.isValid) {
            eglRenderer.createEglSurface(surfaceView.holder.surface)
        }
        addView(surfaceView)
        val constraintSet = ConstraintSet()
        constraintSet.clone(this)
        constraintSet.constrainWidth(surfaceViewId, ConstraintSet.MATCH_CONSTRAINT)
        constraintSet.constrainHeight(surfaceViewId, ConstraintSet.MATCH_CONSTRAINT)
        constraintSet.connect(
            surfaceViewId,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START
        )
        constraintSet.connect(
            surfaceViewId,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END
        )
        constraintSet.connect(
            surfaceViewId,
            ConstraintSet.TOP,
            ConstraintSet.PARENT_ID,
            ConstraintSet.TOP
        )
        constraintSet.connect(
            surfaceViewId,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM
        )
        constraintSet.applyTo(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        eglRenderer.init(
            WebRTCHelper.rootEglBaseContext,
            EglBase.CONFIG_PLAIN,
            GlRectDrawer()
        )
        videoTrack?.addSink(videoSink)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    private fun release() {
        ThreadUtils.checkIsOnMainThread()
        videoTrack?.removeSink(videoSink)
        eglRenderer.release()
    }

    /**
     * 水平镜像
     */
    fun setMirror(mirror: Boolean) {
        eglRenderer.setMirror(mirror)
    }

    /**
     * 竖直镜像
     */
    fun setMirrorVertically(mirror: Boolean) {
        eglRenderer.setMirrorVertically(mirror)
    }

    /**
     * 预览videoTrack
     */
    fun showVideoTrack(videoTrack: VideoTrack?) {
        ThreadUtils.checkIsOnMainThread()
        if (this.videoTrack != videoTrack) {
            this.videoTrack?.removeSink(videoSink)
            eglRenderer.clearImage()
        }
        videoTrack?.addSink(videoSink)
        this.videoTrack = videoTrack
    }

    fun addFrameListener(
        listener: EglRenderer.FrameListener,
        scale: Float,
        drawerParam: GlDrawer?
    ) {
        eglRenderer.addFrameListener(listener, scale, drawerParam)
    }

    fun addFrameListener(listener: EglRenderer.FrameListener, scale: Float) {
        eglRenderer.addFrameListener(listener, scale)
    }

    fun removeFrameListener(listener: EglRenderer.FrameListener) {
        eglRenderer.removeFrameListener(listener)
    }

    override fun onFirstFrameRendered() {
        rendererEvents?.onFirstFrameRendered()
    }


    /**
     *
     * @property SCALE_ASPECT_FIT 相当于 fit_center，缩放居中完整显示，会有黑边
     * @property SCALE_ASPECT_FILL 相当于 fit_xy，拉伸铺满整个view
     * @property SCALE_ASPECT_CROP 相当于 center_crop，缩放铺满view并居中裁剪
     */
    enum class ScalingType(val value: Int) {
        SCALE_ASPECT_FIT(0), SCALE_ASPECT_FILL(1), SCALE_ASPECT_CROP(2);
    }

    /**
     * 设置缩放类型
     */
    var scalingType: ScalingType = ScalingType.SCALE_ASPECT_FIT
        set(value) {
            if (value != field) {
                field = value
                updateSurfaceViewSize()
            }
        }

    private fun updateSurfaceViewSize() {
        ThreadUtils.checkIsOnMainThread()
        val constraintSet = ConstraintSet()
        constraintSet.clone(this)
        when (scalingType) {
            ScalingType.SCALE_ASPECT_FIT -> {
                constraintSet.setDimensionRatio(
                    surfaceViewId,
                    "$rotatedFrameWidth:$rotatedFrameHeight"
                )
                val frameAspectRatio = rotatedFrameWidth.toFloat() / rotatedFrameHeight.toFloat()
                eglRenderer.setLayoutAspectRatio(frameAspectRatio)
            }

            ScalingType.SCALE_ASPECT_FILL -> {
                constraintSet.setDimensionRatio(surfaceViewId, null)
                val frameAspectRatio = rotatedFrameWidth.toFloat() / rotatedFrameHeight.toFloat()
                eglRenderer.setLayoutAspectRatio(frameAspectRatio)
            }

            ScalingType.SCALE_ASPECT_CROP -> {
                constraintSet.setDimensionRatio(surfaceViewId, null)
                val layoutAspectRatio = this.width.toFloat() / this.height.toFloat()
                eglRenderer.setLayoutAspectRatio(layoutAspectRatio)
            }
        }
        constraintSet.applyTo(this)
    }

    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
        // 修改surfaceView的大小
        if (Thread.currentThread() == Looper.getMainLooper().thread) {
            updateSurfaceViewSize()
        } else {
            post {
                updateSurfaceViewSize()
            }
        }
        rendererEvents?.onFrameResolutionChanged(videoWidth, videoHeight, rotation)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        eglRenderer.createEglSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        L.d("surfaceChanged: format: " + format + " size: " + width + "x" + height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ThreadUtils.checkIsOnMainThread()
        val completionLatch = CountDownLatch(1)
        Objects.requireNonNull(completionLatch)
        eglRenderer.releaseEglSurface { completionLatch.countDown() }
        ThreadUtils.awaitUninterruptibly(completionLatch)
    }

    fun setFpsReduction(fps: Float) {
        synchronized(layoutLock) { isRenderingPaused = fps == 0.0f }
        eglRenderer.setFpsReduction(fps)
    }

    fun disableFpsReduction() {
        synchronized(layoutLock) { isRenderingPaused = false }
        eglRenderer.disableFpsReduction()
    }

    fun pauseVideo() {
        synchronized(layoutLock) { isRenderingPaused = true }
        eglRenderer.pauseVideo()
    }

    private fun updateFrameDimensionsAndReportEvents(frame: VideoFrame) {
        synchronized(layoutLock) {
            if (!isRenderingPaused) {
                if (!isFirstFrameRendered) {
                    isFirstFrameRendered = true
                    L.d("Reporting first rendered frame.")
                    onFirstFrameRendered()
                }
                if (rotatedFrameWidth != frame.rotatedWidth || rotatedFrameHeight != frame.rotatedHeight || frameRotation != frame.rotation) {
                    L.d(
                        "Reporting frame resolution changed to " + frame.buffer
                            .width + "x" + frame.buffer
                            .height + " with rotation " + frame.rotation
                    )
                    rotatedFrameWidth = frame.rotatedWidth
                    rotatedFrameHeight = frame.rotatedHeight
                    frameRotation = frame.rotation
                    onFrameResolutionChanged(
                        frame.buffer.width,
                        frame.buffer.height,
                        frame.rotation
                    )
                }
            }
        }
    }
}