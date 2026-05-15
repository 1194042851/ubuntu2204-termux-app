# Termux 改造成 Ubuntu 22.04 APK 开发复盘

本文记录本项目把 Termux App 二次开发成 `ubuntu2204` APK 的完整过程。内容覆盖需求、可行性判断、root 和 no-root 方案对比、最终架构、代码改动、构建安装、测试验证、踩坑复盘、故障恢复和后续维护建议。

这不是通用教程，而是基于当前仓库 `E:\study\termux_app`、当前 Android 设备和当前已验证 Ubuntu 22.04 后端整理出来的工程文档。

## 1. 需求和目标

### 1.1 原始需求

目标是开发一个安装在 Android 设备上的 APK，外观和交互尽量复用 Termux：

- 复用 Termux 的终端界面。
- 复用 Termux 的 session 管理逻辑。
- 复用 Termux 的额外快捷键栏，例如 `ESC`、`CTRL`、`ALT`、方向键、`PGUP`、`PGDN`。
- 应用打开后不要进入 Termux 自带 `$PREFIX` 环境。
- 应用打开后直接进入 Ubuntu 22.04 环境。
- APK 名称为 `ubuntu2204`。
- 当前优先做 root 版本。

用户已有一套经过验证的 Ubuntu 22.04 chroot 环境，文档位于：

```text
E:\study\termux_app\doc_zy\安卓平板安装与接入Ubuntu22.04全过程.md
```

该文档记录了 Android 平板上安装、挂载和接入 Ubuntu 22.04 的过程。本次 APK 改造不是从零创建 Ubuntu rootfs，而是让 APK 直接调用这套已经可用的后端。

### 1.2 当前阶段的实际目标

本次最终落地的目标不是“把 Ubuntu rootfs 打包进 APK”，而是：

- APK 自身提供 Termux UI 壳子。
- 设备端已有 Ubuntu rootfs 位于 `/data/local/ubuntu-22.04`。
- 设备端已有启动脚本 `/data/local/bin/ubuntu22`。
- APK 默认 session 通过 Magisk root 进入该 chroot 环境。
- 不再安装 Termux bootstrap。
- 不再依赖 Termux `$PREFIX` 作为默认 shell 环境。

也就是说，当前实现是：

```text
ubuntu2204 APK
  -> Termux terminal UI
  -> Termux session/JNI/PTY 逻辑
  -> /system/bin/su
  -> Magisk su context
  -> /system/bin/sh /data/local/bin/ubuntu22
  -> busybox chroot /data/local/ubuntu-22.04
  -> /bin/bash --login
```

### 1.3 当前没有做的事情

当前版本没有做以下事情：

- 没有把 Ubuntu rootfs 嵌入 APK。
- 没有在首次启动时自动解压 rootfs。
- 没有做 no-root proot 容器版本。
- 没有把 Java 源码包名从 `com.termux.*` 全量重命名为 `com.ubuntu2204.*`。
- 没有实现完整的后端管理 UI，例如启动 SSH、停止 SSH、停止挂载、修改 root 密码按钮。

这些都可以作为后续阶段继续开发。

## 2. 可行性判断

### 2.1 思路本身可行

把 Termux 改造成一个 Ubuntu 入口是可行的。关键点在于 Termux 本身分成两层：

- UI 和终端交互层。
- 默认 shell 和 `$PREFIX` 环境层。

用户真正想复用的是第一层，也就是：

- `TerminalView`
- 终端渲染
- 输入输出
- PTY 子进程
- session 生命周期
- extra keys 快捷键栏
- 复制、粘贴、通知、前台服务等 Android 逻辑

不需要复用的是第二层，也就是 Termux 自己的 bootstrap、`/data/data/com.termux/files/usr`、apt 包环境。

因此工程上可以保留 Termux UI 和 session 框架，把默认启动的 shell 从 Termux shell 换成 Ubuntu chroot 入口。

### 2.2 root 版本和 no-root 版本的本质区别

Android 上跑 Ubuntu 用户空间大体有两种方式：

| 方案 | 核心机制 | 是否需要 root | 性能 | 系统完整性 | 适合场景 |
| --- | --- | --- | --- | --- | --- |
| root + chroot | 内核级 chroot、mount bind、真实 root 权限 | 需要 | 接近原生 | 更完整 | 已 root 设备、开发环境、SSH、系统服务 |
| no-root + proot | 用户态 syscall 翻译和路径重写 | 不需要 | 明显低于 chroot | 有限制 | 普通用户设备、无需 root、兼容性优先 |

本项目优先选择 root + chroot，因为设备已经 root，且已有 `/data/local/ubuntu-22.04` 后端。这个方案性能更好，也更接近真实 Linux 用户空间。

