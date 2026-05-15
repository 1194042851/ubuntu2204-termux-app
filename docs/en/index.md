---
page_ref: /docs/apps/termux/index.html
---

# ubuntu2204 App Docs

This repository is a Termux-based Android app that boots directly into an embedded Ubuntu 22.04 rootfs.

It is not a generic Termux distribution. The current implementation targets:

- rooted Android devices
- `arm64-v8a`
- a Magisk-style `su` environment
- a local Ubuntu rootfs extracted to `/data/local/ubuntu-22.04`

## Documentation

- [Ubuntu Rootfs Integration](./ubuntu-rootfs.md)

## Summary

The APK contains:

- terminal UI based on Termux
- Ubuntu bootstrap and launch scripts
- an embedded `arm64` Ubuntu rootfs archive
- a first-run installer that validates and extracts the rootfs

After installation, the default terminal session launches Ubuntu instead of a regular Android shell.
