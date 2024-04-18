package cn.cleartv.webrtchelper

import android.graphics.Bitmap
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.opengl.GLES20
import android.util.Log
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.nio.IntBuffer

internal object WebRTCUtils {

    fun VideoFrame.setRotation(rotation: Int) {
        try {
            val cls = VideoFrame::class.java
            val method = cls.getDeclaredField("rotation")
            method.isAccessible = true
            method.setInt(this, rotation)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun CameraVideoCapturer.getCameraName(): String? {
        try {
            val cls = Class.forName("org.webrtc.CameraCapturer")
            val method = cls.getDeclaredMethod("getCameraName")
            method.isAccessible = true
            return method.invoke(this)?.toString()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun MediaStream.isExists(): Boolean {
        try {
            val cls = MediaStream::class.java
            val field = cls.getDeclaredField("nativeStream")
            field.isAccessible = true
            return field.getLong(this) != 0L
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    fun MediaStreamTrack.isExists(): Boolean {
        try {
            val cls = MediaStreamTrack::class.java
            val field = cls.getDeclaredField("nativeTrack")
            field.isAccessible = true
            return field.getLong(this) != 0L
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    @Synchronized
    fun videoFrame2Bitmap(frame: VideoFrame): Bitmap? {
        return YuvFrame(frame).bitmap
    }

    @Synchronized
    fun bitmap2VideoFrame(bitmap: Bitmap, timestampNs: Long): VideoFrame? {
        return null
    }

    private fun textureToBitmap(texIn: Int, width: Int, height: Int): Bitmap? {
        val oldFboId = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, IntBuffer.wrap(oldFboId))
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        val framebufferId = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
        val renderbuffers = IntArray(1)
        GLES20.glGenRenderbuffers(1, renderbuffers, 0)
        val renderId = renderbuffers[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderId)
        GLES20.glRenderbufferStorage(
            GLES20.GL_RENDERBUFFER,
            GLES20.GL_DEPTH_COMPONENT16,
            width,
            height
        )
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texIn,
            0
        )
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_DEPTH_ATTACHMENT,
            GLES20.GL_RENDERBUFFER,
            renderId
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
        }
        val rgbaBuf = ByteBuffer.allocateDirect(width * height * 4)
        rgbaBuf.position(0)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBuf)
        rgbaBuf.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(rgbaBuf)

        // final Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        Log.e("LPFTEST", "textureToBitmap")
        GLES20.glDeleteRenderbuffers(1, IntBuffer.wrap(framebuffers))
//        GLES20.glDeleteFramebuffers(1, IntBuffer.allocate(framebufferId))
        return bitmap
    }

    private fun getCameraInfo(index: Int): CameraInfo? {
        val info = CameraInfo()
        return try {
            Camera.getCameraInfo(index, info)
            info
        } catch (var3: Exception) {
            null
        }
    }

    fun getDeviceName(index: Int): String? {
        val info = getCameraInfo(index)
        return if (info == null) {
            null
        } else {
            val facing = if (info.facing == 1) "front" else "back"
            "Camera " + index + ", Facing " + facing + ", Orientation " + info.orientation
        }
    }

    fun getDeviceNames(): Array<String> {
        val namesList: ArrayList<String> = ArrayList()
        for (i in 0 until Camera.getNumberOfCameras()) {
            val name = getDeviceName(i)
            if (name != null) {
                namesList.add(name)
            }
        }
        return namesList.toArray(arrayOfNulls<String>(namesList.size))
    }

}