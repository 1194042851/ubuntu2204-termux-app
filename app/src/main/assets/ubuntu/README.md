# Ubuntu rootfs 资源说明

这个目录用于放置构建离线 APK 时需要内置的 Ubuntu 22.04 arm64 rootfs。

因为 rootfs 压缩包体积很大，不适合直接提交到 Git 仓库，所以代码仓库只保留说明文件，真实的 rootfs 文件通过 GitHub Release 单独分发。

## 当前发布资源

- 发布页面：https://github.com/1194042851/ubuntu2204-termux-app/releases/tag/rootfs-ubuntu-22.04-arm64-c14ac1c6
- Release 上的 rootfs 文件名：`rootfs-arm64-c14ac1c6.tgz`
- Release 上的校验文件名：`rootfs-arm64.sha256`
- rootfs SHA-256：`c14ac1c6eb9482128e03fa1dcd8a3c590c06e19d75492f7a45aa97da7f28c171`
- rootfs 文件大小：`1543159425` 字节，约 `1.44 GiB`

## 构建前放置方式

从 Release 页面下载这两个文件：

- `rootfs-arm64-c14ac1c6.tgz`
- `rootfs-arm64.sha256`

下载后，把 rootfs 压缩包重命名为：

```text
rootfs-arm64.tgz
```

然后把两个文件放到当前目录，最终路径必须是：

```text
app/src/main/assets/ubuntu/rootfs-arm64.tgz
app/src/main/assets/ubuntu/rootfs-arm64.sha256
```

注意：App 的首次安装器代码读取的是 APK 内部的 `ubuntu/rootfs-arm64.tgz` 和 `ubuntu/rootfs-arm64.sha256`，所以本地构建前文件名必须保持一致。

## 构建命令

在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK 会内置 rootfs。用户首次启动 App 时，会把 rootfs 释放到设备的 `/data/local/ubuntu-22.04`，然后进入 Ubuntu 终端。
