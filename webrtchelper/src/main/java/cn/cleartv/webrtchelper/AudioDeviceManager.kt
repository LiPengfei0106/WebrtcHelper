@file:Suppress("DEPRECATION")

package cn.cleartv.webrtchelper

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import androidx.annotation.RequiresApi
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback

object AudioDeviceManager {
    // 音频模块
    @SuppressLint("StaticFieldLeak")
    private var audioDeviceModule: AudioDeviceModule? = null
    private val inputAudioCallbacks = hashMapOf<Int, SamplesReadyCallback>()
    private val outputAudioCallbacks = hashMapOf<Int, SamplesReadyCallback>()

    fun addInputSamplesInterceptor(id: Int, callback: SamplesReadyCallback) {
        inputAudioCallbacks[id] = callback
    }

    fun removeInputSamplesInterceptor(id: Int) {
        inputAudioCallbacks.remove(id)
    }

    fun addOutputSamplesInterceptor(id: Int, callback: SamplesReadyCallback) {
        outputAudioCallbacks[id] = callback
    }

    fun removeOutputSamplesInterceptor(id: Int) {
        outputAudioCallbacks.remove(id)
    }

    @Synchronized
    fun initializeAudioDeviceModule(context: Context): AudioDeviceModule {
        return audioDeviceModule?: JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() {
                    (audioDeviceModule as? JavaAudioDeviceModule)?.setAudioTrackSamplesReadyCallback { audioSamples ->
                        for (ck in outputAudioCallbacks.values) {
                            ck.onWebRtcAudioRecordSamplesReady(audioSamples)
                        }
                    }
                }

                override fun onWebRtcAudioTrackStop() {
                }

            })
            .setSamplesReadyCallback { audioSamples ->
                for (callback in inputAudioCallbacks.values) {
                    callback.onWebRtcAudioRecordSamplesReady(audioSamples)
                }
            }
            .createAudioDeviceModule().apply {
                audioDeviceModule = this
            }
    }

    @Synchronized
    fun releaseAudioDeviceModule() {
        L.i("Release AudioDeviceModule")
        inputAudioCallbacks.clear()
        outputAudioCallbacks.clear()
        audioDeviceModule?.release()
        audioDeviceModule = null
    }

    /**
     * 麦克风静音
     */
    var isMicrophoneMute = false
        set(value) {
            audioDeviceModule?.setMicrophoneMute(value)
            field = value
        }


    // 1.0.32006 版本支持，但是android版本最低为5.0
    @RequiresApi(23)
    fun setPreferredInputAudioDevice(preferredInputDevice: AudioDeviceInfo?) {
        (audioDeviceModule as? JavaAudioDeviceModule)?.setPreferredInputDevice(preferredInputDevice)
    }

    /**
     * 扬声器是否静音
     */
    var isSpeakerMute = false
        set(value) {
            audioDeviceModule?.setSpeakerMute(value)
            field = value
        }


    fun resetAudio() {
        isMicrophoneMute = false
        isSpeakerMute = false
    }

    /**
     * 回调音频输入数据
     * 反射，替换[WebRtcAudioTrack.audioTrack]，使用[AudioTrackInterceptor]
     * 其中要把[WebRtcAudioTrack.audioTrack]赋值给[AudioTrackInterceptor.originalTrack]，
     * [AudioTrackInterceptor]只是一个壳，具体实现是[AudioTrackInterceptor.originalTrack]
     *
     * @param samplesReadyCallback 回调接口 ，原始pcm数据
     */
    fun JavaAudioDeviceModule.setAudioTrackSamplesReadyCallback(samplesReadyCallback: JavaAudioDeviceModule.SamplesReadyCallback) {
        val deviceModuleClass = this::class.java
        val audioOutputField = deviceModuleClass.getDeclaredField("audioOutput")
        audioOutputField.isAccessible = true
        val webRtcAudioTrack = audioOutputField.get(this)// as? org.webrtc.audio.WebRtcAudioTrack
        webRtcAudioTrack ?: return
        val audioTrackClass = webRtcAudioTrack::class.java
        val audioTrackFiled = audioTrackClass.getDeclaredField("audioTrack")
        audioTrackFiled.isAccessible = true
        val audioTrack = audioTrackFiled.get(webRtcAudioTrack) as? AudioTrack
        audioTrack ?: return
        val interceptor = AudioTrackInterceptor(audioTrack, samplesReadyCallback)
        audioTrackFiled.set(webRtcAudioTrack, interceptor)
    }
}