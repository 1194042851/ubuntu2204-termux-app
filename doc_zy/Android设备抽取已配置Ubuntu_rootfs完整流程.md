# Android 设备抽取已配置 Ubuntu rootfs 完整流程

## 1. 文档目标

这份文档整理的是一条已经在真机上跑通的流程：

1. 从一台已经 `root`、并且已经配置好 `Ubuntu 22.04 chroot` 环境的 Android 设备中，抽取现成的 rootfs。
2. 把现有的 Ubuntu 启动脚本一起抽取出来。
3. 在设备上把 rootfs 打成压缩包。
4. 通过 `adb pull` 拉回本地工程。
5. 作为 APK 内置资源继续用于后续打包、首次安装释放、复用和分发。

这不是泛泛而谈的说明，而是基于本次项目 `E:\study\termux_app` 的真实执行过程整理出来的操作手册。

本文会完整记录：

- 前提条件
- 实际使用的命令
- 命令怎么拼装
- 哪些尝试失败了
- 失败的原因是什么
- 最后成功的路径是什么
- 后续如何验证 rootfs 是否可用于 APK 内置安装

---

## 2. 适用场景

适用于下面这种情况：

- Android 设备已经 root
- 设备上已经有一套可运行的 Ubuntu chroot
- 已知 rootfs 路径，比如 `/data/local/ubuntu-22.04`
- 已知启动脚本路径，比如 `/data/local/bin/ubuntu22`
- 希望把这套已经配好的 Ubuntu 直接抽取出来，做成 APK 的内置资源

不适用于下面这种情况：

- 设备未 root
- 设备上还没有现成 Ubuntu rootfs
- 只想做在线下载 rootfs，不需要离线内置

---

## 3. 本次实际环境

### 3.1 本地工程

工程路径：

```text
E:\study\termux_app
```

目标是把 rootfs 最终放进：

```text
app/src/main/assets/ubuntu/
```

### 3.2 实际设备

本次实际连接到的 adb 设备：

```text
HA22WJ3X
```

检查命令：

```powershell
adb devices
```

### 3.3 设备上的 Ubuntu 相关路径

本次实际使用的路径：

```text
/data/local/ubuntu-22.04
/data/local/bin/ubuntu22
/data/local/bin/ubuntu22-stop
/data/local/bin/ubuntu22-sshd-start
/data/local/bin/ubuntu22-sshd-stop
/data/local/bin/ubuntu22-passwd
/data/adb/magisk/busybox
```

---

## 4. 抽取前提检查

### 4.1 确认 adb 已连通

```powershell
adb devices
```

预期结果里设备状态应该是 `device`，不是 `offline`、`unauthorized` 或空。

### 4.2 确认 root 权限可用

```powershell
adb shell su -c "id"
```

本次真实输出类似：

```text
uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0
```

如果这里失败，后面的 rootfs 抽取就不成立。

### 4.3 确认目标 rootfs、脚本和 busybox 都存在

```powershell
adb shell su -c "ls -ld /data/local/ubuntu-22.04 /data/local/bin /data/adb/magisk/busybox"
adb shell su -c "ls -l /data/local/bin/ubuntu22 /data/local/bin/ubuntu22-stop /data/local/bin/ubuntu22-sshd-start /data/local/bin/ubuntu22-sshd-stop /data/local/bin/ubuntu22-passwd"
```

### 4.4 确认 ABI

如果准备做内置 rootfs APK，最好先确认设备 ABI。

```powershell
adb shell getprop ro.product.cpu.abi
```

本次设备实际结果：

```text
arm64-v8a
```

这意味着这次抽出的 rootfs 是面向 `arm64-v8a` 的。

---

## 5. 抽取前一定要先做的事

### 5.1 先停止 Ubuntu 的挂载

这是整个流程里非常关键的一步。

