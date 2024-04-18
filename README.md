# WebrtcHelper
使用kotlin和协程简化Android端WebRTC的使用流程:

## WebrtcHelper
```kotlin
WebRTCHelper.init() //初始化
WebRTCHelper.enableLog() // 开启日志
WebRTCHelper.pushStream() // 推流
WebRTCHelper.pullStream() // 拉流
WebRTCHelper.addIceCandidate() // 添加ice
WebRTCHelper.releaseConnection() // 释放连接
WebRTCHelper.releaseAllConnection() // 释放所有连接
WebRTCHelper.getAudioStats() // 获取音频推流状态
WebRTCHelper.getVideoStats() // 获取视频推流状态
```

## VideoSourceHelper
```kotlin
initScreenCapturer() //初始化屏幕共享
initCameraCapturer() //初始化摄像头
customRotation // 自定义画面旋转角度
startCapture() //开始捕获画面
stopCapture() //停止捕获画面
switchCamera() //停止捕获画面
getOrCreateVideoTrack() //创建VideoTrack
removeVideoTrack() //移除VideoTrack
release() //释放所有资源
```

## AudioSourceHelper
```kotlin
getOrCreateAudioTrack() //创建AudioTrack
removeAudioTrack() //移除AudioTrack
release() //释放所有资源
```

## AudioDeviceManager
```kotlin
AudioDeviceManager.addInputSamplesInterceptor() //增加输入音频拦截器
AudioDeviceManager.removeInputSamplesInterceptor() //移除输入音频拦截器
AudioDeviceManager.addOutputSamplesInterceptor() //增加输出音频拦截器
AudioDeviceManager.removeOutputSamplesInterceptor() //移除输出音频拦截器
AudioDeviceManager.isMicrophoneMute //静音麦克风
AudioDeviceManager.isSpeakerMute //静音扬声器
AudioDeviceManager.setPreferredInputAudioDevice() //设置优先使用的音频输入设备
```


## VideoTrackView
```kotlin
showVideoTrack() //预览videoTrack
addFrameListener() //增加监听帧画面
removeFrameListener() //移除监听帧画面
scalingType // 设置画面缩放模式
setMirror() // 水平镜像
setMirrorVertically() // 竖直镜像
AudioDeviceManager.addOutputSamplesInterceptor() //增加输出音频拦截器
AudioDeviceManager.removeOutputSamplesInterceptor() //移除输出音频拦截器
AudioDeviceManager.isMicrophoneMute //静音麦克风
AudioDeviceManager.isSpeakerMute //静音扬声器
AudioDeviceManager.setPreferredInputAudioDevice() //设置优先使用的音频输入设备
```

### 使用示例

```kotlin
    val videoSourceHelper: VideoSourceHelper by lazy { VideoSourceHelper().apply { initCameraCapturer() } }
    val audioSourceHelper: AudioSourceHelper by lazy { AudioSourceHelper() }

    /**
     * 本地推流并拉流
     */
    fun localTestPushStart(localSurfaceView: VideoTrackView, remoteSurfaceView: VideoTrackView) {
        // 释放资源
        WebRTCHelper.releaseAllConnection()
        viewModelScope.launch {
            val videoTrack = videoSourceHelper.getOrCreateVideoTrack("localVideo", false) // 创建videoTrack
            val audioTrack = audioSourceHelper.getOrCreateAudioTrack("localAudio") // 创建audioTrack
            if (!videoSourceHelper.isCapturerStarted) {
                videoSourceHelper.startCapture(1920, 1080, 30) // 开始捕获画面
            }
            localSurfaceView.showVideoTrack(videoTrack) // 预览本地画面
            // 开始推流
            WebRTCHelper.pushStream(
                "pusher", // 设置流ID，自定义
                videoTrack,
                audioTrack,
                onSdp = { sdpOffer -> // 生成sdpOffer，这里需要和拉流端交互sdp，获取对方的sdpAnwser
                    // 拉流
                    val sdpAnswer = WebRTCHelper.pullStream(
                        "puller", // 设置流ID，自定义
                        sdpOffer, // 对方生成的offer
                        onAudioConnected = { // 音频连接成功

                        },
                        onVideoConnected = { // 视频连接成功
                            launch(Dispatchers.Main) {
                                remoteSurfaceView.showVideoTrack(it) // 预览收到的画面
                            }
                        },
                        onIce = {
                            WebRTCHelper.addIceCandidate("pusher", it) // 将拉流生成的ice设置到推流上
                        },
                        onDisconnect = { // 连接断开

                        })
                    return@pushStream sdpAnswer // 返回sdpAnswer
                },
                onIce = {
                    // 发流端的sdp中会携带ice，这里可以不用给拉流端设置
                    WebRTCHelper.addIceCandidate("puller", it)
                },
                onDisconnect = {
                    videoSourceHelper.stopCapture() // 停止捕获画面
                })
        }
    }
```

### 演示画面

![演示画面](screenshort/Record_2024-04-18-18-59-16.gif)

