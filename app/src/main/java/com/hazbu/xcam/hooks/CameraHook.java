package com.hazbu.xcam.hooks;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.hazbu.xcam.xposed.XCamModule;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for the original Camera API (android.hardware.Camera).
 * Consolidated all Legacy Camera logic including Renderer hooks.
 */
public class CameraHook {
    private static final long PREVIEW_RESTART_DELAY_MS = 100;

    private final XCamModule module;
    private final Handler mainHandler;
    private Surface viewfinderSurface;

    public CameraHook(XCamModule module) {
        this.module = module;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera API (Legacy)");
            hookCameraParameters(param);
            hookViewfinder(param);
            hookTakePicture(param);
            hookLegacyMethod1(param);
        } catch (Throwable t) {
            module.logHook("[!] FAILED to initialize Camera API hooks: " + t.getMessage());
        }
    }

    private void hookLegacyMethod1(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi") Class<?> legacyDeviceClass = param.getClassLoader().loadClass("android.hardware.camera2.legacy.LegacyCameraDevice");
            for (Method method : legacyDeviceClass.getDeclaredMethods()) {
                if (method.getName().equals("produceFrame") && method.getParameterTypes().length == 5) {
                    module.hook(method).intercept(chain -> {
                        int format = (int) chain.getArgs().get(4);
                        if (format == 0x21) { // JPEG
                            int width = (int) chain.getArgs().get(2);
                            int height = (int) chain.getArgs().get(3);
                            byte[] replacement = module.handleCapture(width, height);
                            if (replacement != null) {
                                Object[] newArgs = chain.getArgs().toArray();
                                newArgs[1] = replacement;
                                return chain.proceed(newArgs);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: LegacyCameraDevice#produceFrame");
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookViewfinder(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method setPreviewDisplay = Camera.class.getDeclaredMethod("setPreviewDisplay", SurfaceHolder.class);
            module.hook(setPreviewDisplay).intercept(chain -> {
                SurfaceHolder holder = (SurfaceHolder) chain.getArgs().get(0);
                if (holder != null && holder.getSurface() != null) viewfinderSurface = holder.getSurface();
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewDisplay");

            Method setPreviewTexture = Camera.class.getDeclaredMethod("setPreviewTexture", SurfaceTexture.class);
            module.hook(setPreviewTexture).intercept(chain -> {
                SurfaceTexture texture = (SurfaceTexture) chain.getArgs().get(0);
                if (texture != null) viewfinderSurface = new Surface(texture);
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewTexture");

            Method startPreview = Camera.class.getDeclaredMethod("startPreview");
            module.hook(startPreview).intercept(chain -> {
                Object result = chain.proceed();
                if (module.getMediaPath() != null && viewfinderSurface != null && viewfinderSurface.isValid()) {
                    module.recordPipelineNode("Camera1(Preview)");
                    module.registerPreviewSurface(viewfinderSurface);
                }
                return result;
            });
            module.logHook("[+] Hooked: Camera#startPreview");

            Method stopPreview = Camera.class.getDeclaredMethod("stopPreview");
            module.hook(stopPreview).intercept(chain -> {
                Object result = chain.proceed();
                module.stopEngine();
                return result;
            });
            module.logHook("[+] Hooked: Camera#stopPreview");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook viewfinder: " + t.getMessage());
        }
    }

    private void hookCameraParameters(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> paramsClass = Camera.Parameters.class;
            Method setPreviewSize = paramsClass.getDeclaredMethod("setPreviewSize", int.class, int.class);
            module.hook(setPreviewSize).intercept(chain -> {
                module.logHook("[*] Legacy Activity: setPreviewSize " + chain.getArgs().get(0) + "x" + chain.getArgs().get(1));
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera.Parameters#setPreviewSize");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook Camera.Parameters: " + t.getMessage());
        }
    }

    private void hookTakePicture(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method m : Camera.class.getDeclaredMethods()) {
                if (m.getName().equals("takePicture") && m.getParameterTypes().length == 4) {
                    module.hook(m).intercept(chain -> {
                        if (module.getMediaPath() == null) return chain.proceed();
                        Camera camera = (Camera) chain.getThisObject();
                        PictureCallback jpegCallback = (PictureCallback) chain.getArgs().get(3);
                        if (jpegCallback == null) return chain.proceed();

                        module.triggerCaptureState();
                        Camera.Parameters params = camera.getParameters();
                        byte[] imageData = module.handleCapture(params.getPictureSize().width, params.getPictureSize().height);

                        if (imageData == null) return chain.proceed();

                        module.recordPipelineNode("Camera1(takePicture)");
                        module.reportPipelineCapture(params.getPictureSize().width, params.getPictureSize().height, imageData);

                        final ShutterCallback shutterCallback = (ShutterCallback) chain.getArgs().get(0);
                        final PictureCallback rawCallback = (PictureCallback) chain.getArgs().get(1);
                        final PictureCallback postviewCallback = (PictureCallback) chain.getArgs().get(2);

                        Runnable deliver = () -> {
                            try {
                                if (shutterCallback != null) shutterCallback.onShutter();
                                if (rawCallback != null) rawCallback.onPictureTaken(null, camera);
                                if (postviewCallback != null) postviewCallback.onPictureTaken(null, camera);
                                jpegCallback.onPictureTaken(imageData, camera);
                                mainHandler.postDelayed(() -> { try { camera.startPreview(); } catch (Throwable ignored) {} }, PREVIEW_RESTART_DELAY_MS);
                            } catch (Throwable ignored) {}
                        };
                        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run(); else mainHandler.post(deliver);
                        return null;
                    });
                    module.logHook("[+] Hooked: Camera#takePicture (Legacy)");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook takePicture: " + t.getMessage());
        }
    }
}

