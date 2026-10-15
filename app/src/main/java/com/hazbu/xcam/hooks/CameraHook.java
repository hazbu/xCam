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
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for the original Camera API (android.hardware.Camera).
 * Consolidated all Legacy Camera logic including Renderer hooks.
 */
public class CameraHook {
    private static final long PREVIEW_RESTART_DELAY_MS = 100;

    private final XCamModule module;
    private final Handler mainHandler;
    private final Set<String> hookedPreviewCallbackClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Surface viewfinderSurface;
    private SurfaceTexture dummySurfaceTexture;

    public CameraHook(XCamModule module) {
        this.module = module;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera API (Legacy)");
            hookCameraParameters(param);
            hookViewfinder(param);
            hookPreviewCallbacks(param);
            hookCameraEventHandler(param);
            hookDisplayOrientation(param);
            hookRelease(param);
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
                        if (format == 0x21) {
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
                if (holder != null && holder.getSurface() != null && module.getMediaPath() != null) {
                    viewfinderSurface = holder.getSurface();
                    module.registerPreviewSurface(viewfinderSurface);
                    if (dummySurfaceTexture == null) {
                        dummySurfaceTexture = new SurfaceTexture(10);
                    }
                    try {
                        Camera camera = (Camera) chain.getThisObject();
                        camera.setPreviewTexture(dummySurfaceTexture);
                        module.logHook("[+] Hijacked Camera#setPreviewDisplay -> diverted camera to dummy texture");
                        return null;
                    } catch (Throwable ignored) {}
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewDisplay");

            Method setPreviewTexture = Camera.class.getDeclaredMethod("setPreviewTexture", SurfaceTexture.class);
            module.hook(setPreviewTexture).intercept(chain -> {
                SurfaceTexture texture = (SurfaceTexture) chain.getArgs().get(0);
                if (texture != null && module.getMediaPath() != null) {
                    if (texture == dummySurfaceTexture) {
                        return chain.proceed();
                    }
                    if (dummySurfaceTexture == null) {
                        dummySurfaceTexture = new SurfaceTexture(10);
                    }
                    viewfinderSurface = new Surface(texture);
                    module.registerPreviewSurface(viewfinderSurface);
                    module.logHook("[+] Hijacked Camera#setPreviewTexture -> diverted camera to dummy texture");
                    Object[] newArgs = chain.getArgs().toArray();
                    newArgs[0] = dummySurfaceTexture;
                    return chain.proceed(newArgs);
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewTexture");

            Method startPreview = Camera.class.getDeclaredMethod("startPreview");
            module.hook(startPreview).intercept(chain -> {
                Object result = chain.proceed();
                if (module.getMediaPath() != null && viewfinderSurface != null && viewfinderSurface.isValid()) {
                    module.recordPipelineNode("Camera1(PreviewStarted)");
                    module.logHook("[*] Starting xCam playback for Camera1 preview surface: " + viewfinderSurface);
                    module.handleModernPreview(viewfinderSurface);
                }
                return result;
            });
            module.logHook("[+] Hooked: Camera#startPreview");

            Method stopPreview = Camera.class.getDeclaredMethod("stopPreview");
            module.hook(stopPreview).intercept(chain -> {
                Object result = chain.proceed();
                module.stopEngine();
                if (dummySurfaceTexture != null) {
                    try { dummySurfaceTexture.release(); } catch (Throwable ignored) {}
                    dummySurfaceTexture = null;
                }
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
                int pw = (int) chain.getArgs().get(0);
                int ph = (int) chain.getArgs().get(1);
                module.recordPipelineNode("Camera1(Preview " + pw + "x" + ph + " NV21)");
                module.logHook("[*] Legacy Activity: setPreviewSize " + pw + "x" + ph);
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
                        int picW = params.getPictureSize().width;
                        int picH = params.getPictureSize().height;
                        byte[] imageData = module.handleCapture(picW, picH);

                        if (imageData == null) return chain.proceed();

                        module.recordPipelineNode("Camera1(takePicture " + picW + "x" + picH + " JPEG)");
                        module.reportPipelineCapture(picW, picH, imageData);

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

    private void hookPreviewCallbacks(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method setCallback = Camera.class.getDeclaredMethod("setPreviewCallback", Camera.PreviewCallback.class);
            module.hook(setCallback).intercept(chain -> {
                Object cb = chain.getArgs().get(0);
                if (cb != null) processCallbackRegistration(cb);
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewCallback");

            Method setCallbackWithBuffer = Camera.class.getDeclaredMethod("setPreviewCallbackWithBuffer", Camera.PreviewCallback.class);
            module.hook(setCallbackWithBuffer).intercept(chain -> {
                Object cb = chain.getArgs().get(0);
                if (cb != null) processCallbackRegistration(cb);
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewCallbackWithBuffer");

            Method setOneShot = Camera.class.getDeclaredMethod("setOneShotPreviewCallback", Camera.PreviewCallback.class);
            module.hook(setOneShot).intercept(chain -> {
                Object cb = chain.getArgs().get(0);
                if (cb != null) processCallbackRegistration(cb);
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setOneShotPreviewCallback");

            Method addCallbackBuffer = Camera.class.getDeclaredMethod("addCallbackBuffer", byte[].class);
            module.hook(addCallbackBuffer).intercept(chain -> chain.proceed());
            module.logHook("[+] Hooked: Camera#addCallbackBuffer");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook preview callbacks: " + t.getMessage());
        }
    }

    private void processCallbackRegistration(Object callback) {
        if (callback == null) return;
        Class<?> callbackClass = callback.getClass();
        if (!hookedPreviewCallbackClasses.add(callbackClass.getName())) {
            return;
        }

        try {
            Class<?> clazz = callbackClass;
            while (clazz != null && clazz != Object.class) {
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getName().equals("onPreviewFrame") && method.getParameterTypes().length == 2
                            && method.getParameterTypes()[0] == byte[].class && method.getParameterTypes()[1] == Camera.class) {
                        method.setAccessible(true);
                        module.hook(method).intercept(chain -> {
                            byte[] buffer = (byte[]) chain.getArgs().get(0);
                            Camera camera = (Camera) chain.getArgs().get(1);
                            if (buffer != null && camera != null && module.getMediaPath() != null) {
                                int w = 640;
                                int h = 480;
                                try {
                                    Camera.Parameters params = camera.getParameters();
                                    if (params != null) {
                                        Camera.Size size = params.getPreviewSize();
                                        if (size != null) {
                                            w = size.width;
                                            h = size.height;
                                        }
                                    }
                                } catch (Throwable ignored) {}

                                int[] dims = resolveDimensions(w, h, buffer.length);
                                w = dims[0];
                                h = dims[1];

                                byte[] nv21 = module.handleNv21Frame(w, h);
                                if (nv21 != null && nv21.length <= buffer.length) {
                                    System.arraycopy(nv21, 0, buffer, 0, nv21.length);
                                    if (nv21.length < buffer.length) {
                                        java.util.Arrays.fill(buffer, nv21.length, buffer.length, (byte) 128);
                                    }
                                    module.recordPipelineNode("Camera1(Callback " + w + "x" + h + " NV21)");
                                }
                            }
                            return chain.proceed();
                        });
                        module.logHook("[+] Hooked onPreviewFrame on: " + clazz.getName());
                        return;
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook onPreviewFrame on " + callbackClass.getName() + ": " + t.getMessage());
        }
    }

    private void hookCameraEventHandler(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi")
            Class<?> eventHandlerClass = Class.forName("android.hardware.Camera$EventHandler");
            Method handleMessage = eventHandlerClass.getDeclaredMethod("handleMessage", android.os.Message.class);
            module.hook(handleMessage).intercept(chain -> {
                try {
                    android.os.Message msg = (android.os.Message) chain.getArgs().get(0);
                    // 0x010 is CAMERA_MSG_PREVIEW_FRAME in AOSP
                    if (msg != null && msg.what == 0x010 && msg.obj instanceof byte[] && module.getMediaPath() != null) {
                        byte[] buffer = (byte[]) msg.obj;
                        int w = 640;
                        int h = 480;
                        try {
                            java.lang.reflect.Field cameraField = eventHandlerClass.getDeclaredField("mCamera");
                            cameraField.setAccessible(true);
                            Camera camera = (Camera) cameraField.get(chain.getThisObject());
                            if (camera != null) {
                                Camera.Size size = camera.getParameters().getPreviewSize();
                                if (size != null) {
                                    w = size.width;
                                    h = size.height;
                                }
                            }
                        } catch (Throwable ignored) {}

                        int[] dims = resolveDimensions(w, h, buffer.length);
                        w = dims[0];
                        h = dims[1];

                        byte[] nv21 = module.handleNv21Frame(w, h);
                        if (nv21 != null && nv21.length <= buffer.length) {
                            System.arraycopy(nv21, 0, buffer, 0, nv21.length);
                            if (nv21.length < buffer.length) {
                                java.util.Arrays.fill(buffer, nv21.length, buffer.length, (byte) 128);
                            }
                            module.recordPipelineNode("Camera1(EventHandler " + w + "x" + h + " NV21)");
                        }
                    }
                } catch (Throwable ignored) {}
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera$EventHandler#handleMessage");
        } catch (Throwable t) {
            module.logHook("[!] Camera$EventHandler hook skipped: " + t.getMessage());
        }
    }

    private void hookDisplayOrientation(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method setDisplayOrientation = Camera.class.getDeclaredMethod("setDisplayOrientation", int.class);
            module.hook(setDisplayOrientation).intercept(chain -> {
                int degrees = (int) chain.getArgs().get(0);
                module.logHook("[*] Camera#setDisplayOrientation: " + degrees + " degrees");
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setDisplayOrientation");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook setDisplayOrientation: " + t.getMessage());
        }
    }

    private void hookRelease(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method release = Camera.class.getDeclaredMethod("release");
            module.hook(release).intercept(chain -> {
                module.logHook("[*] Camera#release invoked");
                module.stopEngine();
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#release");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook release: " + t.getMessage());
        }
    }

    private static int[] resolveDimensions(int w, int h, int bufferLength) {
        if (w > 0 && h > 0 && w * h * 3 / 2 == bufferLength) {
            return new int[]{w, h};
        }
        int[][] standards = {
            {1920, 1080}, {1280, 720}, {960, 540}, {800, 600}, {800, 480},
            {640, 480}, {352, 288}, {320, 240}, {176, 144}
        };
        for (int[] res : standards) {
            if (res[0] * res[1] * 3 / 2 == bufferLength) {
                return res;
            }
        }
        if (w > 0 && (bufferLength * 2) % (w * 3) == 0) {
            int calcH = (bufferLength * 2) / (w * 3);
            return new int[]{w, calcH};
        }
        return new int[]{w > 0 ? w : 640, h > 0 ? h : 480};
    }
}

