# TV Lock

A PIN lock for Android TV and Google TV boxes. Instead of the home screen, the TV shows a PIN screen when it
starts, wakes up or switches back to the box's HDMI input. It can also lock just the apps you pick, or both.

- 4-digit PIN by default; 6-digit, any length (4–16) or a password with letters and symbols
- Works with the remote's D-pad, the number buttons on the remote, or a keyboard
- Locks again when the TV sleeps, when you switch inputs (HDMI-CEC) or after a while with no remote use
- Wrong tries pause the lock: 5 free tries, then 30 s, 1 min, 2 min … up to 15 min
- Profiles: everyone gets their own name, picture and PIN; the TV greets whoever unlocks it ("Hello, Bob")
- Phone remote on the home network: unlock, lock, D-pad, media keys, typing, apps and settings (no port forwarding)
- Two APKs: TV-Lock (the TV app with the lock) and TV-Lock-Phone (the companion: finds the TV, sets it up
  automatically over ADB, remote, several TVs). The phone APK only asks for network access and its own updates.
- Updates itself from this repo's GitHub Releases, keeping the PIN and settings

## Install

**Easiest:** install TV Lock on the TV, open it and pick **Set up with your phone**. Scan the code, get the app on
your phone, and it sets up the TV for you (it needs network debugging on the TV and one press of Allow).

**By hand:**

1. Download `TV-Lock-<version>.apk` from [Releases](https://github.com/alexd-aero/tvlock/releases) and sideload it
   (or `adb install TV-Lock-<version>.apk`).
2. Open TV Lock and follow the setup: allow Accessibility, choose what to lock, set your PIN, test it.

If Accessibility for TV Lock is greyed out (Android 13+, apps installed from a file), open App info for TV Lock and
pick **Allow restricted settings**, or run:

```sh
adb shell appops set dev.alexaero.tvlock ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services dev.alexaero.tvlock/.LockService
adb shell settings put secure accessibility_enabled 1
```

### Switching HDMI inputs

TV Lock watches the box's HDMI-CEC state: when the TV switches to another input, it puts the PIN screen up (the box
stays on), so it's waiting when you switch back. Automatic setup grants what it needs; by hand:

```sh
adb shell pm grant dev.alexaero.tvlock android.permission.DUMP
```

### Locked out?

`adb uninstall dev.alexaero.tvlock` removes the lock.

## How it works

- `LockService` (accessibility service) sees which app is on screen and draws the lock as an accessibility
  overlay above everything, so Home and Back can't close it. If a TV never gives that window the remote's keys,
  it falls back to a full-screen `LockActivity`.
- The service declares *visual* feedback: launchers such as `com.google.android.tvlauncher` switch to an
  accessible mode (a menu on every click) when a *generic* or *spoken* feedback service is on.
- While locked, TV Lock holds audio focus, so whatever is behind the lock pauses.
- The PIN is stored as a salted PBKDF2 hash.

## Build

```sh
./build.sh                   # fast dev APKs for testing (no R8, same key): app/build/outputs/apk/{tv,phone}/dev/
./build.sh assembleRelease   # the small shrunk APKs: app/build/outputs/apk/{tv,phone}/release/
./release.sh "notes"         # release build + GitHub release (TV APK first, then the phone APK)
```

The Gradle daemon stays warm for 3 hours, so after the first build a small change rebuilds in seconds.

```sh
```

Release builds are signed with the key in `keystore.properties` (not in the repo). Updates must be signed with the
same key, or Android refuses to install them over the existing app.
