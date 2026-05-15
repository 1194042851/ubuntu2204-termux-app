# Ubuntu rootfs 资源包

如果要构建“离线内置 Ubuntu rootfs”的 APK，请在编译前把下面两个文件放到当前目录：

- `rootfs-arm64.tgz`
- `rootfs-arm64.sha256`

这两个文件不会提交到 Git 仓库，因为 rootfs 压缩包体积很大，适合通过 GitHub Release 单独分发。

当前 rootfs 发布位置：

- 发布页面：https://github.com/1194042851/ubuntu2204-termux-app/releases/tag/rootfs-ubuntu-22.04-arm64-c14ac1c6
- `rootfs-arm64.tgz` 的 SHA-256：`c14ac1c6eb9482128e03fa1dcd8a3c590c06e19d75492f7a45aa97da7f28c171`
- 文件大小：`1543159425` 字节

构建离线 APK 前，请从发布页面下载 `rootfs-arm64.tgz` 和 `rootfs-arm64.sha256`，然后放到这里：

```text
app/src/main/assets/ubuntu/rootfs-arm64.tgz
app/src/main/assets/ubuntu/rootfs-arm64.sha256
```

然后在项目根目录执行编译：

```powershell
.\gradlew.bat :app:assembleDebug
```
