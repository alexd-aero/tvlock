#!/bin/sh
# Builds both APKs and publishes them as a GitHub release, which TV Lock's updaters pick up:
#   TV-Lock-<v>.apk        the TV app (uploaded first: TV Lock 1.3.x takes the first .apk it finds)
#   TV-Lock-Phone-<v>.apk  the phone app
# Usage: ./release.sh ["release notes"]
set -e
cd "$(dirname "$0")"
V=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
./build.sh assembleTvRelease assemblePhoneRelease
cp app/build/outputs/apk/tv/release/app-tv-release.apk "/tmp/TV-Lock-$V.apk"
cp app/build/outputs/apk/phone/release/app-phone-release.apk "/tmp/TV-Lock-Phone-$V.apk"
gh release create "v$V" "/tmp/TV-Lock-$V.apk" --repo alexd-aero/tvlock --title "TV Lock $V" --notes "${1:-TV Lock $V}"
gh release upload "v$V" "/tmp/TV-Lock-Phone-$V.apk" --repo alexd-aero/tvlock