如果 rootfs 当前正处于运行状态，`/proc`、`/sys`、`/dev`、`/mnt/shared` 这些目录可能已经挂载了宿主环境内容。直接打包会把脏内容带进去，后面做成 APK 后问题会很多。

因此要先执行停止脚本：

```powershell
adb shell "su -c '/data/local/bin/ubuntu22-stop || true'"
```

如果没有 stop 脚本，也要自己把 mount 卸掉。

可以顺手检查挂载是否已经清掉：

```powershell
adb shell "su -c 'mount | /system/bin/grep /data/local/ubuntu-22.04 || true'"
```

如果这里还有输出，说明 rootfs 里还有挂载没卸掉。

---

## 6. 先把启动脚本拉回本地

在打 rootfs 包之前，建议先把 `/data/local/bin` 下的入口脚本拉出来。

原因：

- 它们本身就是 APK 首次安装后要释放回设备的内容
- 这些脚本通常比 rootfs 更小、更稳定
- 即使后面 rootfs 打包中途出问题，脚本也先保住了

本次实际操作：

```powershell
New-Item -ItemType Directory -Force app\src\main\assets\ubuntu\bin | Out-Null

adb pull /data/local/bin/ubuntu22 app\src\main\assets\ubuntu\bin\ubuntu22
adb pull /data/local/bin/ubuntu22-stop app\src\main\assets\ubuntu\bin\ubuntu22-stop
adb pull /data/local/bin/ubuntu22-sshd-start app\src\main\assets\ubuntu\bin\ubuntu22-sshd-start
adb pull /data/local/bin/ubuntu22-sshd-stop app\src\main\assets\ubuntu\bin\ubuntu22-sshd-stop
adb pull /data/local/bin/ubuntu22-passwd app\src\main\assets\ubuntu\bin\ubuntu22-passwd
```

### 6.1 本次脚本内容的重要说明

本次设备里的 `ubuntu22` 脚本是：

```sh
#!/system/bin/sh
. /data/local/ubuntu-22.04/.host/ubuntu-core.sh
ensure_mounts
run_in_ubuntu "$@"
```

这说明这套 Ubuntu 的宿主启动逻辑还依赖 rootfs 内的：

```text
/data/local/ubuntu-22.04/.host/ubuntu-core.sh
```

因此后面做 APK 内置安装时，除了 rootfs 和 `/data/local/bin/ubuntu22*`，还要注意 `.host/ubuntu-core.sh` 这部分宿主逻辑。

---

## 7. 评估 rootfs 体积

建议在设备上先看看 rootfs 体积，免得打包打一半才发现不合理。

本次实际命令：

```powershell
adb shell "su -c '/data/adb/magisk/busybox du -shx /data/local/ubuntu-22.04'"
```

本次实际结果大约是：

```text
3.4G    /data/local/ubuntu-22.04
```

这说明：

- 未压缩 rootfs 约 3.4GB
- 做进 APK 后，最终包不会小
- 后面 APK 很可能在 1.5GB 到 2GB 量级

---

## 8. 在设备上打 rootfs 包

### 8.1 为什么在设备上打包

这是本次实际采用的方案：

- rootfs 已经在设备上
- 文件权限、symlink、owner 都是设备上的真实状态
- 用设备上的 root 和 busybox 打包，比先复制整棵目录到电脑再打包更直接

### 8.2 输出路径

本次把压缩包输出到：

```text
/data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz
```

对应 hash 文件：

```text
/data/local/tmp/ubuntu2204-rootfs-arm64.sha256
```

### 8.3 成功的打包命令

本次最终成功命令如下：

```powershell
adb shell "su -c 'rm -f /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz /data/local/tmp/ubuntu2204-rootfs-arm64.sha256; cd /data/local/ubuntu-22.04 && /data/adb/magisk/busybox tar --numeric-owner --exclude=./proc/* --exclude=./sys/* --exclude=./dev/* --exclude=./run/* --exclude=./tmp/* --exclude=./mnt/shared/* -czf /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz . && /data/adb/magisk/busybox sha256sum /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz > /data/local/tmp/ubuntu2204-rootfs-arm64.sha256 && chmod 644 /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz /data/local/tmp/ubuntu2204-rootfs-arm64.sha256'"
```