### 2.3 是否可以把 rootfs 集成到 APK

理论上可以，但不是当前阶段的最佳路径。

如果要把 rootfs 放进 APK，需要处理：

- APK 体积会非常大。当前 rootfs 约 600 MB，压缩后也很容易超出普通 APK 分发习惯。
- APK 内的 assets 或 raw 资源是只读的，Ubuntu rootfs 必须首次启动时解压到可写目录。
- chroot 通常更适合放在 `/data/local/ubuntu-22.04` 这种 root 可控路径，而不是普通 app 私有目录。
- 首次解压时间长，失败恢复复杂。
- rootfs 更新机制需要额外设计。
- 不同 ABI 要分包或内置不同 rootfs。
- 如果没 root，仍然不能 chroot，只能 proot。

因此当前更稳妥的工程路径是：

```text
先固定一个已经验证过的设备后端路径
再把 APK 做成这个后端的图形终端入口
最后再考虑是否内置 rootfs 和自动安装器
```

## 3. 已验证设备和后端环境

### 3.1 PC 侧环境

工作目录：

```text
E:\study\termux_app
```

ADB 路径：

```text
E:\study\platform-tools\adb.exe
```

构建命令使用 Windows PowerShell：

```powershell
$env:TERMUX_TARGET_ABI='arm64-v8a'
.\gradlew.bat :app:assembleDebug
```

### 3.2 设备信息

实际验证设备：

```text
品牌/型号: OnePlus OPD2404
Android: 15
SDK: 35
ABI: arm64-v8a
SELinux: enforcing
Root: Magisk 可用
```

root 验证：

```sh
su -c id
```

预期返回：

```text
uid=0(root)
```

### 3.3 Ubuntu 后端路径

当前设备上已经存在并验证可用的后端：

```text
/data/local/ubuntu-22.04
/data/local/bin/ubuntu22
/data/local/bin/ubuntu22-stop
/data/local/bin/ubuntu22-sshd-start
/data/local/bin/ubuntu22-sshd-stop
/data/local/bin/ubuntu22-passwd
/data/adb/magisk/busybox
```

最关键的入口脚本：

```text
/data/local/bin/ubuntu22
```

它的职责是：

1. 引入 `/data/local/ubuntu-22.04/.host/ubuntu-core.sh`。
2. 执行 `ensure_mounts`。
3. 执行 `run_in_ubuntu "$@"`。
4. 最终进入 `busybox chroot /data/local/ubuntu-22.04 /bin/bash --login`。

核心后端脚本逻辑大致如下：

```sh
ROOTFS=/data/local/ubuntu-22.04
BUSYBOX=/data/adb/magisk/busybox

ensure_mounts() {
  mkdir -p "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev" "$ROOTFS/dev/pts" \
    "$ROOTFS/mnt/shared" "$ROOTFS/run" "$ROOTFS/tmp"

  mount -t proc proc "$ROOTFS/proc"
  mount -t sysfs sysfs "$ROOTFS/sys"
  mount -o bind /dev "$ROOTFS/dev"
  mount -t devpts devpts "$ROOTFS/dev/pts"
  mount -o bind /storage/emulated/0 "$ROOTFS/mnt/shared"
}

run_in_ubuntu() {
  exec "$BUSYBOX" chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root \
    LANG=zh_CN.UTF-8 \
    LC_ALL=zh_CN.UTF-8 \
    TERM="${TERM:-xterm-256color}" \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash --login
}
```

## 4. 最终架构

### 4.1 应用结构

最终应用包名：

```text
com.ubuntu2204
```

最终应用名：

```text
ubuntu2204
```

源码 Java package 仍然保留：

```text
com.termux.*
```

这个选择很重要。为了控制改动范围，没有把所有 Java 源码包名重命名。这样能减少大量连锁修改，也更容易继续跟踪 Termux 原项目结构。

因此工程上形成了两个概念：

| 概念 | 当前值 | 说明 |
| --- | --- | --- |
| Android applicationId | `com.ubuntu2204` | 系统安装包名 |
| Java 源码包名 | `com.termux.*` | 源码目录和类名仍沿用 Termux |
| App 显示名 | `ubuntu2204` | Launcher 和通知里看到的名称 |

### 4.2 默认启动链路

最终默认启动命令不是直接执行 Ubuntu 脚本，而是：

```text
/system/bin/su -Z u:r:su:s0 -c "PATH=/system/bin:/system/xbin:/product/bin:/system_ext/bin:/vendor/bin:/vendor/xbin:/odm/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin /system/bin/sh /data/local/bin/ubuntu22"
```

其中每一段都有原因：

