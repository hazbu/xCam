package com.hazbu.xcam.xposed

import com.hazbu.xcam.hooks.*
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectors(private val module: XCamModule) {

    private val cameraHook = CameraHook(module)
    private val camera2Hook = Camera2Hook(module)
    private val captureHook = CaptureHook(module)
    private val imageReaderHook = ImageReaderHook(module)
    private val webRtcHook = WebRtcHook(module)
    private val audioHook = AudioHook(module)

    fun install(param: XposedModuleInterface.PackageReadyParam) {
        cameraHook.install(param)
        camera2Hook.install(param)
        captureHook.install(param)
        imageReaderHook.install(param)
        webRtcHook.install(param)
        audioHook.install(param)
        
        module.logInit("[+] Core camera & audio hooks installed successfully")
    }
}
