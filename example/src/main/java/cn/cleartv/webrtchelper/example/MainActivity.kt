package cn.cleartv.webrtchelper.example

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.cleartv.webrtchelper.VideoTrackView
import cn.cleartv.webrtchelper.WebRTCHelper

class MainActivity : AppCompatActivity() {

    val viewModel: MainViewModel by viewModels()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                ), 100
            )
        }

        val localSurface = findViewById<VideoTrackView>(R.id.localSurface)
        val remoteSurface = findViewById<VideoTrackView>(R.id.remoteSurface)
        val localInfo = findViewById<TextView>(R.id.localInfo)

        localSurface.setOnClickListener {
            localInfo.text =
                (WebRTCHelper.getAudioStats("pushStartTestPusher")
                    ?: WebRTCHelper.getAudioStats("pullStartTestPusher"))?.toFormatString() +
                        (WebRTCHelper.getVideoStats("pushStartTestPusher")
                            ?: WebRTCHelper.getVideoStats("pullStartTestPusher"))?.toFormatString()
        }

        val btnPushStart = findViewById<Button>(R.id.btnPushStart)
        val btnPullStart = findViewById<Button>(R.id.btnPullStart)
        val btnScaleFit = findViewById<Button>(R.id.btnScaleFit)
        val btnScaleFill = findViewById<Button>(R.id.btnScaleFill)
        val btnScaleCrop = findViewById<Button>(R.id.btnScaleCrop)
        val btnSwitchCamera = findViewById<Button>(R.id.btnSwitchCamera)
        val btnRotationCamera = findViewById<Button>(R.id.btnRotationCamera)
        val swMirror = findViewById<SwitchCompat>(R.id.swMirror)
        val swMirrorVertically = findViewById<SwitchCompat>(R.id.swMirrorVertically)

        btnScaleFit.setOnClickListener {
            localSurface.setScalingType(VideoTrackView.ScalingType.SCALE_ASPECT_FIT)
            remoteSurface.setScalingType(VideoTrackView.ScalingType.SCALE_ASPECT_FIT)
        }
        btnScaleFill.setOnClickListener {
            localSurface.setScalingType(VideoTrackView.ScalingType.SCALE_ASPECT_FILL)
            remoteSurface.setScalingType(VideoTrackView.ScalingType.SCALE_ASPECT_FILL)
        }
        btnScaleCrop.setOnClickListener {
            localSurface.setScalingType(VideoTrackView.ScalingType.SCALE_ASPECT_CROP)
            remoteSurface.setScalingType(VideoTrackView.ScalingType.SCALE_ASPECT_CROP)
        }

        swMirror.setOnCheckedChangeListener { buttonView, isChecked ->
            localSurface.setMirror(isChecked)
            remoteSurface.setMirror(isChecked)
        }
        swMirrorVertically.setOnCheckedChangeListener { buttonView, isChecked ->
            localSurface.setMirrorVertically(isChecked)
            remoteSurface.setMirrorVertically(isChecked)
        }

        btnSwitchCamera.setOnClickListener {
            viewModel.videoSourceHelper.switchCamera()
        }
        btnRotationCamera.setOnClickListener {
            viewModel.videoSourceHelper.customRotation += 90
        }

        btnPushStart.setOnClickListener {
            viewModel.localTestPushStart(localSurface, remoteSurface)
        }

        btnPullStart.setOnClickListener {
            viewModel.localTestPullStart(localSurface, remoteSurface)
        }
    }
}