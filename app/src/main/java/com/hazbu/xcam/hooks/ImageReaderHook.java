package com.hazbu.xcam.hooks;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;

import androidx.annotation.NonNull;

import com.hazbu.xcam.xposed.XCamModule;
import com.hazbu.xcam.utils.SystemUtils;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Specialized hooks for android.media.ImageReader.
 * Detects when ImageReader is created and when data starts flowing.
 * Added surgical injection for RGBA_8888 (0x1) format used by Instagram.
 */
public class ImageReaderHook {
    private final XCamModule module;
    private final Set<String> detectedFlows = new HashSet<>();
    private final Set<String> injectedFlows = new HashSet<>();
    private final Map<ImageReader, ReaderMetadata> readerTracker = new WeakHashMap<>();

    private volatile int activeCaptureWidth = 0;
    private volatile int activeCaptureHeight = 0;

    public int getActiveCaptureWidth() {
        return activeCaptureWidth;
    }

    public int getActiveCaptureHeight() {
        return activeCaptureHeight;
    }

    public void reset() {
        activeCaptureWidth = 0;
        activeCaptureHeight = 0;
        detectedFlows.clear();
        injectedFlows.clear();
        readerTracker.clear();
    }

    private static class ReaderMetadata {
        final int width;
        final int height;
        final int format;
        final String formatName;
        final long surfaceId;

        ReaderMetadata(int width, int height, int format, String formatName, long surfaceId) {
            this.width = width;
            this.height = height;
            this.format = format;
            this.formatName = formatName;
            this.surfaceId = surfaceId;
        }

        @NonNull
        @Override
        public String toString() {
            return width + "x" + height + " " + formatName + " (ID: " + surfaceId + ")";
        }
    }

