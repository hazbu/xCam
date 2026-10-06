package com.hazbu.xcam.hooks;

import android.media.AudioRecord;

import com.hazbu.xcam.xposed.XCamModule;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Microphone & AudioRecord Hook.
 * Aligned with CamSwap: intercepts AudioRecord.read() and replaces real microphone audio
 * with virtual PCM audio decoded from the video file.
 */
public class AudioHook {
    private final XCamModule module;

    public AudioHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Microphone / AudioRecord Hooks");
            hookAudioRecordRead(param);
            module.logHook("[+] Microphone / AudioRecord Hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] AudioRecord hook installation failed: " + t.getMessage());
        }
    }

    private void hookAudioRecordRead(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> audioRecordClass = param.getClassLoader().loadClass("android.media.AudioRecord");
            for (Method method : audioRecordClass.getDeclaredMethods()) {
                if (!method.getName().equals("read")) continue;

                Class<?>[] params = method.getParameterTypes();
                if (params.length < 2) continue;

                // 1. read(byte[] audioData, int offsetInBytes, int sizeInBytes, ...)
                if (params[0] == byte[].class && params[1] == int.class && params[2] == int.class) {
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Integer && (Integer) result > 0 && module.getMediaPath() != null) {
                            module.recordPipelineNode("AudioRecord(PCM)");
                            int bytesRead = (Integer) result;
                            List<Object> args = chain.getArgs();
                            byte[] buffer = (byte[]) args.get(0);
                            int offset = (int) args.get(1);
                            module.injectAudioBytes(buffer, offset, bytesRead);
                        }
                        return result;
                    });
                    module.logHook("[+] Hooked: AudioRecord#read(byte[], ...)");
                }

                // 2. read(short[] audioData, int offsetInShorts, int sizeInShorts, ...)
                if (params[0] == short[].class && params[1] == int.class && params[2] == int.class) {
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Integer && (Integer) result > 0 && module.getMediaPath() != null) {
                            int shortsRead = (Integer) result;
                            List<Object> args = chain.getArgs();
                            short[] buffer = (short[]) args.get(0);
                            int offset = (int) args.get(1);
                            module.injectAudioShorts(buffer, offset, shortsRead);
                        }
                        return result;
                    });
                    module.logHook("[+] Hooked: AudioRecord#read(short[], ...)");
                }

                // 3. read(ByteBuffer audioBuffer, int sizeInBytes, ...)
                if (params[0] == ByteBuffer.class && params[1] == int.class) {
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Integer && (Integer) result > 0 && module.getMediaPath() != null) {
                            int bytesRead = (Integer) result;
                            List<Object> args = chain.getArgs();
                            ByteBuffer buffer = (ByteBuffer) args.get(0);
                            module.injectAudioByteBuffer(buffer, bytesRead);
                        }
                        return result;
                    });
                    module.logHook("[+] Hooked: AudioRecord#read(ByteBuffer, ...)");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] AudioRecord method hook failed: " + t.getMessage());
        }
    }
}