### 8.4 这个命令的结构说明

这条命令实际做了几件事：

1. 删除旧压缩包和旧 hash 文件
2. `cd` 到 rootfs 根目录
3. 用 Magisk busybox 的 `tar` 打包当前目录 `.`
4. 排除运行时目录
5. 生成 `sha256`
6. 改文件权限为 `644`

### 8.5 为什么这些 exclude 是必须的

本次使用的排除项：

```text
--exclude=./proc/*
--exclude=./sys/*
--exclude=./dev/*
--exclude=./run/*
--exclude=./tmp/*
--exclude=./mnt/shared/*
```

原因：

- `/proc`、`/sys`、`/dev` 是运行时挂载内容，不该进发行包
- `/run` 很多时候是临时 runtime 状态
- `/tmp` 不应该带入发行包
- `/mnt/shared` 是 bind mount 到 Android 存储，也不能打进 rootfs

---

## 9. 本次踩过的坑和失败尝试

### 9.1 失败尝试一：PowerShell 引号层级写错

一开始直接拼了很长的：

```powershell
adb shell "su -c '...很多命令...'"
```

但引号没有收对，PowerShell 报错：

```text
The string is missing the terminator: ".
```

### 9.1.1 原因

Windows PowerShell 有自己一套字符串解析规则，而 `adb shell`、`su -c`、内部 shell 又各自再解析一层。

这个场景至少有三层：

1. PowerShell
2. adb shell
3. su -c 后面的 shell

只要外层双引号、内层单引号没配平，就会在最外层先炸掉。

### 9.1.2 经验

推荐固定写法：

```powershell
adb shell "su -c '...shell 命令...'"
```

也就是：

- PowerShell 外层用双引号
- `su -c` 里面整段 shell 命令用单引号

这次后面所有成功命令基本都按这个模式写。

---

### 9.2 失败尝试二：直接照 GNU tar 写 `--one-file-system`

一开始我尝试过：

```text
--one-file-system
```

结果 busybox `tar` 直接报：

