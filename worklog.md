---
Task ID: 1
Agent: Main Agent
Task: Fix ScreenShareService crash on Android 14 and rebuild APK

Work Log:
- Read all source files (ScreenShareService, MainActivity, FileShareServer, FileShareService, AndroidManifest)
- Identified root cause: ScreenShareService.onStartCommand() called startForeground() with mediaProjection type BEFORE MediaProjection was obtained — illegal on Android 14 (API 34)
- Rewrote ScreenShareService.java: removed startForeground() from onStartCommand(), added startForegroundMediaProjection() that is called AFTER getMediaProjection() succeeds in startCapture()
- Updated MainActivity.java intent key from "data" to EXTRA_PROJECTION_DATA to match new ScreenShareService constant
- Fixed missing imports: java.util.Map/HashMap in FileShareServer.java, android.view.View in MainActivity.java
- Downloaded and installed full JDK 17 (Temurin 17.0.19+10) since system JDK 21 was headless (missing jlink)
- Downloaded and installed Gradle 8.7
- Installed Android SDK command-line tools, platform 34, build-tools 34.0.0
- Built debug APK successfully (5.6MB)
- Copied to /home/z/my-project/download/SplannesFileSharePro.apk

Stage Summary:
- Fixed SecurityException crash on Android 14 for screen sharing
- APK built and saved to /home/z/my-project/download/SplannesFileSharePro.apk
- Key fix: startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) now called AFTER getMediaProjection() succeeds