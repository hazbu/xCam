# xCam - Universal Virtual Camera & Media Injection Xposed Module

**xCam** is a high-performance, universal Xposed module built with the modern **LibXposed API (API 101)**. It seamlessly injects virtual media (videos and images) into live camera feeds, photo capture routines, and video recording sessions across a wide variety of Android applications, supporting **Android 9 up to Android 16 (API 28 - API 36+)**.

---

## 🚀 Key Features

### 📸 Universal Media Support
- **Video & Image Support**: Inject standard video files (`.mp4`, `.mkv`, etc.) or still photos (`.jpg`, `.png`, etc.) with automated seamless loop processing.
- **Real-Time Transformations**: Rotate media on-the-fly (90°, 180°, 270°) and horizontally mirror preview output to match physical camera sensor orientation.

### 🎥 Multi-Layer Pipeline Interception
- **Camera1 & Camera2**: Surface and preview stream hijacking supporting both legacy APIs and modern `OutputConfiguration` pipelines.
- **Hardware ImageReader Hijacking**: Directly injects synthesized YUV420 and JPEG data into native `ImageReader` queues.
- **Universal Capture Replacement**: Intercepts `BitmapFactory` decode streams, `Bitmap.compress`, and `ContentResolver`/`MediaStore` file descriptors to ensure 100% replacement success on photos taken in target apps.
- **Synchronized Video Recording**: Employs hardware Surface-to-Surface transcoding (`MediaCodec`) to replace `MediaRecorder` output with millisecond accuracy matching the live preview, producing authentic IDR keyframes without green screen artifacts or PTS drift.
- **WebRTC Injection**: Replaces incoming video tracks in video call and live streaming apps.
- **Virtual Audio Feed**: Synchronized audio capture routing for media sources with sound.

### 🛠️ Diagnostics & Telemetry
- **Pipeline Monitor**: Real-time inspection of active hook execution routes (e.g., `ImageReader(YUV) ➔ Camera2(SurfaceTexture) ➔ MediaRecorder#start ➔ MediaRecorder(VideoTrimmed)`).
- **Capture Alert**: Heads-up notification card with captured image thumbnail preview and quick-dismiss capabilities.

---

## 📊 Feature Availability

| Feature | Android 9 - 11 (Legacy) | Android 12 - 16 (Modern) |
| :--- | :---: | :---: |
| **Video Playback (.mp4)** | ✅ | ✅ |
| **Still Images (.jpg / .png)** | ✅ | ✅ |
| **Rotate (90° / 180° / 270°)** | ✅ | ✅ |
| **Horizontal Mirroring** | ✅ | ✅ |
| **Photo Capture Hijacking** | ✅ | ✅ |
| **Video Recording Replacement** | ✅ | ✅ |
| **Live Route Pipeline Monitor** | ✅ | ✅ |
| **Capture Alert Notification** | ✅ | ✅ |

---

## 📦 Installation & Setup

### 📱 Rooted Environments
**Requirements**:
- Rooted Android device (Android 9.0 - 16.0)
- Xposed framework provider supporting modern API (e.g., **LSPosed**, **Vector**, **KernelSU + Zygisk**, **APatch**)

### 🛡️ Non-Rooted Environments
**Requirements**:
- Non-rooted Android device (Android 9.0 - 14.0+)
- APK patcher tool such as **LSPatch** (recommended) or **Shizuku + LSPatch**

## ⚖️ License

This project is licensed under the **GNU General Public License v3.0**.

```text
Copyright (C) 2026 hazbu
```

## ⚠️ Disclaimer

This module is intended for **educational, testing, and development purposes only**. Use responsibly and at your own risk. The developer is not responsible for any misuse, account suspensions, or violations of third-party application terms of service.
