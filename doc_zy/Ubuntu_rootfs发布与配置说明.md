# Ubuntu rootfs 发布与配置说明

本项目不会把 Ubuntu rootfs 压缩包直接提交到 Git 仓库。这个文件体积很大，放进普通 Git 历史会导致克隆、推送、回滚都变得很慢，所以当前采用“代码进仓库，rootfs 走 GitHub Release”的方式。

## 当前 rootfs 资源

- 发布页面：https://github.com/1194042851/ubuntu2204-termux-app/releases/tag/rootfs-ubuntu-22.04-arm64-c14ac1c6
- Release 上的 rootfs 文件名：`rootfs-arm64-c14ac1c6.tgz`
- Release 上的校验文件名：`rootfs-arm64.sha256`
- rootfs SHA-256：`c14ac1c6eb9482128e03fa1dcd8a3c590c06e19d75492f7a45aa97da7f28c171`
- rootfs 文件大小：`1543159425` 字节，约 `1.44 GiB`

我已经通过 GitHub API 检查过 Release 资产：

- `rootfs-arm64-c14ac1c6.tgz` 状态为 `uploaded`
- GitHub 返回的 digest 为 `sha256:c14ac1c6eb9482128e03fa1dcd8a3c590c06e19d75492f7a45aa97da7f28c171`
- `rootfs-arm64.sha256` 状态为 `uploaded`

也就是说，网页端上传的 rootfs 是可用的。

## 构建离线 APK 前如何配置

从 Release 页面下载这两个文件：

```text
rootfs-arm64-c14ac1c6.tgz
rootfs-arm64.sha256
```

下载完成后，把 `rootfs-arm64-c14ac1c6.tgz` 重命名为：

```text
rootfs-arm64.tgz
```

然后放到项目里的这个目录：

```text
app/src/main/assets/ubuntu/
```

最终应当得到：

```text
app/src/main/assets/ubuntu/rootfs-arm64.tgz
app/src/main/assets/ubuntu/rootfs-arm64.sha256
```

文件名必须保持一致。当前 App 首次安装器读取的是 APK 内部的：

```text
ubuntu/rootfs-arm64.tgz
ubuntu/rootfs-arm64.sha256
```

如果文件名不一致，APK 可以编译，但首次启动安装 Ubuntu 时会找不到 rootfs。

## 本地校验方式

在 PowerShell 里执行：

```powershell
Get-FileHash app\src\main\assets\ubuntu\rootfs-arm64.tgz -Algorithm SHA256
Get-Content app\src\main\assets\ubuntu\rootfs-arm64.sha256
```

确认 `Get-FileHash` 输出的哈希值等于：

```text
c14ac1c6eb9482128e03fa1dcd8a3c590c06e19d75492f7a45aa97da7f28c171
```

在 Linux 或 WSL 里也可以执行：

```bash
cd app/src/main/assets/ubuntu
sha256sum -c rootfs-arm64.sha256
```

## 构建离线 APK

放好 rootfs 和 sha256 文件后，在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK 会内置 Ubuntu rootfs。用户首次启动 App 时，安装器会把 rootfs 释放到设备的：

```text
/data/local/ubuntu-22.04
```

释放完成后，App 会通过 Termux 壳子进入 Ubuntu 22.04 终端。

## 重新制作 rootfs 后的更新流程

如果以后重新从设备或 WSL 制作了新的 rootfs，需要按下面流程更新：

1. 生成新的 `rootfs-arm64.tgz`。
2. 计算新的 SHA-256。
3. 上传新的 rootfs 压缩包和 sha256 文件到新的 GitHub Release。
4. 更新本文档里的 Release 链接、文件名、SHA-256 和文件大小。
5. 如果要构建离线 APK，把新的 rootfs 文件放回 `app/src/main/assets/ubuntu/` 后重新编译。
