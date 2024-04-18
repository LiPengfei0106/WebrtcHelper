package cn.cleartv.webrtchelper

import java.text.DecimalFormat

data class AudioStats(
    var audioStatsTimestamp: Double = 0.0,
    var audioBitRate: Double = 0.0,
    var audioBytesSent: Long = 0L,
    var audioPacketsLost: Int = 0,
    var audioPacketsSend: Int = 0,
    var recentAudioPacketsLostRate: Double = 0.0,
    var audioRtt: Int = 0,
    var audioLevel: Double = 0.0,
    var totalEnergy: Double = 0.0,
    var totalDuration: Double = 0.0,
) {

    fun toFormatString(): String {
        val sb = StringBuilder()
        sb.append("------ 音频 ------\n")
        sb.append("音量输入大小：$audioLevel\n")
        sb.append("音频码率：${DecimalFormat("#.00").format(audioBitRate * 8 / 1024.0)}Kbps\n")
        sb.append("音频RTT：$audioRtt\n")
        sb.append("音频丢包率：${recentAudioPacketsLostRate}%\n")
        return sb.toString()
    }
}