#!/bin/sh
# Builds the release APK and publishes it as a GitHub release, which TV Lock's updater picks up.
# Usage: ./release.sh ["release notes"]
set -e
cd "$(dirname "$0")"
V=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
./build.sh assembleRelease
cp app/build/outputs/apk/release/app-release.apk "/tmp/TV-Lock-$V.apk"
gh release create "v$V" "/tmp/TV-Lock-$V.apk" --repo alexd-aero/tvlock --title "TV Lock $V" --notes "${1:-TV Lock $V}"
