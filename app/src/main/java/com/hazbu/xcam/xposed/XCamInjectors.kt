package com.hazbu.xcam.xposed

import com.hazbu.xcam.hooks.*
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectors(private val module: XCamModule) {

    val cameraHook = CameraHook(module)
    val camera2Hook = Camera2Hook(module)
    val imageReaderHook = ImageReaderHook(module)
    val captureHook = CaptureHook(module, imageReaderHook)
    val webRtcHook = WebRtcHook(module)
    val audioHook = AudioHook(module)

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
