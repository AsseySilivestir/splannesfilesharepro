#!/bin/bash
set -e
export ANDROID_HOME=/home/z/android-sdk
export JAVA_HOME=/home/z/jdk-17
export PATH=$JAVA_HOME/bin:/home/z/gradle/bin:$PATH
export GRADLE_OPTS="-Xmx3072m -Dorg.gradle.daemon=false"

cd /home/z/my-project/splannesfilesharepro
echo "=== Starting build ==="
gradle assembleDebug 2>&1
echo "=== Build complete ==="
ls -la app/build/outputs/apk/debug/app-debug.apk
cp app/build/outputs/apk/debug/app-debug.apk /home/z/my-project/download/SplannesFileSharePro.apk
echo "=== APK copied to download/ ==="