- `/system/bin/su`：由 Magisk 提供 root 权限。
- `-Z u:r:su:s0`：让命令运行在 Magisk su SELinux context 中。
- `-c`：让 su 的 shell 执行后面的命令片段。
- `PATH=...`：给 Android 侧启动脚本提供 `mkdir`、`grep`、`chmod`、`getprop`、`mount` 等命令路径。
- `/system/bin/sh`：显式用 Android 系统 shell 执行后端脚本。
- `/data/local/bin/ubuntu22`：进入已经验证过的 Ubuntu 22.04 chroot 后端。

### 4.3 为什么需要 `su -Z u:r:su:s0`

最开始只用普通 `su -c` 时，日志中出现过类似问题：

```text
avc: denied { execute } for name="ubuntu22" ... scontext=u:r:untrusted_app_27 ...
```

这说明 app 进程虽然能调用 `su`，但终端 session 的执行路径仍然可能被 SELinux 视为从 `untrusted_app` 域执行 `/data/local/bin/ubuntu22`，从而被拒绝。

解决方法不是关闭 SELinux，也不是修改系统策略，而是让 Magisk su 把命令放到 `u:r:su:s0` context 执行：

```sh
su -Z u:r:su:s0 -c '...'
```

这样 Ubuntu 启动脚本、mount、chroot 等 root 操作都发生在 su 域中，而不是普通 app 域中。

### 4.4 为什么不能使用 `su -mm`

中间测试过：

```sh
su -mm -Z u:r:su:s0 -c '...'
```

`-mm` 是 Magisk 的 mount master/global mount namespace 选项。它会让 root 命令运行在全局 mount namespace 中。

这个选项在本项目里是坑，不能用于默认启动。

原因是 Ubuntu 后端脚本会做 bind mount 和 umount。如果放到全局 mount namespace，就可能污染系统级 `/dev`、`/dev/binderfs` 等挂载状态。实际测试中出现过系统命令报错：

```text
Binder driver '2 (No such file or directory) Opening '/dev/binder' failed
```

这类错误会影响 `cmd package`、`dumpsys`、`uiautomator` 等依赖 binder 的 Android 命令。

最终结论：

```text
只使用 -Z，不使用 -mm。
```

### 4.5 为什么需要显式 Android PATH

使用 `su -Z` 后，终端里一度出现过：

```text
/data/local/bin/ubuntu22[3]: mkdir: inaccessible or not found
/data/local/bin/ubuntu22[3]: grep: inaccessible or not found
/data/local/bin/ubuntu22[3]: chmod: inaccessible or not found
/data/local/bin/ubuntu22[3]: getprop: inaccessible or not found
```

原因不是 rootfs 损坏，也不是脚本不存在，而是 `su -Z` 执行环境中的 `PATH` 不完整。Android 侧后端脚本在 chroot 之前需要调用 Android 系统命令，例如：

- `mkdir`
- `grep`
- `chmod`
- `getprop`
- `mount`

如果 `PATH` 没有 `/system/bin` 等路径，这些命令会找不到。

最终修复是在执行 `/system/bin/sh /data/local/bin/ubuntu22` 之前显式注入：

```text
PATH=/system/bin:/system/xbin:/product/bin:/system_ext/bin:/vendor/bin:/vendor/xbin:/odm/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin
```

## 5. 主要代码改动

### 5.1 新增 UbuntuRootUtils

新增文件：

```text
app/src/main/java/com/termux/app/UbuntuRootUtils.java
```

这个类集中管理 Ubuntu 后端路径、su 参数、预检查逻辑和默认 session 参数。

关键常量：

```java
public static final String SYSTEM_SU_PATH = "/system/bin/su";
public static final String SYSTEM_SH_PATH = "/system/bin/sh";
public static final String MAGISK_SU_CONTEXT = "u:r:su:s0";
public static final String ANDROID_SYSTEM_PATH = "/system/bin:/system/xbin:/product/bin:/system_ext/bin:/vendor/bin:/vendor/xbin:/odm/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin";
public static final String UBUNTU_ENTRYPOINT = "/data/local/bin/ubuntu22";
public static final String UBUNTU_ROOTFS_PATH = "/data/local/ubuntu-22.04";
public static final String MAGISK_BUSYBOX_PATH = "/data/adb/magisk/busybox";
```

默认 session 参数：

```java
@NonNull
public static String[] getDefaultUbuntuSessionArguments() {
    return buildSuArguments(buildShellCommand(UBUNTU_ENTRYPOINT, null));
}
```

最终构造出的 su 参数：

```java
private static String[] buildSuArguments(@NonNull String shellSnippet) {
    return new String[]{"-Z", MAGISK_SU_CONTEXT, "-c", shellSnippet};
}
```

后端脚本执行片段：

