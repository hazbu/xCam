package com.hazbu.xcam.hooks;

import android.annotation.SuppressLint;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.view.Surface;

import com.hazbu.xcam.xposed.XCamModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for the Camera2 API (android.hardware.camera2) and ImageReader.
 * Consolidated all Camera2 logic including Hijacking and Surgical Diversion.
 */
public class Camera2Hook {
    private final XCamModule module;

    public Camera2Hook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera2 API");
            hookDiscovery(param);
            hookModernHijack(param);
            hookSurgicalDiverter(param);
            hookCleanup(param);
            module.logHook("[+] Camera2 API hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize Camera2 API hooks: " + t.getMessage());
        }
    }

    private void hookDiscovery(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi") Class<?> cameraDeviceClass = param.getClassLoader().loadClass("android.hardware.camera2.impl.CameraDeviceImpl");
            for (Method method : cameraDeviceClass.getDeclaredMethods()) {
                if (method.getName().startsWith("createCaptureSession")) {
                    module.hook(method).intercept(chain -> {
                        module.logHook("[*] Activity: CameraDeviceImpl#" + method.getName());
                        module.incrementSessionGeneration();
                        module.clearPreviewSurfaces();
                        for (Object arg : chain.getArgs()) {
                            inspectDiscoveryArgument(arg);
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: CameraDeviceImpl#" + method.getName());
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Discovery hook failed: " + t.getMessage());
        }
    }

    private void inspectDiscoveryArgument(Object arg) {
        if (arg instanceof Surface) {
            Surface s = (Surface) arg;
            module.logSessionOutput(s);
            String sStr = s.toString();
            if (sStr.contains("SurfaceTexture") || sStr.contains("SurfaceView") || sStr.contains("BLAST") || sStr.contains("Surface")) {
                module.registerPreviewSurface(s);
            }
        } else if (arg instanceof OutputConfiguration) {
            Surface s = ((OutputConfiguration) arg).getSurface();
            if (s != null) inspectDiscoveryArgument(s);
        } else if (arg instanceof Collection) {
            for (Object item : (Collection<?>) arg) inspectDiscoveryArgument(item);
        }
    }

    private void hookModernHijack(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> ocClass = param.getClassLoader().loadClass("android.hardware.camera2.params.OutputConfiguration");
            for (Constructor<?> constructor : ocClass.getDeclaredConstructors()) {
                module.hook(constructor).intercept(chain -> {
                    if (module.getMediaPath() == null) return chain.proceed();

                    Surface surface = null;
                    int surfaceIndex = -1;
                    List<Object> args = chain.getArgs();
                    for (int i = 0; i < args.size(); i++) {
                        if (args.get(i) instanceof Surface && ((Surface) args.get(i)).isValid()) {
                            surface = (Surface) args.get(i);
                            surfaceIndex = i;
                            break;
                        }
                    }

                    if (surface != null) {
                        String sStr = surface.toString();
                        boolean isPreview = sStr.contains("SurfaceTexture") || sStr.contains("SurfaceView") || sStr.contains("BLAST") || sStr.contains("Surface");

                        if (isPreview) {
                            module.registerPreviewSurface(surface);
                        }

                        if (isPreview && !module.getPreviewSwapped()) {
                            String surfaceType = sStr.contains("SurfaceView") ? "SurfaceView" : "SurfaceTexture";
                            module.recordPipelineNode("Camera2(" + surfaceType + ")");
                            module.logHook("[!] Action: Hijacking Preview Surface via OutputConfiguration (" + sStr + ")");
                            module.setPreviewSwapped(true);
                            module.handleModernPreview(surface);

                            Object[] newArgs = chain.getArgs().toArray();
                            newArgs[surfaceIndex] = module.getDummySurface();
                            return chain.proceed(newArgs);
                        }
                    }
                    return chain.proceed();
                });
            }
            module.logHook("[+] Hooked: OutputConfiguration Constructor");
        } catch (Throwable t) {
            module.logHook("[!] Modern Hijack hook failed: " + t.getMessage());
        }
    }

    private void hookSurgicalDiverter(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method addTarget = CaptureRequest.Builder.class.getDeclaredMethod("addTarget", Surface.class);
            module.hook(addTarget).intercept(chain -> {
                Surface surface = (Surface) chain.getArgs().get(0);
                if (surface != null && module.getMediaPath() != null) {
                    CaptureRequest.Builder builder = (CaptureRequest.Builder) chain.getThisObject();
                    Integer intent = null;
                    try { intent = builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT); } catch (Throwable ignored) {}

                    if (intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                        module.logHook("[*] Activity: addTarget [STILL_CAPTURE]");
                        module.recordPipelineNode("Camera2(StillCapture)");
                        module.triggerCaptureState();
                        return chain.proceed();
                    }

                    if (intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD) {
                        module.logHook("[*] Activity: addTarget [VIDEO_RECORD]");
                        module.recordPipelineNode("Camera2(VideoRecord)");
                        return chain.proceed();
                    }

                    if (intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT) {
                        module.logHook("[*] Activity: addTarget [VIDEO_SNAPSHOT]");
                        module.recordPipelineNode("Camera2(VideoSnapshot)");
                        return chain.proceed();
                    }

                    if (module.isPreviewSurface(surface)) {
                        Object[] newArgs = new Object[] { module.getDummySurface() };
                        return chain.proceed(newArgs);
                    }
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: CaptureRequest.Builder#addTarget");
        } catch (Throwable t) {
            module.logHook("[!] Surgical Diverter hook failed: " + t.getMessage());
        }
    }

    private void hookCleanup(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> cameraDeviceClass = param.getClassLoader().loadClass("android.hardware.camera2.impl.CameraDeviceImpl");
            Method deviceClose = cameraDeviceClass.getDeclaredMethod("close");
            module.hook(deviceClose).intercept(chain -> {
                module.logHook("[*] Activity: CameraDeviceImpl#close");
                module.stopEngine();
                return chain.proceed();
            });

            Class<?> sessionClass = param.getClassLoader().loadClass("android.hardware.camera2.impl.CameraCaptureSessionImpl");
            Method sessionClose = sessionClass.getDeclaredMethod("close");
            module.hook(sessionClose).intercept(chain -> {
                module.logHook("[*] Activity: CameraCaptureSessionImpl#close");
                module.stopEngine();
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera2 Cleanup methods");
        } catch (Throwable t) {
            module.logHook("[!] Cleanup hook failed: " + t.getMessage());
        }
    }
}

