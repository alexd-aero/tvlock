#!/bin/sh
# Builds TV Lock on the Pi: JDK 17 + the system Gradle 8.9.
#   ./build.sh                    fast dev APK (no R8), same signing key: installs over the TV's copy, keeps PINs
#                                 -> app/build/outputs/apk/dev/app-dev.apk
#   ./build.sh assembleRelease    the small shrunk APK that gets published (release.sh does this)
# The Gradle daemon stays up between builds (3 h idle), so only changed code is recompiled.
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/home/rasp-alex2/android-sdk
export PATH=/opt/gradle-8.9/bin:$JAVA_HOME/bin:$PATH
cd "$(dirname "$0")"
exec gradle "${@:-assembleDev}"