```java
public static String buildShellCommand(@NonNull String scriptPath, String argument) {
    StringBuilder builder = new StringBuilder();
    builder.append("PATH=").append(ANDROID_SYSTEM_PATH).append(" ").append(SYSTEM_SH_PATH).append(" ").append(scriptPath);
    if (argument != null && !argument.isEmpty())
        builder.append(" ").append(argument);
    return builder.toString();
}
```

预检查项包括：

- 是否能运行 `su`。
- 是否存在 `/data/local/bin/ubuntu22`。
- 是否存在 `/data/local/ubuntu-22.04`。
- 是否存在 `/data/adb/magisk/busybox`。
- 是否能启动 Ubuntu session。

预检查也使用和真实 session 一致的 `su -Z u:r:su:s0` 路径，避免出现“检查失败但实际能启动”或“检查成功但实际启动失败”的不一致。

### 5.2 TermuxService 默认 session 改为 Ubuntu

文件：

```text
app/src/main/java/com/termux/app/TermuxService.java
```

修改点在 `createTermuxSession(...)`。

当不是 failsafe session 且没有显式传入 executable 时，默认不再选择 Termux shell，而是选择 Ubuntu：

```java
if (!isFailSafe && executablePath == null) {
    executablePath = UbuntuRootUtils.SYSTEM_SU_PATH;
    arguments = UbuntuRootUtils.getDefaultUbuntuSessionArguments();
    workingDirectory = UbuntuRootUtils.getUbuntuWorkingDirectory();
    if (sessionName == null || sessionName.isEmpty())
        sessionName = UbuntuRootUtils.getDefaultUbuntuSessionName();
}
```

效果：

```text
普通新 session -> Ubuntu chroot
failsafe session -> 保留 Termux 原逻辑
```

### 5.3 TermuxActivity 跳过 bootstrap 安装

文件：

```text
app/src/main/java/com/termux/app/TermuxActivity.java
```

原 Termux 首次启动会调用：

```java
TermuxInstaller.setupBootstrapIfNeeded(...)
```

本项目已经不再需要 Termux bootstrap，因此改成：

- 先做 Ubuntu 后端预检查。
- 如果预检查失败，只记录 log warning。
- 继续创建 session。

这样不会再因为 Termux bootstrap 不存在而阻塞启动。

需要注意：中间版本曾经弹出过 `Ubuntu backend unavailable` 对话框。最终版本没有把它作为阻塞弹窗，只记录日志。这样用户不会被错误预检查卡住，但开发时仍能从 logcat 看到后端缺失原因。

### 5.4 TermuxTerminalSessionActivityClient 工作目录修正

文件：

```text
app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java
```

普通 session 的初始工作目录改为：

```java
workingDirectory = isFailSafe ? mActivity.getProperties().getDefaultWorkingDirectory() : UbuntuRootUtils.getUbuntuWorkingDirectory();
```

也就是：

```text
普通 Ubuntu session -> /
failsafe session -> Termux 默认工作目录
```

### 5.5 应用身份改造

涉及文件：

```text
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
termux-shared/src/main/res/values/strings.xml
```

主要改动：

- `applicationId` 改为 `com.ubuntu2204`。
- App 名称改为 `ubuntu2204`。
- manifest placeholders 从 `Termux` 改为 `ubuntu2204`。
- `TermuxConstants.TERMUX_PACKAGE_NAME` 改为 `com.ubuntu2204`。
- `TermuxConstants.TERMUX_APP_NAME` 改为 `ubuntu2204`。
- manifest 内组件类名使用完整类名，例如 `com.termux.app.TermuxActivity`。

完整类名很重要，因为 Java 源码包名没有重命名。如果继续使用 `.app.TermuxActivity` 这种相对写法，Android 会按 manifest package/applicationId 去拼接，容易找不到类。

### 5.6 保持 Java 源码包名不变

本次没有把所有 `com.termux.*` 改成 `com.ubuntu2204.*`。

原因：

- Termux 源码内部引用很多。
- 全量重命名风险大。
- 和上游同步更困难。
- 当前需求只需要改变安装包名和默认 shell，不需要改变源码包名。

因此 `TermuxConstants` 里一部分“应用包名”改成 `com.ubuntu2204`，但一部分“类名常量”要固定为 `com.termux...`，例如：

```java
public static final String TERMUX_ACTIVITY_NAME = "com.termux.app.TermuxActivity";
public static final String TERMUX_SERVICE_NAME = "com.termux.app.TermuxService";
```

### 5.7 移除 Termux bootstrap 打包逻辑

文件：

```text
app/build.gradle
```

原始 Termux App 会下载并打包 bootstrap zip，生成 `libtermux-bootstrap.so`，首次启动时解压 `$PREFIX`。

