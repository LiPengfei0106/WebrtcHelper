package cn.cleartv.webrtchelper.example

import android.app.Application
import cn.cleartv.webrtchelper.WebRTCHelper

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        WebRTCHelper.init(this)
        WebRTCHelper.enableLog(true)
    }
}