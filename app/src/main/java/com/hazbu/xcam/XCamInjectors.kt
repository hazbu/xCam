package com.hazbu.xcam

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.view.Surface
import android.view.SurfaceHolder
import io.github.libxposed.api.XposedModuleInterface

class XCamInjectors(private val module: XCamModule) {

    fun installLegacyHooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val rendererClass = param.classLoader.loadClass("android.hardware.camera2.legacy.SurfaceTextureRenderer")
            val drawFrame = rendererClass.getDeclaredMethod("drawFrame", SurfaceTexture::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            module.hook(drawFrame).intercept { chain ->
                val width = (chain.args[1] as? Number)?.toInt() ?: 0
                val height = (chain.args[2] as? Number)?.toInt() ?: 0
                if (module.handlePreview(width, height)) null else chain.proceed()
            }
        } catch (e: Throwable) {}
    }

    fun installCamera1Hooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val cameraClass = param.classLoader.loadClass("android.hardware.Camera")
            val setPreviewTexture = cameraClass.getDeclaredMethod("setPreviewTexture", SurfaceTexture::class.java)
            module.hook(setPreviewTexture).intercept { chain ->
                val originalST = chain.args[0] as? SurfaceTexture
                if (originalST != null && module.mediaPath != null) {
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = module.getDummyST()
                    module.handleCamera1Preview(originalST)
                    return@intercept chain.proceed(newArgs)
                }
                chain.proceed()
            }
        } catch (e: Throwable) {}
    }

    fun installUniversalCaptureHooks(param: XposedModuleInterface.PackageReadyParam) {
        // 1. Still Capture Trigger
        try {
            val sessionClass = param.classLoader.loadClass("android.hardware.camera2.impl.CameraCaptureSessionImpl")
            val capture = sessionClass.getDeclaredMethods().find { it.name == "capture" && it.parameterTypes.size >= 2 }
            capture?.let { method ->
                module.hook(method).intercept { chain ->
                    val request = chain.args[0] as? CaptureRequest
                    if (request != null) {
                        val template = try { request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) } catch (e: Exception) { -1 }
                        if (template == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                            module.printLog("Capture Intent Detected")
                            module.triggerCaptureState()
                        }
                    }
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {}

        // 2. The Hunter: Hook ALL decodeByteArray variants
        try {
            val bfClass = BitmapFactory::class.java
            bfClass.getDeclaredMethods().filter { it.name == "decodeByteArray" }.forEach { method ->
                module.hook(method).intercept { chain ->
                    if (module.isCapturingState()) {
                        module.printLog("Hunter: Success! Intercepted decodeByteArray")
                        val replacement = module.handleCapture(1280, 1280)
                        if (replacement != null) {
                            val newArgs = chain.args.toTypedArray()
                            newArgs[0] = replacement
                            if (newArgs.size >= 3) newArgs[2] = replacement.size
                            return@intercept chain.proceed(newArgs)
                        }
                    }
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {}
    }

    fun installAndroid16UIHooks(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val textureViewClass = param.classLoader.loadClass("android.view.TextureView")
            val setSurfaceTexture = textureViewClass.getDeclaredMethod("setSurfaceTexture", SurfaceTexture::class.java)
            module.hook(setSurfaceTexture).intercept { chain ->
                val st = chain.args[0] as? SurfaceTexture
                if (st != null) module.handleCamera1Preview(st)
                chain.proceed()
            }

            val surfaceViewClass = param.classLoader.loadClass("android.view.SurfaceView")
            val getHolder = surfaceViewClass.getDeclaredMethod("getHolder")
            module.hook(getHolder).intercept { chain ->
                val holder = chain.proceed() as? SurfaceHolder
                holder?.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { module.handleSurfaceViewPreview(h) }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) { module.handleSurfaceViewPreview(h) }
                    override fun surfaceDestroyed(h: SurfaceHolder) { module.stopCamera1Engine() }
                })
                holder
            }
        } catch (e: Throwable) {}
    }
}