当前方案不需要这个逻辑，因此移除了：

- bootstrap zip 下载任务。
- app 模块里的 externalNativeBuild bootstrap 打包逻辑。
- `libtermux-bootstrap.so` 的 APK 入口。

当前 APK 只保留 Termux UI 和 native PTY 所需库，例如：

```text
lib/arm64-v8a/libtermux.so
lib/arm64-v8a/liblocal-socket.so
```

### 5.8 ABI 定向构建

涉及文件：

```text
app/build.gradle
terminal-emulator/build.gradle
termux-shared/build.gradle
```

新增环境变量：

```text
TERMUX_TARGET_ABI=arm64-v8a
```

这样可以只构建目标设备需要的 ABI，减少 APK 体积和构建噪声。

构建输出文件名改为：

```text
ubuntu2204_apt-android-7-debug_arm64-v8a.apk
```

## 6. 构建和部署

### 6.1 推荐构建命令

在 Windows PowerShell 中执行：

```powershell
cd E:\study\termux_app
$env:TERMUX_TARGET_ABI='arm64-v8a'
.\gradlew.bat :app:assembleDebug
```

输出 APK：

```text
E:\study\termux_app\app\build\outputs\apk\debug\ubuntu2204_apt-android-7-debug_arm64-v8a.apk
```

### 6.2 可复用构建脚本

当前保留了脚本：

```text
scripts\build-termux-arm64.ps1
```

脚本设置了：

```powershell
$env:ANDROID_HOME = 'E:\study\android-sdk-termux'
$env:ANDROID_SDK_ROOT = 'E:\study\android-sdk-termux'
$env:JAVA_HOME = 'e:\dev\jdk-17.0.18+8'
$env:TERMUX_PACKAGE_VARIANT = 'apt-android-7'
$env:TERMUX_TARGET_ABI = 'arm64-v8a'
$env:TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS = '1'
```

然后执行：

```powershell
& "$repoRoot\gradlew.bat" clean app:assembleDebug
```

### 6.3 推荐安装流程

为了避免设备上残留旧 APK 状态，推荐流程是：

```powershell
E:\study\platform-tools\adb.exe shell am force-stop com.ubuntu2204
E:\study\platform-tools\adb.exe install -r -d E:\study\termux_app\app\build\outputs\apk\debug\ubuntu2204_apt-android-7-debug_arm64-v8a.apk
```

如果需要完全干净安装，可以先卸载：

```powershell
E:\study\platform-tools\adb.exe uninstall com.ubuntu2204
E:\study\platform-tools\adb.exe install E:\study\termux_app\app\build\outputs\apk\debug\ubuntu2204_apt-android-7-debug_arm64-v8a.apk
```

注意：实际测试中 `adb uninstall com.ubuntu2204` 有时返回 `DELETE_FAILED_INTERNAL_ERROR`，但 `install -r -d` 可以正常覆盖安装。

### 6.4 校验设备安装的 APK 是否是最新版本

本地 APK SHA256：

```powershell
Get-FileHash -Algorithm SHA256 app\build\outputs\apk\debug\ubuntu2204_apt-android-7-debug_arm64-v8a.apk
```

设备 APK SHA256：

```powershell
E:\study\platform-tools\adb.exe shell 'pm path com.ubuntu2204 | cut -d: -f2 | xargs sha256sum'
```

最终验证过的 APK hash：

```text
e9afdca1d376cfd147cf5aec4ba5b2dc27eced37c9f934d6e57cfbdd3ba9bb4b
```

## 7. 测试验证方法

### 7.1 启动应用

```powershell
E:\study\platform-tools\adb.exe shell monkey -p com.ubuntu2204 1
```

### 7.2 查看进程链

```powershell
E:\study\platform-tools\adb.exe shell ps -A | Select-String -Pattern 'com\.ubuntu2204|\bsu\b|\bbash\b'
```

预期看到类似：

```text
u0_a268      21517  1258    ... S com.ubuntu2204
u0_a268      21603 21517    ... S su
root         21613 21609    ... S bash
```

关键是：

```text
com.ubuntu2204 -> su -> root bash
```

这说明 APK 的 Termux session 已经通过 su 进入了 Ubuntu bash。

### 7.3 验证 bash 是否真的在 Ubuntu rootfs 中

找到 root bash PID 后执行：

```powershell
E:\study\platform-tools\adb.exe shell 'su -c "readlink /proc/<BASH_PID>/root"'
```

预期返回：

```text
/data/local/ubuntu-22.04
```

这比只看终端提示符更可靠。

### 7.4 验证日志没有关键错误

```powershell
E:\study\platform-tools\adb.exe logcat -d -v time | Select-String -Pattern 'avc: denied.*ubuntu22|inaccessible or not found|chroot: Needs|FATAL EXCEPTION|TermuxSession exited|Ubuntu backend preflight warning'
```

