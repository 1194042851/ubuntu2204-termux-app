# Ubuntu rootfs 发布与配置说明

项目源码不会直接提交 Ubuntu rootfs 压缩包。这个文件体积很大，不适合放进普通 Git 历史里，推荐通过 GitHub Release 单独分发。

## 当前 rootfs 资源

- 发布页面：https://github.com/1194042851/ubuntu2204-termux-app/releases/tag/rootfs-ubuntu-22.04-arm64-c14ac1c6
- rootfs 压缩包：`rootfs-arm64.tgz`
- 校验文件：`rootfs-arm64.sha256`
- SHA-256：`c14ac1c6eb9482128e03fa1dcd8a3c590c06e19d75492f7a45aa97da7f28c171`
- 文件大小：`1543159425` 字节

## 编译前放置位置

从发布页面下载 `rootfs-arm64.tgz` 和 `rootfs-arm64.sha256`，然后放到下面这个目录：

```text
app/src/main/assets/ubuntu/rootfs-arm64.tgz
app/src/main/assets/ubuntu/rootfs-arm64.sha256
```

文件名必须保持一致。当前安装器代码会读取 APK 里的 `ubuntu/rootfs-arm64.tgz`，并使用 `ubuntu/rootfs-arm64.sha256` 做 SHA-256 校验。

## 本地校验

在 PowerShell 里执行：

```powershell
Get-FileHash app\src\main\assets\ubuntu\rootfs-arm64.tgz -Algorithm SHA256
Get-Content app\src\main\assets\ubuntu\rootfs-arm64.sha256
```

在 Linux 或 WSL 里执行：

```bash
cd app/src/main/assets/ubuntu
sha256sum -c rootfs-arm64.sha256
```

## 构建离线 APK

放回这两个文件后，在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK 会内置 rootfs。首次启动时，App 会把 rootfs 释放到设备的 `/data/local/ubuntu-22.04`，然后进入 Ubuntu 终端。
