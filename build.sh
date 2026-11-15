#!/bin/sh
# Builds the release APK on the Pi: JDK 17 + the system Gradle 8.9.
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/home/rasp-alex2/android-sdk
export PATH=/opt/gradle-8.9/bin:$JAVA_HOME/bin:$PATH
cd "$(dirname "$0")"
exec gradle --no-daemon "${@:-assembleRelease}"