最终版本预期不出现以下错误：

```text
avc: denied ... ubuntu22
inaccessible or not found
chroot: Needs
FATAL EXCEPTION
Ubuntu backend preflight warning
```

### 7.5 验证 Android binder 状态没有被污染

```powershell
E:\study\platform-tools\adb.exe shell 'cmd package path com.ubuntu2204 >/dev/null && echo binder-ok; ls -l /dev/binder /dev/hwbinder /dev/vndbinder'
```

预期输出包含：

```text
binder-ok
/dev/binder -> /dev/binderfs/binder
/dev/hwbinder -> /dev/binderfs/hwbinder
/dev/vndbinder -> /dev/binderfs/vndbinder
```

如果出现 binder driver 打不开，优先怀疑曾经使用了 `su -mm` 或全局 namespace 被污染。

### 7.6 用户可见终端验证

在 APK 终端里执行：

```sh
id
pwd
head -n 3 /etc/os-release
```

预期：

- `id` 显示 root。
- `pwd` 在 Ubuntu 环境中。
- `/etc/os-release` 显示 Ubuntu 22.04。

## 8. 问题和踩坑复盘

### 8.1 旧的 backend unavailable 弹窗

中间阶段用户看到过：

```text
Ubuntu backend unavailable
missing su binary: /system/bin/su
```

这个问题一度很迷惑，因为源码已经改成只记录 warning，不再弹 dialog，但设备上仍然显示过旧弹窗。

排查结论：

- 有一段时间设备可能运行的是旧 APK 或旧进程状态。
- 后续通过 SHA256 比对确认设备 APK 与本地最新 APK 一致。
- 最终版本不再阻塞弹窗，只在 logcat 记录预检查 warning。

经验：

```text
测试 APK 时必须校验设备端 base.apk hash，不能只相信 adb install 输出。
```

### 8.2 SELinux 拒绝执行 ubuntu22

现象：

```text
avc: denied { execute } for name="ubuntu22" ... scontext=u:r:untrusted_app_27 ...
```

原因：

- Android app 默认运行在 `untrusted_app` SELinux 域。
- 从 app session 里拉起 `/data/local/bin/ubuntu22` 时，SELinux 可能仍然按 app 域拦截。
- root UID 不等于拥有所有 SELinux 权限。

修复：

```sh
su -Z u:r:su:s0 -c 'PATH=... /system/bin/sh /data/local/bin/ubuntu22'
```

结论：

```text
Root 解决 UID/GID，SELinux context 解决 MAC 权限边界。
```

### 8.3 `su -mm` 污染 binderfs

现象：

```text
Binder driver '2 (No such file or directory) Opening '/dev/binder' failed
```

影响：

- `cmd package` 失败。
- `dumpsys activity top` 失败。
- `uiautomator dump` 失败。
- 系统级 binder 状态异常。

原因：

- `su -mm` 让命令运行在全局 mount namespace。
- Ubuntu 脚本会 bind mount `/dev` 到 rootfs。
- 后端 stop 或 umount 操作可能影响全局 `/dev/binderfs`。

修复：

- APK 默认启动绝对不能使用 `-mm`。
- 如果已经污染，最稳妥恢复方式是硬重启或 `adb reboot`。

最终策略：

```text
使用 su -Z，不使用 su -mm。
```

### 8.4 `mkdir/grep/chmod/getprop: inaccessible or not found`

现象：

终端里显示：

```text
/data/local/bin/ubuntu22[3]: mkdir: inaccessible or not found
/data/local/bin/ubuntu22[3]: grep: inaccessible or not found
/data/local/bin/ubuntu22[3]: getprop: inaccessible or not found
```

原因：

- `su -Z` 后环境变量变化。
- Android 系统命令不在 PATH 中。
- 后端脚本在 chroot 前需要 Android 侧命令。

修复：

```text
PATH=/system/bin:/system/xbin:/product/bin:/system_ext/bin:/vendor/bin:/vendor/xbin:/odm/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin /system/bin/sh /data/local/bin/ubuntu22
```

结论：

```text
chroot 前需要 Android PATH，chroot 后需要 Ubuntu PATH。
两者不能混淆。
```

### 8.5 PowerShell 和 adb shell 引号问题

Windows PowerShell、adb shell、Android sh 三层引号混用时，很容易出现命令被错误拆分。

实际出现过：

```text
/system/bin/sh: ubuntu22: inaccessible or not found
/system/bin/sh: bash: inaccessible or not found
chroot: Needs 1 argument
```

但这些并非 APK 真实启动错误，而是测试命令：

