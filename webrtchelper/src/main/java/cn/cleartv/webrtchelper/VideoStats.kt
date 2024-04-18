package cn.cleartv.webrtchelper

import java.text.DecimalFormat

data class VideoStats(
    var videoStatsTimestamp: Double = 0.0,
    var inputVideoWidth: Int = 0,
    var inputVideoHeight: Int = 0,
    var inputVideoFrameRate: Int = 0,
    var sentVideoWidth: Int = 0,
    var sentVideoHeight: Int = 0,
    var sentVideoFrameRate: Int = 0,
    var videoRtt: Int = 0,
    var videoAvgEncodeMs: Int = 0,
    var videoPacketsLost: Int = 0,
    var videoPacketsSend: Int = 0,
    var recentVideoPacketsLostRate: Double = 0.0,
    var videoBytesSent: Long = 0L,
    var videoBitRate: Double = 0.0,
) {


    fun toFormatString(): String {
        val sb = StringBuilder()
        sb.append("------ 视频 ------\n")
        sb.append("输入分辨率：$inputVideoWidth*$inputVideoHeight\n")
        sb.append("发送分辨率：$sentVideoWidth*$sentVideoHeight\n")
        sb.append("输入帧率：$inputVideoFrameRate\n")
        sb.append("发送帧率：$sentVideoFrameRate\n")
        sb.append("视频平均编码时长：${videoAvgEncodeMs}ms\n")
        sb.append("视频码率：${DecimalFormat("#.00").format(videoBitRate * 8 / 1024.0)}Kbps\n")
        sb.append("视频RTT：$videoRtt\n")
        sb.append("视频丢包率：${recentVideoPacketsLostRate}%\n")
        return sb.toString()
    }
}