```text
busybox: unrecognized option `--one-file-system'
```

### 9.2.1 原因

Magisk 自带 busybox 的 `tar` 不是完整 GNU tar，不支持所有长参数。

### 9.2.2 结论

在这台设备上：

- `--numeric-owner` 可以用
- `--exclude=...` 可以用
- `--one-file-system` 不可用

所以本次最终方案是：

1. 先 `ubuntu22-stop`
2. 先确认挂载尽量卸掉
3. 再显式排除 `/proc`、`/sys`、`/dev`、`/run`、`/tmp`、`/mnt/shared`

---

### 9.3 失败尝试三：用错误方式检查挂载

有一轮我写过类似：

```powershell
adb shell su -c "mount | /data/adb/magisk/busybox grep /data/local/ubuntu-22.04 || true"
```

但因为引用和 shell 解析层级不对，报过：

```text
/system/bin/sh: /data/adb/magisk/busybox: inaccessible or not found
```

### 9.3.1 原因

并不是 busybox 真不存在，而是命令在错误的解析层被拆坏了。

### 9.3.2 后来的正确写法

本次后来使用更稳定的写法：

```powershell
adb shell "su -c 'mount | /system/bin/grep /data/local/ubuntu-22.04 || true'"
```

这个版本更稳定，原因是：

- `grep` 直接用系统已有的 `/system/bin/grep`
- 不再多套一层 busybox 子命令
- 外双内单结构稳定

---

### 9.4 失败尝试四：APK 内放 `.tar.gz` 被 Android 打包系统展开

这不是 rootfs 抽取阶段的问题，但和后续“怎么把抽出来的包放进 APK”直接相关，所以必须记录。

本次一开始把文件命名为：

```text
rootfs-arm64.tar.gz
```

并试图在 Gradle 里：

```gradle
androidResources {
    noCompress += ["gz", "tar"]
}
```

结果 APK 里实际出现的条目不是 `.tar.gz`，而是：

```text
assets/ubuntu/rootfs-arm64.tar
```

而且 APK 体积直接膨胀到大约 3.4GB。

### 9.4.1 原因

Android 资源打包链路会对某些后缀做特殊处理，`.tar.gz` 在这条链路上不够稳定。

### 9.4.2 本次最终解决方案

把文件重命名成：

```text
rootfs-arm64.tgz
```

并在 Gradle 里配置：

```gradle
androidResources {
    noCompress += ["tgz", "tar"]
}
```

这样 APK 内最终条目保持为：

```text
assets/ubuntu/rootfs-arm64.tgz
```

并且 `Length` 与 `CompressedLength` 相同，说明没有被二次压缩或错误展开。

---

## 10. 打包完成后的检查

### 10.1 检查设备上的压缩包大小和 hash

本次实际命令：

```powershell
adb shell "su -c 'ls -lh /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz /data/local/tmp/ubuntu2204-rootfs-arm64.sha256; cat /data/local/tmp/ubuntu2204-rootfs-arm64.sha256'"
```

本次实际结果大概是：

```text
1.6G  /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz
fe8555f802b0579be621173a28d882c98483ad50ea73b1a05b06abd4846ab17c  /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz
```

这说明：

- 原始 rootfs 约 3.4GB
- 压缩后 rootfs 约 1.6GB

---

## 11. 把 rootfs 拉回本地工程

### 11.1 拉回 hash 文件

```powershell
adb pull /data/local/tmp/ubuntu2204-rootfs-arm64.sha256 app\src\main\assets\ubuntu\rootfs-arm64.sha256
```

### 11.2 拉回 rootfs 包

先拉回原始 `.tar.gz`：

```powershell
adb pull /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz app\src\main\assets\ubuntu\rootfs-arm64.tar.gz
```

后面为了 APK 内置稳定，又把它在本地改名成：

```powershell
Move-Item -LiteralPath "E:\study\termux_app\app\src\main\assets\ubuntu\rootfs-arm64.tar.gz" -Destination "E:\study\termux_app\app\src\main\assets\ubuntu\rootfs-arm64.tgz"
```

### 11.3 本地再次校验 hash

```powershell
Get-FileHash app\src\main\assets\ubuntu\rootfs-arm64.tgz -Algorithm SHA256
Get-Content app\src\main\assets\ubuntu\rootfs-arm64.sha256
```

本次实际验证 hash 一致。

---

## 12. rootfs 抽出来以后，怎么用于 APK 内置安装

这部分不是“抽取”本身，但和重复流程强相关，因为抽取的最终目的就是给 APK 用。

### 12.1 目录结构

本次最终使用的 assets 结构：

```text
app/src/main/assets/ubuntu/
  rootfs-arm64.tgz
  rootfs-arm64.sha256
  bin/
    ubuntu22
    ubuntu22-stop
    ubuntu22-sshd-start
    ubuntu22-sshd-stop
    ubuntu22-passwd
  host/
    ubuntu-core.sh