```powershell
adb shell 'ps -A | grep -E "ubuntu2204|ubuntu22|chroot|bash|su"'
```

在某些情况下被外层 shell 错误解析，导致 `|bash|su` 被当成真正管道执行。

更稳妥的方法是在 PC 侧过滤：

```powershell
E:\study\platform-tools\adb.exe shell ps -A | Select-String -Pattern 'com\.ubuntu2204|\bsu\b|\bbash\b'
```

### 8.6 截图和 UI dump 不是终端内容的可靠来源

`uiautomator dump` 可以看到 Android View 结构，但 Termux 终端文本是自绘 View，不一定能直接从 XML 中读到终端内容。

因此验证 Ubuntu 是否启动不能只靠 `uiautomator`。更可靠的方法是：

- 看进程链。
- 看 `/proc/<bash_pid>/root`。
- 看 logcat 错误过滤。
- 在终端内手动执行 `id`、`pwd`、`cat /etc/os-release`。

### 8.7 APK 状态和旧进程残留

Android 上覆盖安装后，旧进程、旧 task、旧 session 有可能还在。

推荐测试前执行：

```powershell
adb shell am force-stop com.ubuntu2204
adb install -r -d path\to\apk
adb shell monkey -p com.ubuntu2204 1
```

必要时清理 Ubuntu 挂载：

```powershell
adb shell 'su -c "/data/local/bin/ubuntu22-stop >/dev/null 2>&1 || true"'
```

## 9. 最终验证结果

最终 APK：

```text
E:\study\termux_app\app\build\outputs\apk\debug\ubuntu2204_apt-android-7-debug_arm64-v8a.apk
```

最终 SHA256：

```text
e9afdca1d376cfd147cf5aec4ba5b2dc27eced37c9f934d6e57cfbdd3ba9bb4b
```

最终干净启动后看到进程链：

```text
com.ubuntu2204 -> su -> root bash
```

验证 bash root：

```text
/proc/<bash_pid>/root -> /data/local/ubuntu-22.04
```

最终 logcat 没有再出现：

```text
avc: denied ... ubuntu22
inaccessible or not found
chroot: Needs
FATAL EXCEPTION
Ubuntu backend preflight warning
```

Android binder 验证正常：

```text
binder-ok
```

## 10. 当前项目文件状态说明

### 10.1 关键新增文件

```text
app/src/main/java/com/termux/app/UbuntuRootUtils.java
```

这是 Ubuntu 后端集成的核心文件。

### 10.2 关键修改文件

```text
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/java/com/termux/app/TermuxActivity.java
app/src/main/java/com/termux/app/TermuxApplication.java
app/src/main/java/com/termux/app/TermuxService.java
app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java
app/src/main/res/values/strings.xml
terminal-emulator/build.gradle
termux-shared/build.gradle
termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
termux-shared/src/main/res/values/strings.xml
```

### 10.3 保留的用户文档

```text
doc_zy/安卓平板安装与接入Ubuntu22.04全过程.md
```

这份文档是设备后端安装依据，不能删除。

### 10.4 已清理的临时文件

已经清理过测试过程中的临时文件，例如：

- 截图 PNG。
- UI dump XML。
- APK 解包目录。
- APK 检查 zip。
- 临时 base.apk。

保留了：

```text
scripts/build-termux-arm64.ps1
```

因为这是可复用构建脚本，不是临时截图或检查产物。

## 11. 从零复现当前方案的操作流程

### 11.1 设备端准备

确认 root 可用：

```powershell
E:\study\platform-tools\adb.exe shell su -c id
```

确认后端路径存在：

```powershell
E:\study\platform-tools\adb.exe shell 'su -c "ls -ld /data/local/ubuntu-22.04 /data/local/bin/ubuntu22 /data/adb/magisk/busybox"'
```

确认后端可手动进入：

```powershell
E:\study\platform-tools\adb.exe shell 'su -c "/data/local/bin/ubuntu22 id"'
```

### 11.2 PC 侧构建

```powershell
cd E:\study\termux_app
$env:TERMUX_TARGET_ABI='arm64-v8a'
.\gradlew.bat :app:assembleDebug
```

### 11.3 安装 APK

```powershell
E:\study\platform-tools\adb.exe shell am force-stop com.ubuntu2204
E:\study\platform-tools\adb.exe install -r -d E:\study\termux_app\app\build\outputs\apk\debug\ubuntu2204_apt-android-7-debug_arm64-v8a.apk
```

### 11.4 启动验证

```powershell
E:\study\platform-tools\adb.exe logcat -c
E:\study\platform-tools\adb.exe shell monkey -p com.ubuntu2204 1
Start-Sleep -Seconds 8
E:\study\platform-tools\adb.exe shell ps -A | Select-String -Pattern 'com\.ubuntu2204|\bsu\b|\bbash\b'
```

