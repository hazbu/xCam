package com.hazbu.xcam.hooks;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.hazbu.xcam.data.Constants;
import com.hazbu.xcam.xposed.XCamModule;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Consolidated Capture Hook.
 * Handles all image data injection points (BitmapFactory, Bitmap.compress, MediaStore, FileOutput).
 * Prevents redundant extractions and recursion.
 */
public class CaptureHook {
    private final XCamModule module;

    public CaptureHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Capture Diversion Hooks");
            
            hookBitmapFactory(param);
            
            hookBitmapCompress(param);
            
            hookMediaStore(param);
            
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
                            byte[] injected = module.handleCapture(0, 0);
                            if (injected != null) {
                                module.recordPipelineNode("BitmapFactory#decodeByteArray");
                                module.reportPipelineCapture(0, 0, injected);
                                module.logHook("[*] Activity: Captured -> Injected into BitmapFactory#decodeByteArray");
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
                            byte[] injected = module.handleCapture(0, 0);
                            if (injected != null) {
                                try {
                                    module.setIgnoringHooks(true);
                                    BitmapFactory.Options opts = null;
                                    if (chain.getArgs().size() >= 3 && chain.getArgs().get(2) instanceof BitmapFactory.Options) {
                                        opts = (BitmapFactory.Options) chain.getArgs().get(2);
                                    }
                                    
                                    Bitmap bitmap = BitmapFactory.decodeByteArray(injected, 0, injected.length, opts);
                                    if (bitmap != null) {
                                        module.recordPipelineNode("BitmapFactory#decodeStream");
                                        module.reportPipelineCapture(0, 0, injected);
                                        module.logHook("[*] Activity: Captured -> Injected virtual Bitmap into BitmapFactory#decodeStream");
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
                    byte[] injected = module.handleCapture(Constants.DEFAULT_CAPTURE_WIDTH, Constants.DEFAULT_CAPTURE_HEIGHT);
                    if (injected != null) {
                        OutputStream os = (OutputStream) chain.getArgs().get(2);
                        if (os != null) {
                            try {
                                module.setIgnoringHooks(true);
                                os.write(injected);
                                os.flush();
                                module.recordPipelineNode("Bitmap#compress");
                                module.reportPipelineCapture(Constants.DEFAULT_CAPTURE_WIDTH, Constants.DEFAULT_CAPTURE_HEIGHT, injected);
                                module.logHook("[*] Activity: Captured -> Injected into Bitmap#compress");
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
                        byte[] injected = module.handleCapture(Constants.DEFAULT_CAPTURE_WIDTH, Constants.DEFAULT_CAPTURE_HEIGHT);
                        if (injected != null) {
                            try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
                                module.setIgnoringHooks(true);
                                fos.write(injected);
                                fos.flush();
                                module.recordPipelineNode("MediaStore#openFD");
                                module.reportPipelineCapture(Constants.DEFAULT_CAPTURE_WIDTH, Constants.DEFAULT_CAPTURE_HEIGHT, injected);
                                module.logHook("[*] Activity: Captured -> Injected into MediaStore FD: " + uri);
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
}