```

### 12.2 首次安装的解压目标

APK 首次运行时，不直接解压到正式目录，而是：

```text
/data/local/ubuntu-22.04.tmp
```

成功后切换到：

```text
/data/local/ubuntu-22.04
```

必要时旧版本备份到：

```text
/data/local/ubuntu-22.04.bak
```

### 12.3 APK 安装器用的解压命令思路

本次 Android 端代码里走的是把 assets 中的 `tgz` 流式喂给 root shell：

```text
busybox tar -xzf - -C /data/local/ubuntu-22.04.tmp
```

这里的 `-` 表示从标准输入读取压缩包数据，而不是先把 1.6GB rootfs 整个复制到设备临时文件再解压。

这样做的好处：

- 少一次大文件落盘
- 安装器能直接从 APK assets 流式释放
- 进度更容易做

---

## 13. 首次安装完成后要验证什么

### 13.1 安装标记

建议安装完成后写：

```text
/data/local/ubuntu-22.04/.ubuntu2204-installed
```

本次实际 marker 内容类似：

```text
version=22.04
abi=arm64-v8a
rootfs_sha256=fe8555f802b0579be621173a28d882c98483ad50ea73b1a05b06abd4846ab17c
installed_at=...
installer_app=com.ubuntu2204
```

### 13.2 启动脚本是否被正确释放

检查：

```powershell
adb shell "su -c 'ls -l /data/local/bin/ubuntu22*'"
```

### 13.3 Ubuntu 是否能直接启动

```powershell
adb shell "su -c '/data/local/bin/ubuntu22 true; echo exit:$?'"
```

如果能正常返回成功，说明 rootfs 和入口脚本基本成立。

### 13.4 `resolv.conf` 是否可写

本次就踩过这个坑。

检查：

```powershell
adb shell "su -c 'ls -l /data/local/ubuntu-22.04/etc/resolv.conf; cat /data/local/ubuntu-22.04/etc/resolv.conf'"
```

如果它是坏掉的 symlink，就要在宿主脚本里修正成普通文件。

---

## 14. 推荐的完整成功流程

下面给出一版适合后续重复执行的推荐流程。

### 14.1 设备侧确认

```powershell
adb devices
adb shell su -c "id"
adb shell getprop ro.product.cpu.abi
adb shell su -c "ls -ld /data/local/ubuntu-22.04 /data/local/bin /data/adb/magisk/busybox"
adb shell su -c "ls -l /data/local/bin/ubuntu22 /data/local/bin/ubuntu22-stop /data/local/bin/ubuntu22-sshd-start /data/local/bin/ubuntu22-sshd-stop /data/local/bin/ubuntu22-passwd"
```

### 14.2 停止挂载

```powershell
adb shell "su -c '/data/local/bin/ubuntu22-stop || true'"
adb shell "su -c 'mount | /system/bin/grep /data/local/ubuntu-22.04 || true'"
```

### 14.3 先拉启动脚本

```powershell
New-Item -ItemType Directory -Force app\src\main\assets\ubuntu\bin | Out-Null

