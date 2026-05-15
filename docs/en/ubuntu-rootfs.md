# Ubuntu Rootfs Integration

## Overview

This project embeds an Ubuntu 22.04 rootfs directly inside the APK and installs it on first launch.

The current design is intentionally opinionated:

- root-only
- `arm64-v8a` only
- default install location is `/data/local/ubuntu-22.04`
- startup scripts are installed into `/data/local/bin`

## Runtime Flow

When the app starts and no existing Ubuntu backend is available, it performs the following steps:

1. Check device ABI
2. Check root access
3. Check Magisk busybox availability
4. Check free space on `/data`
5. Verify the embedded rootfs SHA-256
6. Extract the rootfs to `/data/local/ubuntu-22.04.tmp`
7. Write the host-side Ubuntu helper script
8. Write the install marker
9. Move the temp directory into `/data/local/ubuntu-22.04`
10. Write `/data/local/bin/ubuntu22*`
11. Launch the default Ubuntu terminal session

## Paths

### Embedded assets

- `app/src/main/assets/ubuntu/rootfs-arm64.tgz`
- `app/src/main/assets/ubuntu/rootfs-arm64.sha256`
- `app/src/main/assets/ubuntu/bin/*`
- `app/src/main/assets/ubuntu/host/ubuntu-core.sh`

### Installed paths on device

- `/data/local/ubuntu-22.04`
- `/data/local/ubuntu-22.04/.ubuntu2204-installed`
- `/data/local/bin/ubuntu22`
- `/data/local/bin/ubuntu22-stop`
- `/data/local/bin/ubuntu22-sshd-start`
- `/data/local/bin/ubuntu22-sshd-stop`
- `/data/local/bin/ubuntu22-passwd`

## Main Code Locations

- [UbuntuRootfsInstaller.java](../../app/src/main/java/com/termux/app/UbuntuRootfsInstaller.java)
- [UbuntuRootUtils.java](../../app/src/main/java/com/termux/app/UbuntuRootUtils.java)
- [TermuxActivity.java](../../app/src/main/java/com/termux/app/TermuxActivity.java)
- [TermuxService.java](../../app/src/main/java/com/termux/app/TermuxService.java)

## Current Constraints

The current implementation has several important constraints:

- It depends on root access and does not support non-root devices.
- It assumes a Magisk-compatible environment and expects `/data/adb/magisk/busybox`.
- It installs outside app-private storage, so uninstalling the APK does not remove the Ubuntu rootfs.
- The APK is very large because the rootfs is bundled directly inside it.
- Upgrade and migration logic is still basic and should be improved before broader distribution.

## Recommended Next Steps

- Add a dedicated Ubuntu management screen
- Add explicit rootfs and script versioning
- Add reinstall, repair, and cleanup actions
- Improve `su` and busybox compatibility fallback
- Add user-facing preflight guidance before install starts