    public ImageReaderHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing ImageReader Hooks");
            hookImageReader(param);
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize ImageReader hooks: " + t.getMessage());
        }
    }

    private String getFormatName(int format) {
        switch (format) {
            case ImageFormat.JPEG: return "JPEG";
            case ImageFormat.YUV_420_888: return "YUV_420_888";
            case 0x1: return "RGBA_8888";
            case 0x22: return "PRIVATE";
            case ImageFormat.RAW_SENSOR: return "RAW_SENSOR";
            default: return "Format(0x" + Integer.toHexString(format) + ")";
        }
    }

    private void hookImageReader(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method method : ImageReader.class.getDeclaredMethods()) {
                if (method.getName().equals("newInstance")) {
                    module.hook(method).intercept(chain -> {
                        int w = (int) chain.getArgs().get(0);
                        int h = (int) chain.getArgs().get(1);
                        int format = (int) chain.getArgs().get(2);
                        String fmtName = getFormatName(format);

                        if (w > 0 && h > 0) {
                            if (format == ImageFormat.JPEG || (w * h >= activeCaptureWidth * activeCaptureHeight)) {
                                activeCaptureWidth = w;
                                activeCaptureHeight = h;
                            }
                        }
                        
                        Object result = chain.proceed();
                        if (result instanceof ImageReader) {
                            ImageReader reader = (ImageReader) result;
                            long surfaceId = SystemUtils.INSTANCE.getSurfaceId(reader.getSurface());
                            readerTracker.put(reader, new ReaderMetadata(w, h, format, fmtName, surfaceId));
                            module.registerImageReaderSurface(reader.getSurface(), format, w, h);
                            String shortFmt = getShortFormatName(format);
                            module.recordPipelineNode("ImageReader(" + w + "x" + h + " " + shortFmt + ")");
                            module.logHook("[+] ImageReader.newInstance: " + w + "x" + h + " " + fmtName + " | ID: " + surfaceId);
                            module.showToast(w + "x" + h + " (" + fmtName + ")");
                        }
                        return result;
                    });
                }
            }

            try {
                Method setListener = ImageReader.class.getDeclaredMethod("setOnImageAvailableListener", ImageReader.OnImageAvailableListener.class, android.os.Handler.class);
                module.hook(setListener).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    ReaderMetadata meta = readerTracker.get(reader);
                    module.logHook("[*] ImageReader.setOnImageAvailableListener | Reader: " + (meta != null ? meta.toString() : "unknown") + " | Thread: " + Thread.currentThread().getName());
                    return chain.proceed();
                });
                module.logHook("[+] Hooked: ImageReader#setOnImageAvailableListener");
            } catch (Throwable ignored) {}

            try {
                Method acquireLatest = ImageReader.class.getDeclaredMethod("acquireLatestImage");
                module.hook(acquireLatest).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    ReaderMetadata meta = readerTracker.get(reader);
                    if (meta != null && meta.width == 960 && meta.format == ImageFormat.YUV_420_888) {
                         module.logHook("[*] ImageReader.acquireLatestImage | Reader: " + meta + " | Thread: " + Thread.currentThread().getName());
                    }
                    Object result = chain.proceed();
                    if (result instanceof Image) {
                        processAcquiredImage(reader, (Image) result);
                    }
                    return result;
                });
                module.logHook("[+] Hooked: ImageReader#acquireLatestImage");
            } catch (Throwable ignored) {}

            try {
                Method acquireNext = ImageReader.class.getDeclaredMethod("acquireNextImage");
                module.hook(acquireNext).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    ReaderMetadata meta = readerTracker.get(reader);
                    if (meta != null && meta.width == 960 && meta.format == ImageFormat.YUV_420_888) {
                        module.logHook("[*] ImageReader.acquireNextImage | Reader: " + meta + " | Thread: " + Thread.currentThread().getName());
                    }
                    Object result = chain.proceed();
                    if (result instanceof Image) {
                        processAcquiredImage(reader, (Image) result);
                    }
                    return result;
                });
                module.logHook("[+] Hooked: ImageReader#acquireNextImage");
            } catch (Throwable ignored) {}

            try {
                Method close = ImageReader.class.getDeclaredMethod("close");
                module.hook(close).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    ReaderMetadata meta = readerTracker.get(reader);
                    module.logHook("[*] ImageReader.close | Reader: " + (meta != null ? meta.toString() : "unknown"));
                    return chain.proceed();
                });
                module.logHook("[+] Hooked: ImageReader#close");
            } catch (Throwable ignored) {}
            
        } catch (Throwable t) {
            module.logHook("[!] ImageReader hook failed: " + t.getMessage());
        }
    }

    private String getShortFormatName(int format) {
        switch (format) {
            case ImageFormat.JPEG: return "JPEG";
            case ImageFormat.YUV_420_888: return "YUV";
            case 0x1: return "RGBA";
            case 0x22: return "PRIVATE";
            case ImageFormat.RAW_SENSOR: return "RAW";
            default: return "0x" + Integer.toHexString(format);
        }
    }

    private void processAcquiredImage(ImageReader reader, Image image) {
        int w = reader.getWidth();
        int h = reader.getHeight();
        int format = reader.getImageFormat();
        String key = w + "x" + h + "_" + format;
        String shortFmt = getShortFormatName(format);

        if (w > 0 && h > 0) {
            if (format == ImageFormat.JPEG || activeCaptureWidth == 0 || activeCaptureHeight == 0) {
                activeCaptureWidth = w;
                activeCaptureHeight = h;
            }
        }

        if (!detectedFlows.contains(key)) {
            detectedFlows.add(key);
            String fmt = getFormatName(format);
            module.showToast(w + "x" + h + " (" + fmt + ")");
            module.logHook("[*] Activity: ImageReader first image acquired: " + key);
        }

        if (module.getMediaPath() != null && format == ImageFormat.YUV_420_888) {
            module.recordPipelineNode("ImageReader(" + w + "x" + h + " " + shortFmt + ")");
            module.injectYuvFrame(image, w, h);
            if (injectedFlows.add(key)) {
                module.logHook("[+] Activity: ImageReader YUV replacement active: " + key);
            }
        } else if (module.isCapturingState() && format == ImageFormat.JPEG) {
            try {
                Image.Plane[] planes = image.getPlanes();
                if (planes != null && planes.length > 0) {
                    ByteBuffer buffer = planes[0].getBuffer();
                    if (buffer != null && !buffer.isReadOnly()) {
                        int capacity = buffer.remaining();
                        byte[] replacement = module.handleCapture(w, h, capacity);
                        
                        if (replacement != null) {
                            buffer.clear();
                            if (buffer.remaining() < replacement.length) {
                                module.logHook("[!] Activity: ImageReader JPEG replacement skipped: buffer too small (" + buffer.remaining() + " < " + replacement.length + ")");
                                return;
                            }
                            buffer.put(replacement);
                            module.recordPipelineNode("ImageReader(" + w + "x" + h + " " + shortFmt + ")");
                            module.reportPipelineCapture(w, h, replacement);
                            module.logHook("[+] Activity: ImageReader JPEG replaced successfully (" + replacement.length + " bytes)");
                        }
                    }
                }
            } catch (Throwable t) {
                module.logHook("[!] Failed to inject JPEG: " + t.getMessage());
            }
        } else if (format == 0x1) {
            module.recordPipelineNode("ImageReader(" + w + "x" + h + " " + shortFmt + ")");
        }
    }
}
