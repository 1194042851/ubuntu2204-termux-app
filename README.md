# ubuntu2204 Android App

This repository contains a Termux-based Android app that boots directly into an embedded Ubuntu 22.04 rootfs.

It is not a generic Termux distribution. The current project is a focused Ubuntu launcher/runtime with these goals:

- ship an `arm64-v8a` Ubuntu 22.04 rootfs inside the APK
- install the rootfs on first launch
- boot straight into Ubuntu after installation
- keep the familiar Termux terminal UI as the frontend shell

## What This Project Does

The app installs and launches Ubuntu through a root-backed flow:

1. Install the APK
2. Start the app for the first time
3. Check ABI, root access, Magisk busybox, and free space
4. Verify the embedded rootfs SHA-256
5. Extract the rootfs to `/data/local/ubuntu-22.04.tmp`
6. Write host-side helper scripts and launcher scripts
7. Move the installation into `/data/local/ubuntu-22.04`
8. Launch the default Ubuntu terminal session

## Current Status

The current implementation has already been validated on a real device with the following full cycle:

- uninstall the app
- remove the old Ubuntu rootfs and launcher scripts
- reinstall the APK
- run the first-launch installer
- enter the Ubuntu shell successfully

The project is therefore working as a real root-only Ubuntu APK, but it should still be treated as an engineering validation build rather than a polished end-user product.

## Requirements

This APK currently expects all of the following:

- a rooted Android device
- working `su`
- a Magisk-style environment
- executable `/data/adb/magisk/busybox`
- `arm64-v8a`
- enough free space on `/data`

If those requirements are not met, the current build is expected to fail.

## Install Layout

### Embedded APK assets

- [app/src/main/assets/ubuntu/rootfs-arm64.tgz](app/src/main/assets/ubuntu/rootfs-arm64.tgz)
- [app/src/main/assets/ubuntu/rootfs-arm64.sha256](app/src/main/assets/ubuntu/rootfs-arm64.sha256)
- [app/src/main/assets/ubuntu/bin](app/src/main/assets/ubuntu/bin)
- [app/src/main/assets/ubuntu/host](app/src/main/assets/ubuntu/host)

### Installed device paths

- `/data/local/ubuntu-22.04`
- `/data/local/ubuntu-22.04/.ubuntu2204-installed`
- `/data/local/bin/ubuntu22`
- `/data/local/bin/ubuntu22-stop`
- `/data/local/bin/ubuntu22-sshd-start`
- `/data/local/bin/ubuntu22-sshd-stop`
- `/data/local/bin/ubuntu22-passwd`

## Key Code Paths

- [UbuntuRootfsInstaller.java](app/src/main/java/com/termux/app/UbuntuRootfsInstaller.java)
- [UbuntuRootUtils.java](app/src/main/java/com/termux/app/UbuntuRootUtils.java)
- [TermuxActivity.java](app/src/main/java/com/termux/app/TermuxActivity.java)
- [TermuxService.java](app/src/main/java/com/termux/app/TermuxService.java)

## Build

Run the debug build from the repository root:

```powershell
.\gradlew.bat assembleDebug --console=plain --no-daemon
```

The current project defaults to `arm64-v8a`.

Expected debug APK output:

```text
app/build/outputs/apk/debug/ubuntu2204_apt-android-7-debug_arm64-v8a.apk
```

## Important Limitations

- root-only
- `arm64-v8a` only
- currently tied to Magisk busybox
- very large APK because the rootfs is bundled directly
- uninstalling the APK does not remove `/data/local/ubuntu-22.04`
- upgrade and migration behavior still need more work

## Recommended Next Steps

- add a dedicated Ubuntu management screen
- add explicit rootfs and script versioning
- support reinstall, repair, and cleanup actions
- improve `su` and busybox compatibility fallback
- improve user-facing preflight guidance and failure messages

## Documentation

- [docs/en/index.md](docs/en/index.md)
- [docs/en/ubuntu-rootfs.md](docs/en/ubuntu-rootfs.md)

## Credits

This project is built on top of the Termux app codebase and reuses its terminal frontend and supporting infrastructure while replacing the default runtime flow with an Ubuntu-first launch path.
