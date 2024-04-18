package cn.cleartv.webrtchelper

import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import java.util.concurrent.ConcurrentHashMap

class AudioSourceHelper(
    audioConstraints: MediaConstraints = MediaConstraints(),
    noiseSuppression: Boolean = true,
    autoGainControl: Boolean = true,
    echoCancellation: Boolean = true,
    highpassFilter: Boolean = true,
) {

    private val audioTrackMap: ConcurrentHashMap<String, AudioTrack> = ConcurrentHashMap()

    val audioSource: AudioSource = WebRTCHelper.factory.createAudioSource(audioConstraints.apply {
        mandatory.add(
            MediaConstraints.KeyValuePair(
                "googHighpassFilter",
                highpassFilter.toString()
            )
        )
//        mandatory.add(
//            MediaConstraints.KeyValuePair(
//                "googAudioMirroring",
//                highpassFilter.toString()
//            )
//        )

        mandatory.add(
            MediaConstraints.KeyValuePair(
                "googNoiseSuppression",
                noiseSuppression.toString()
            )
        )
        mandatory.add(
            MediaConstraints.KeyValuePair(
                "googNoiseSuppression2",
                noiseSuppression.toString()
            )
        )

        mandatory.add(
            MediaConstraints.KeyValuePair(
                "googAutoGainControl",
                autoGainControl.toString()
            )
        )
        mandatory.add(
            MediaConstraints.KeyValuePair(
                "googAutoGainControl2",
                autoGainControl.toString()
            )
        )

        mandatory.add(
            MediaConstraints.KeyValuePair(
                "echoCancellation",
                echoCancellation.toString()
            )
        )
        mandatory.add(
            MediaConstraints.KeyValuePair(
                "googEchoCancellation",
                echoCancellation.toString()
            )
        )
        mandatory.add(
            MediaConstraints.KeyValuePair(
                "googEchoCancellation2",
                echoCancellation.toString()
            )
        )
        mandatory.add(
            MediaConstraints.KeyValuePair(
                "googDAEchoCancellation",
                echoCancellation.toString()
            )
        )
    })


    fun getOrCreateAudioTrack(trackId: String): AudioTrack {
        return audioTrackMap.getOrPut(trackId) {
            WebRTCHelper.factory.createAudioTrack(trackId, audioSource)
        }
    }

    fun removeAudioTrack(trackId: String) {
        audioTrackMap.remove(trackId)?.dispose()
    }

    fun release(){
        audioSource.dispose()
        audioTrackMap.clear()
    }

}