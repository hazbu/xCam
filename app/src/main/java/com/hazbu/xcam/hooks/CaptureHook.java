package com.hazbu.xcam.hooks;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.hazbu.xcam.data.Constants;
import com.hazbu.xcam.xposed.XCamModule;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Consolidated Capture Hook.
 * Handles all image data injection points (BitmapFactory, Bitmap.compress, MediaStore, FileOutput).
 * Prevents redundant extractions and recursion.
 */
public class CaptureHook {
    private final XCamModule module;
    private final ImageReaderHook imageReaderHook;

    private static class RecorderTarget {
        String path;
        FileDescriptor fd;
        long startMediaPosMs = 0;
        long startWallClockMs = 0;

        RecorderTarget(String path, FileDescriptor fd) {
            this.path = path;
            this.fd = fd;
        }
    }

    private final Map<Object, RecorderTarget> recorderTargets = new ConcurrentHashMap<>();
    private volatile RecorderTarget lastRecorderTarget;

    public CaptureHook(XCamModule module, ImageReaderHook imageReaderHook) {
        this.module = module;
        this.imageReaderHook = imageReaderHook;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Capture Diversion Hooks");
            
            hookBitmapFactory(param);
            
            hookBitmapCompress(param);
            
            hookMediaStore(param);
            
            hookMediaRecorder(param);

            hookMediaCodec(param);
            
            module.logHook("[+] Capture Diversion Hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] Capture Hooks installation failed: " + t.getMessage());
        }
    }

    private void hookBitmapFactory(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method method : BitmapFactory.class.getDeclaredMethods()) {
                if (method.getName().equals("decodeByteArray")) {
                    module.hook(method).intercept(chain -> {
                        if (module.isIgnoringHooks()) return chain.proceed();
                        if (module.isCapturingState() && module.getMediaPath() != null) {
                            int activeW = imageReaderHook.getActiveCaptureWidth();
                            int activeH = imageReaderHook.getActiveCaptureHeight();
                            int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                            int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                            byte[] injected = module.handleCapture(targetW, targetH);
                            if (injected != null) {
                                BitmapFactory.Options boundOpts = new BitmapFactory.Options();
                                boundOpts.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(injected, 0, injected.length, boundOpts);
                                int actualW = boundOpts.outWidth > 0 ? boundOpts.outWidth : targetW;
                                int actualH = boundOpts.outHeight > 0 ? boundOpts.outHeight : targetH;
                                String nodeDesc = "BitmapFactory#decodeByteArray(" + actualW + "x" + actualH + ")";
                                module.recordPipelineNode(nodeDesc);
                                module.reportPipelineCapture(actualW, actualH, injected);
                                module.logHook("[*] Activity: Captured -> Injected into BitmapFactory#decodeByteArray (" + actualW + "x" + actualH + ")");
                                Object[] args = chain.getArgs().toArray();
                                args[0] = injected;
                                if (args.length >= 3) args[2] = injected.length;
                                return chain.proceed(args);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: BitmapFactory#decodeByteArray");
                }
                
                if (method.getName().equals("decodeStream") && method.getParameterTypes().length >= 1) {
                    module.hook(method).intercept(chain -> {
                        if (module.isIgnoringHooks()) return chain.proceed();
                        if (module.isCapturingState() && module.getMediaPath() != null) {
                            int activeW = imageReaderHook.getActiveCaptureWidth();
                            int activeH = imageReaderHook.getActiveCaptureHeight();
                            int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                            int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                            byte[] injected = module.handleCapture(targetW, targetH);
                            if (injected != null) {
                                try {
                                    module.setIgnoringHooks(true);
                                    BitmapFactory.Options opts = null;
                                    if (chain.getArgs().size() >= 3 && chain.getArgs().get(2) instanceof BitmapFactory.Options) {
                                        opts = (BitmapFactory.Options) chain.getArgs().get(2);
                                    }
                                    
                                    Bitmap bitmap = BitmapFactory.decodeByteArray(injected, 0, injected.length, opts);
                                    if (bitmap != null) {
                                        int actualW = bitmap.getWidth();
                                        int actualH = bitmap.getHeight();
                                        String nodeDesc = "BitmapFactory#decodeStream(" + actualW + "x" + actualH + ")";
                                        module.recordPipelineNode(nodeDesc);
                                        module.reportPipelineCapture(actualW, actualH, injected);
                                        module.logHook("[*] Activity: Captured -> Injected virtual Bitmap into BitmapFactory#decodeStream (" + actualW + "x" + actualH + ")");
                                        return bitmap;
                                    }
                                } finally {
                                    module.setIgnoringHooks(false);
                                }
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: BitmapFactory#decodeStream");
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookBitmapCompress(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method compress = Bitmap.class.getDeclaredMethod("compress", Bitmap.CompressFormat.class, int.class, OutputStream.class);
            module.hook(compress).intercept(chain -> {
                if (module.isIgnoringHooks()) return chain.proceed();
                if (module.isCapturingState() && module.getMediaPath() != null) {
                    int activeW = imageReaderHook.getActiveCaptureWidth();
                    int activeH = imageReaderHook.getActiveCaptureHeight();
                    int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                    int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                    byte[] injected = module.handleCapture(targetW, targetH);
                    if (injected != null) {
                        OutputStream os = (OutputStream) chain.getArgs().get(2);
                        if (os != null) {
                            try {
                                module.setIgnoringHooks(true);
                                os.write(injected);
                                os.flush();
                                BitmapFactory.Options boundOpts = new BitmapFactory.Options();
                                boundOpts.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(injected, 0, injected.length, boundOpts);
                                int actualW = boundOpts.outWidth > 0 ? boundOpts.outWidth : targetW;
                                int actualH = boundOpts.outHeight > 0 ? boundOpts.outHeight : targetH;
                                String nodeDesc = "Bitmap#compress(" + actualW + "x" + actualH + ")";
                                module.recordPipelineNode(nodeDesc);
                                module.reportPipelineCapture(actualW, actualH, injected);
                                module.logHook("[*] Activity: Captured -> Injected into Bitmap#compress (" + actualW + "x" + actualH + ")");
                                return true;
                            } catch (Throwable t) {
                                module.logHook("[!] Bitmap#compress injection FAILED: " + t.getMessage());
                            } finally {
                                module.setIgnoringHooks(false);
                            }
                        }
                    }
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Bitmap#compress");
        } catch (Throwable ignored) {}
    }

    private void hookMediaStore(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method openFD = ContentResolver.class.getDeclaredMethod("openFileDescriptor", Uri.class, String.class);
            module.hook(openFD).intercept(chain -> {
                if (module.isIgnoringHooks()) return chain.proceed();
                
                Uri uri = (Uri) chain.getArgs().get(0);
                String mode = (String) chain.getArgs().get(1);
                
                if (uri != null && mode != null && mode.contains("w") && module.isCapturingState()) {
                    ParcelFileDescriptor pfd = (ParcelFileDescriptor) chain.proceed();
                    if (pfd != null) {
                        int activeW = imageReaderHook.getActiveCaptureWidth();
                        int activeH = imageReaderHook.getActiveCaptureHeight();
                        int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                        int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                        byte[] injected = module.handleCapture(targetW, targetH);
                        if (injected != null) {
                            try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
                                module.setIgnoringHooks(true);
                                fos.write(injected);
                                fos.flush();
                                BitmapFactory.Options boundOpts = new BitmapFactory.Options();
                                boundOpts.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(injected, 0, injected.length, boundOpts);
                                int actualW = boundOpts.outWidth > 0 ? boundOpts.outWidth : targetW;
                                int actualH = boundOpts.outHeight > 0 ? boundOpts.outHeight : targetH;
                                String nodeDesc = "MediaStore#openFD(" + actualW + "x" + actualH + ")";
                                module.recordPipelineNode(nodeDesc);
                                module.reportPipelineCapture(actualW, actualH, injected);
                                module.logHook("[*] Activity: Captured -> Injected into MediaStore FD (" + actualW + "x" + actualH + "): " + uri);
                            } catch (Throwable t) {
                                module.logHook("[!] MediaStore injection error: " + t.getMessage());
                            } finally {
                                module.setIgnoringHooks(false);
                            }
                        }
                    }
                    return pfd;
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: ContentResolver#openFileDescriptor");
        } catch (Throwable ignored) {}
    }

    private String resolveFdPath(FileDescriptor fd) {
        if (fd == null) return null;
        try {
            Field field = FileDescriptor.class.getDeclaredField("descriptor");
            field.setAccessible(true);
            int fdInt = (int) field.get(fd);
            if (fdInt >= 0) {
                File procLink = new File("/proc/self/fd/" + fdInt);
                if (procLink.exists()) {
                    return procLink.getCanonicalPath();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void recordOutputTarget(Object recorder, String path, FileDescriptor fd) {
        String resolvedPath = path;
        if (resolvedPath == null && fd != null) {
            resolvedPath = resolveFdPath(fd);
        }
        RecorderTarget target = new RecorderTarget(resolvedPath, fd);
        if (recorder != null) {
            recorderTargets.put(recorder, target);
        }
        lastRecorderTarget = target;
        module.logHook("[*] MediaRecorder output configured -> Path: " + resolvedPath + " | FD: " + fd);
    }

    private InputStream openSourceStream(String path) {
        if (path == null) return null;
        try {
            if (path.startsWith("content://")) {
                android.content.Context ctx = module.getContext();
                if (ctx != null) {
                    return ctx.getContentResolver().openInputStream(Uri.parse(path));
                }
            }
            if (path.startsWith("file://")) {
                return new FileInputStream(Uri.parse(path).getPath());
            }
            return new FileInputStream(path);
        } catch (Throwable t) {
            module.logHook("[!] Failed to open media source stream: " + t.getMessage());
            return null;
        }
    }

    private void recordStartTime(Object recorder) {
        RecorderTarget target = null;
        if (recorder != null) {
            target = recorderTargets.get(recorder);
        }
        if (target == null) {
            target = lastRecorderTarget;
        }
        if (target != null) {
            target.startMediaPosMs = module.getCurrentPosition();
            target.startWallClockMs = System.currentTimeMillis();
            module.logHook("[*] MediaRecorder#start timing captured: StartMediaPos=" + target.startMediaPosMs + " ms, WallClock=" + target.startWallClockMs);
        }
    }

    private void replaceMediaRecorderOutput(Object recorder) {
        RecorderTarget target = null;
        if (recorder != null) {
            target = recorderTargets.remove(recorder);
        }
        if (target == null) {
            target = lastRecorderTarget;
            lastRecorderTarget = null;
        }
        if (target == null) {
            module.logHook("[!] MediaRecorder#stop: No output target found to replace");
            return;
        }

        String mediaPath = module.getMediaPath();
        if (mediaPath == null) {
            module.logHook("[!] MediaRecorder#stop: Media path is null, cannot replace recorded video");
            return;
        }

        long stopWallClockMs = System.currentTimeMillis();
        long recordDurationMs = (target.startWallClockMs > 0) ? (stopWallClockMs - target.startWallClockMs) : 0;
        long startMediaPosMs = target.startMediaPosMs;
        module.logHook("[*] MediaRecorder#stop timing: Duration=" + recordDurationMs + " ms, StartMediaPos=" + startMediaPosMs + " ms");

        boolean replaced = false;
        long copiedBytes = 0;

        if (target.path != null) {
            File destFile = new File(target.path);
            android.content.Context ctx = module.getContext();
            if (ctx != null && recordDurationMs > 300) {
                try {
                    replaced = com.hazbu.xcam.utils.MediaRemuxer.INSTANCE.trimVideo(
                        ctx,
                        mediaPath,
                        destFile,
                        startMediaPosMs,
                        recordDurationMs,
                        msg -> {
                            module.logHook(msg);
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                    if (replaced) {
                        module.recordPipelineNode("MediaRecorder(VideoTrimmed)");
                        module.logHook("[*] Activity: MediaRecorder#stop -> Trimmed video successfully: " + target.path + " (Duration: " + recordDurationMs + " ms)");
                    }
                } catch (Throwable t) {
                    module.logHook("[!] Trimming attempt failed: " + t.getMessage());
                }
            }

            if (!replaced) {
                try (InputStream in = openSourceStream(mediaPath);
                     FileOutputStream out = new FileOutputStream(destFile, false)) {
                    if (in != null) {
                        byte[] buffer = new byte[65536];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                            copiedBytes += len;
                        }
                        out.flush();
                        try {
                            out.getFD().sync();
                        } catch (Throwable ignored) {}
                        replaced = true;
                        module.recordPipelineNode("MediaRecorder(VideoReplaced)");
                        module.logHook("[*] Activity: MediaRecorder#stop -> Successfully replaced file: " + target.path + " (" + copiedBytes + " bytes)");
                    }
                } catch (Throwable t) {
                    module.logHook("[!] Failed to replace file at " + target.path + ": " + t.getMessage());
                }
            }
        }

        if (!replaced && target.fd != null && target.fd.valid()) {
            try (ParcelFileDescriptor dupPfd = ParcelFileDescriptor.dup(target.fd);
                 FileOutputStream fos = new FileOutputStream(dupPfd.getFileDescriptor())) {
                java.nio.channels.FileChannel channel = fos.getChannel();
                channel.position(0);
                channel.truncate(0);
                try (InputStream in = openSourceStream(mediaPath)) {
                    if (in != null) {
                        byte[] buffer = new byte[65536];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                            copiedBytes += len;
                        }
                        fos.flush();
                        try {
                            channel.force(true);
                        } catch (Throwable ignored) {}
                        replaced = true;
                        module.recordPipelineNode("MediaRecorder(VideoReplaced)");
                        module.logHook("[*] Activity: MediaRecorder#stop -> Successfully replaced via FileDescriptor (" + copiedBytes + " bytes)");
                    }
                }
            } catch (Throwable t) {
                module.logHook("[!] Failed to replace via FileDescriptor: " + t.getMessage());
            }
        }

        if (!replaced) {
            module.logHook("[!] MediaRecorder#stop: Output replacement was not successful");
        }
    }

    private void hookMediaRecorder(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> mrClass = param.getClassLoader().loadClass("android.media.MediaRecorder");

            for (Method m : mrClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("setOutputFile") || name.equals("setNextOutputFile")) {
                    module.hook(m).intercept(chain -> {
                        Object recorder = chain.getThisObject();
                        List<Object> args = chain.getArgs();
                        if (args != null && !args.isEmpty()) {
                            Object target = args.get(0);
                            if (target instanceof String) {
                                recordOutputTarget(recorder, (String) target, null);
                            } else if (target instanceof File) {
                                recordOutputTarget(recorder, ((File) target).getAbsolutePath(), null);
                            } else if (target instanceof FileDescriptor) {
                                recordOutputTarget(recorder, null, (FileDescriptor) target);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: MediaRecorder#" + name);
                }
            }

            Method startMethod = mrClass.getDeclaredMethod("start");
            module.hook(startMethod).intercept(chain -> {
                Object recorder = chain.getThisObject();
                recordStartTime(recorder);
                module.recordPipelineNode("MediaRecorder#start");
                module.logHook("[*] Activity: MediaRecorder#start");
                return chain.proceed();
            });
            module.logHook("[+] Hooked: MediaRecorder#start");

            Method stopMethod = mrClass.getDeclaredMethod("stop");
            module.hook(stopMethod).intercept(chain -> {
                Object recorder = chain.getThisObject();
                Object result = chain.proceed();
                try {
                    replaceMediaRecorderOutput(recorder);
                } catch (Throwable t) {
                    module.logHook("[!] MediaRecorder#stop replacement invocation failed: " + t.getMessage());
                }
                return result;
            });
            module.logHook("[+] Hooked: MediaRecorder#stop");

            for (Method m : mrClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("reset") || name.equals("release")) {
                    module.hook(m).intercept(chain -> {
                        Object recorder = chain.getThisObject();
                        if (recorder != null) {
                            recorderTargets.remove(recorder);
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookMediaCodec(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> mcClass = param.getClassLoader().loadClass("android.media.MediaCodec");
            Method createInputSurface = mcClass.getDeclaredMethod("createInputSurface");
            module.hook(createInputSurface).intercept(chain -> {
                module.recordPipelineNode("MediaCodec(InputSurface)");
                module.logHook("[*] Activity: MediaCodec#createInputSurface");
                return chain.proceed();
            });
            module.logHook("[+] Hooked: MediaCodec#createInputSurface");
        } catch (Throwable ignored) {}
    }
}