adb pull /data/local/bin/ubuntu22 app\src\main\assets\ubuntu\bin\ubuntu22
adb pull /data/local/bin/ubuntu22-stop app\src\main\assets\ubuntu\bin\ubuntu22-stop
adb pull /data/local/bin/ubuntu22-sshd-start app\src\main\assets\ubuntu\bin\ubuntu22-sshd-start
adb pull /data/local/bin/ubuntu22-sshd-stop app\src\main\assets\ubuntu\bin\ubuntu22-sshd-stop
adb pull /data/local/bin/ubuntu22-passwd app\src\main\assets\ubuntu\bin\ubuntu22-passwd
```

### 14.4 看 rootfs 体积

```powershell
adb shell "su -c '/data/adb/magisk/busybox du -shx /data/local/ubuntu-22.04'"
```

### 14.5 在设备上打包

```powershell
adb shell "su -c 'rm -f /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz /data/local/tmp/ubuntu2204-rootfs-arm64.sha256; cd /data/local/ubuntu-22.04 && /data/adb/magisk/busybox tar --numeric-owner --exclude=./proc/* --exclude=./sys/* --exclude=./dev/* --exclude=./run/* --exclude=./tmp/* --exclude=./mnt/shared/* -czf /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz . && /data/adb/magisk/busybox sha256sum /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz > /data/local/tmp/ubuntu2204-rootfs-arm64.sha256 && chmod 644 /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz /data/local/tmp/ubuntu2204-rootfs-arm64.sha256'"
```

### 14.6 看打包结果

```powershell
adb shell "su -c 'ls -lh /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz /data/local/tmp/ubuntu2204-rootfs-arm64.sha256; cat /data/local/tmp/ubuntu2204-rootfs-arm64.sha256'"
```

### 14.7 拉回本地

```powershell
New-Item -ItemType Directory -Force app\src\main\assets\ubuntu | Out-Null
adb pull /data/local/tmp/ubuntu2204-rootfs-arm64.sha256 app\src\main\assets\ubuntu\rootfs-arm64.sha256
adb pull /data/local/tmp/ubuntu2204-rootfs-arm64.tar.gz app\src\main\assets\ubuntu\rootfs-arm64.tar.gz
Move-Item -LiteralPath "E:\study\termux_app\app\src\main\assets\ubuntu\rootfs-arm64.tar.gz" -Destination "E:\study\termux_app\app\src\main\assets\ubuntu\rootfs-arm64.tgz"
```

### 14.8 本地校验 hash

```powershell
Get-FileHash app\src\main\assets\ubuntu\rootfs-arm64.tgz -Algorithm SHA256
Get-Content app\src\main\assets\ubuntu\rootfs-arm64.sha256
```

### 14.9 APK 侧注意事项

Gradle 中建议：

```gradle
androidResources {
    noCompress += ["tgz", "tar"]
}
```

不要继续用 `.tar.gz` 当 APK 内资源名，优先改成 `.tgz`。

---

## 15. 后续维护建议

### 15.1 不要把“抽取 rootfs”理解成一次性工作

rootfs 抽取后，后续还会遇到：

- rootfs 更新
- 启动脚本更新
- `.host/ubuntu-core.sh` 修补
- `resolv.conf`、DNS、挂载策略修正
- `sshd` 或 `passwd` 脚本修正

所以建议把这套流程当成“可重复执行的制品生成流程”，而不是临时手工操作。

### 15.2 建议保留三个版本信息

建议后续在文档或 marker 里维护：

- rootfs SHA-256
- rootfs 来源设备/版本
- 宿主脚本版本

这样后面的人更容易判断当前 APK 里到底内置的是哪一版 Ubuntu。

### 15.3 建议把抽取流程脚本化

如果后面这件事会反复做，建议再往前走一步：

- 把设备侧打包命令固化成一个 `.ps1`
- 把本地 pull 和 rename 固化成一个 `.ps1`
- 最后输出一份标准 manifest

但在脚本化之前，应该先用本文档把手工过程吃透。

---

## 16. 本次最终结果

本次最终成功抽取的 rootfs 制品是：

```text
E:\study\termux_app\app\src\main\assets\ubuntu\rootfs-arm64.tgz
```

相关文件还有：

```text
E:\study\termux_app\app\src\main\assets\ubuntu\rootfs-arm64.sha256
E:\study\termux_app\app\src\main\assets\ubuntu\bin\ubuntu22
E:\study\termux_app\app\src\main\assets\ubuntu\bin\ubuntu22-stop
E:\study\termux_app\app\src\main\assets\ubuntu\bin\ubuntu22-sshd-start
E:\study\termux_app\app\src\main\assets\ubuntu\bin\ubuntu22-sshd-stop
E:\study\termux_app\app\src\main\assets\ubuntu\bin\ubuntu22-passwd
E:\study\termux_app\app\src\main\assets\ubuntu\host\ubuntu-core.sh
```

这套资源随后已经用于 APK 内置安装，并完成过真实的：

- 卸载 app
- 删除旧 rootfs
- 重新安装 APK
- 首次启动自动解压
- 进入 Ubuntu shell

因此本文档记录的流程，不只是理论可行，而是已经在当前工程和当前设备上验证通过的。