### 11.5 错误过滤

```powershell
E:\study\platform-tools\adb.exe logcat -d -v time | Select-String -Pattern 'avc: denied.*ubuntu22|inaccessible or not found|chroot: Needs|FATAL EXCEPTION|TermuxSession exited|Ubuntu backend preflight warning'
```

没有输出或没有关键错误即为通过。

## 12. 当前方案的限制

### 12.1 强依赖固定后端路径

当前 APK 写死了：

```text
/data/local/bin/ubuntu22
/data/local/ubuntu-22.04
/data/adb/magisk/busybox
```

换设备或换 rootfs 路径后，需要改代码或增加配置项。

### 12.2 强依赖 Magisk su 的 `-Z` 参数

当前方案默认 `su` 支持：

```text
-Z, --context CONTEXT
```

如果换成其他 root 管理器，可能不支持 `-Z`，需要重新设计启动方式。

### 12.3 仍然是 root-only 方案

没有 root 的设备不能使用当前 chroot 方案。no-root 版本需要另外做 proot 后端。

### 12.4 Termux 插件生态不一定兼容

虽然保留了很多 Termux 结构，但 package name 已经变成 `com.ubuntu2204`，并且没有 Termux bootstrap，因此 Termux:API、Termux:Widget、Termux:Boot 等插件不能假设无缝可用。

### 12.5 还没有产品化后端管理

当前只是默认进入 Ubuntu。后续可以增加：

- 启动 SSH。
- 停止 SSH。
- 停止 Ubuntu 挂载。
- 修改 root 密码。
- 后端状态检查页面。
- rootfs 一键安装或修复页面。

## 13. 后续开发建议

### 13.1 优先做后端状态页

建议新增一个简单状态页或菜单项，显示：

- root 是否可用。
- su 是否支持 `-Z`。
- Ubuntu rootfs 是否存在。
- busybox 是否存在。
- 当前是否已有 Ubuntu bash 进程。
- 当前 mount 是否存在。

### 13.2 增加后端管理命令

可以把已有脚本接入 UI：

```text
/data/local/bin/ubuntu22-stop
/data/local/bin/ubuntu22-sshd-start
/data/local/bin/ubuntu22-sshd-stop
/data/local/bin/ubuntu22-passwd
```

这些适合放到 overflow menu 或 drawer 里。

### 13.3 做 rootfs 安装器时的建议

如果后续要把 rootfs 集成进 APK，建议流程是：

1. APK 内置压缩 rootfs 或下载 rootfs。
2. 首次启动检查 `/data/local/ubuntu-22.04`。
3. 如果不存在，用 root 解压到临时目录。
4. 校验 rootfs 完整性。
5. 原子移动到最终路径。
6. 写入 `/data/local/bin/ubuntu22` 等入口脚本。
7. 再启动 Ubuntu session。

不要直接在 APK assets 中 chroot，也不要把 rootfs 放在只读 APK 内使用。

### 13.4 做 no-root 版本时的建议

no-root 版本应单独分支或单独产品化，不要和 root chroot 逻辑混在同一个启动路径里。

no-root 版本大致需要：

- proot 二进制。
- app 私有目录里的 rootfs。
- 用户态 mount/path 映射。
- 更弱的系统服务支持。
- 更低的性能预期。

### 13.5 发布版注意事项

发布版还需要处理：

- release signing。
- app 图标和名称彻底品牌化。
- versionCode/versionName 管理。
- minSdk/targetSdk 策略。
- root 授权失败提示。
- 非 Magisk 环境兼容提示。
- 隐私和安全说明。

## 14. 关键结论

1. 这个改造方案可行。
2. 当前最稳妥路径是 root + chroot，而不是一开始就把 rootfs 塞进 APK。
3. Termux UI 和 session 逻辑可以复用，默认 shell 可以替换为 Ubuntu 后端。
4. SELinux 不能忽略，root UID 不等于拥有正确 SELinux context。
5. `su -Z u:r:su:s0` 是当前设备上解决 app 域执行限制的关键。
6. 不要使用 `su -mm` 启动 Ubuntu 后端，它会污染全局 mount namespace。
7. `su -Z` 后必须显式提供 Android 系统 `PATH`，否则后端脚本找不到 `mkdir/grep/chmod/getprop`。
8. 测试 APK 必须校验设备端 base.apk hash，避免旧包或旧进程误导判断。
9. 验证是否进入 Ubuntu，最可靠的是看 `/proc/<bash_pid>/root` 是否指向 `/data/local/ubuntu-22.04`。
10. 当前最终 APK 已验证可以干净启动，并进入 Ubuntu 22.04 chroot shell。

