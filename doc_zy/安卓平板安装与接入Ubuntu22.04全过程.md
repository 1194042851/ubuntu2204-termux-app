# 安卓平板安装与接入 Ubuntu 22.04 全过程

## 1. 文档目标

这份文档记录了本机环境中，如何在一台已经 `root` 且已经解锁 `BL` 的安卓平板上：

1. 安装一个可运行的 `Ubuntu 22.04.5 LTS` 用户空间。
2. 把它做成不依赖 Termux 前台界面的系统级入口。
3. 再开发一个本地安卓 APK，给这个 Ubuntu 环境提供可点击的入口和控制台。

这份文档不是泛泛而谈，而是基于这次实际落地过程整理，包含：

- 真实环境信息
- 实际用到的路径和脚本
- 下载源和依赖
- 踩过的坑
- 每个坑最后是怎么绕过去的
- 当前结果和剩余限制

---

## 2. 最终结论

### 2.1 Ubuntu 形态

这次落地的不是“刷机替换安卓”的 Ubuntu，而是：

- 安卓内核不变
- Ubuntu 作为 `chroot` 用户空间运行
- rootfs 位于 `/data/local/ubuntu-22.04`
- 通过 Magisk 的 `busybox chroot` 启动

也就是说，这是一套：

- 不是 `proot`
- 不是 Docker
- 不是虚拟机
- 也不是双系统刷机

但它已经是较接近原生 Linux 用户空间的方案。

### 2.2 当前已经实现的结果

已经完成：

- Ubuntu 22.04.5 LTS `arm64` rootfs 安装
- 系统级启动脚本
- 开机自动拉起 Ubuntu 内部 `sshd`
- 本地 SSH 入口 `127.0.0.1:2222`
- 安卓 APK 前端入口

当前可直接使用的入口：

- 进入 Ubuntu：
  `su -c /data/local/bin/ubuntu22`
- 停止挂载：
  `su -c /data/local/bin/ubuntu22-stop`
- 启动 Ubuntu SSH：
  `su -c /data/local/bin/ubuntu22-sshd-start`
- 停止 Ubuntu SSH：
  `su -c /data/local/bin/ubuntu22-sshd-stop`
- 修改 Ubuntu root 密码：
  `su -c /data/local/bin/ubuntu22-passwd`

开机自启动脚本：

- `/data/adb/service.d/ubuntu22-boot.sh`

Ubuntu rootfs 路径：

- `/data/local/ubuntu-22.04`

安卓 APK 工程路径：

- `/data/data/com.termux/files/home/work/ubuntu2204`

生成的 APK：

- `/data/data/com.termux/files/home/work/ubuntu2204/app/build/outputs/apk/debug/app-debug.apk`

---

## 3. 本机环境

### 3.1 设备与系统

实测环境：

- 品牌：`OnePlus`
- 型号：`OPD2404`
- SoC：`SM8475`
- ABI：`arm64-v8a`
- SELinux：`Enforcing`
- 内核：`Linux 5.10.168-android12-9-00313-gccf05ebc66df`

这个 SoC 对应高通 `Snapdragon 8+ Gen 1` 级别平台，性能足够支撑 Ubuntu 22.04 chroot。

### 3.2 Root 状态

前提条件已经具备：

- Bootloader 已解锁
- Magisk root 可用
- `su -c id` 能返回 `uid=0(root)`

这是整个方案成立的前提。

### 3.3 Termux / Java / Android 打包工具

本次实际检测到的关键组件：

- `aapt2 13.0.0.6-23`
- `aapt 13.0.0.6-23`
- `apksigner 33.0.1-1`
- `d8 33.0.1-1`
- `android-tools 35.0.2-7`
- `openjdk-17 17.0.19`
- `openjdk-21 21.0.11`

当前默认 `java -version` 输出：

- `OpenJDK 21.0.11`

### 3.4 Ubuntu 版本

当前已安装的 Ubuntu：

- `Ubuntu 22.04.5 LTS`
- `Jammy Jellyfish`
- `arm64`

---

## 4. 依赖与前提

### 4.1 必须条件

这套方案要求至少满足以下条件：

- 安卓设备已经 root
- 已解锁 BL
- 有可写的 `/data`
- 有足够空间放 rootfs
- 能通过 `su` 获取 root

### 4.2 本次实际依赖

安卓侧依赖：

- Magisk
- `/data/adb/magisk/busybox`
- `/system/bin/mount`
- `/system/bin/sh`

Termux 侧依赖：

- `curl` 或 `wget`
- `tar`
- `sha256sum`
- `javac`
- `zip`
- `zipalign`
- `apksigner`
- `d8`
- `aapt` / `aapt2`

Ubuntu 内部后来安装的基础包：

- `ca-certificates`
- `curl`
- `git`
- `wget`
- `nano`
- `vim-tiny`
- `sudo`
- `procps`
- `net-tools`
- `iproute2`
- `iputils-ping`
- `openssh-client`
- `openssh-server`
- `tzdata`
- `locales`

---

## 5. Ubuntu 安装过程

### 5.1 方案选择

一开始就明确放弃了几条路线：

- 不用 `proot`
- 不用 Docker
- 不刷机替换安卓
- 不做完整虚拟机

最终选的是：

- `root + chroot + Ubuntu rootfs`

原因：

- 性能比 `proot` 更好
- 与真实 Linux 用户空间更接近
- 风险远低于刷机
- 随时可删，可回退

### 5.2 rootfs 下载

最初计划从 Ubuntu 官方源下载：

- `cdimage.ubuntu.com`
- `cloud-images.ubuntu.com`

但是遇到的实际问题是：

- `curl`
- `wget`
- Python `urllib`

都出现了 TLS EOF 或连接被远端关闭的问题。

典型报错：

- `unexpected eof while reading`
- `Unable to establish SSL connection`
- `Remote end closed connection without response`

### 5.3 下载源切换

解决方法是切换到公开镜像：

- `https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/jammy/release/ubuntu-base-22.04.5-base-arm64.tar.gz`

最终下载的 rootfs 文件：

- `ubuntu-base-22.04.5-base-arm64.tar.gz`

校验结果：

- `075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f`

与镜像站提供的 `SHA256SUMS` 一致。

### 5.4 rootfs 解压位置

实际解压到：

- `/data/local/ubuntu-22.04`

没有放到 `/sdcard` 或共享存储，原因是：

- 共享存储是 FUSE / emulated storage
- 权限语义和标准 Linux 不一致
- 会影响软链接、执行位、包管理和很多 Unix 程序

### 5.5 首次进入与初始化

安装后写入了基础脚本，并通过 `chroot` 首次进入，做了这些事：

- 更新 `apt` 源
- 升级 rootfs 基础包
- 设置时区 `Asia/Shanghai`
- 生成 `zh_CN.UTF-8`
- 设置 `LANG=zh_CN.UTF-8`
- 安装基础命令行工具
- 安装 `openssh-server`

Ubuntu 内软件源最终用了：

- `http://mirrors.huaweicloud.com/ubuntu-ports/`

原因同样是为了避开本机对官方 HTTPS 源的握手问题。

---

## 6. 系统级 Ubuntu 入口是怎么做的

### 6.1 为什么不能继续只依赖 Termux

最初的入口是 Termux 脚本，但用户明确要求：

- 不想依赖 Termux 前台界面

所以后面把真正的入口做成了系统级脚本。

### 6.2 核心脚本

核心逻辑在：

- `/data/local/ubuntu-22.04/.host/ubuntu-core.sh`

它主要做三件事：

1. 准备挂载点
2. 挂载 Linux 运行所需目录
3. 用 Magisk `busybox chroot` 进入 Ubuntu

### 6.3 实际挂载内容

脚本会挂载这些目录：

- `proc -> $ROOTFS/proc`
- `sysfs -> $ROOTFS/sys`
- `/dev -> $ROOTFS/dev`
- `devpts -> $ROOTFS/dev/pts`
- `/storage/emulated/0 -> $ROOTFS/mnt/shared`

并且会：

- 自动生成 `/etc/resolv.conf`
- 从 `getprop net.dns*` 取 DNS
- 如果系统 DNS 为空，则回退到 `1.1.1.1` 和 `8.8.8.8`

### 6.4 真实启动方式

系统级入口脚本：

- `/data/local/bin/ubuntu22`

内容很简单：

1. `source ubuntu-core.sh`
2. 执行 `ensure_mounts`
3. 调用 `run_in_ubuntu`

### 6.5 为什么要改成 Magisk busybox chroot

一开始的脚本仍然调用了 Termux 自带的 `chroot`，但后来为了彻底去掉对 Termux 前台环境的依赖，改成了：

- `/data/adb/magisk/busybox chroot`

这样有几个好处：

- 不需要先开 Termux
- 可从 root shell、ADB、服务脚本直接调用
- 更像系统级能力

### 6.6 开机自启动

为了让 Ubuntu 环境开机后就能被接入，加入了：

- `/data/adb/service.d/ubuntu22-boot.sh`

这个脚本会：

- 调用 `ensure_mounts`
- 确保 `/run/sshd` 存在
- 先 `pkill sshd`
- 再在 Ubuntu 内启动 `/usr/sbin/sshd`

---

## 7. SSH 接入方案

### 7.1 为什么要加 SSH

如果不想依赖 Termux 作为前台终端，那么最自然的方式就是：

- Ubuntu 在后台运行
- 安卓前台只负责连接

所以加入了 SSH。

### 7.2 安全策略

SSH 最终配置成：

- 监听 `127.0.0.1`
- 端口 `2222`

这样默认只允许本机 App 连接自己，不直接暴露到局域网。

### 7.3 对应入口

- 启动 SSH：
  `/data/local/bin/ubuntu22-sshd-start`
- 停止 SSH：
  `/data/local/bin/ubuntu22-sshd-stop`

### 7.4 密码设置

为方便首次使用，增加了：

- `/data/local/bin/ubuntu22-passwd`

用于给 Ubuntu 里的 `root` 设置密码。

---

## 8. 安卓 APK 开发过程

### 8.1 目标

目标不是做一个完整 Linux 桌面壳，而是做一个轻量、稳定、可本地安装的安卓前端：

- 可以直接从桌面打开
- 不需要先开 Termux
- 能提供 Ubuntu 入口
- 能执行常见控制动作
- 能显示命令输出

### 8.2 项目位置

安卓 APK 工程目录：

- `/data/data/com.termux/files/home/work/ubuntu2204`

教程参考目录：

- `/data/data/com.termux/files/home/work/Android_app`

### 8.3 最终实现方式

前端界面是一个原生 Android Java Activity，提供：

- 连接 Ubuntu 会话
- 断开会话
- 清空输出
- 查看环境状态
- 启动 SSH
- 停止 SSH
- 卸载挂载
- 常用命令提示
- 命令输入框
- 输出控制台

Activity 源码位置：

- `/data/data/com.termux/files/home/work/ubuntu2204/app/src/main/java/com/example/ubuntu2204launcher/MainActivity.java`

### 8.4 APK 与后端的关系

APK 不直接内嵌 Ubuntu。

它的作用是：

- 通过 `su`
- 调用 `/data/local/bin/ubuntu22*`
- 把命令输出拉回安卓界面显示

所以架构上是：

- Ubuntu rootfs 是后端
- root 脚本是系统入口层
- APK 是图形前端

---

## 9. 安卓 APK 开发阶段踩过的坑

### 9.1 `ubuntu2204` 目录最初是空的

一开始目标工程目录为空，需要从 `Android_app` 目录抽取思路，但不能直接照抄成品。

处理方式：

- 复制 Gradle wrapper、基础配置
- 新建独立工程目录结构
- 自己实现 `MainActivity`

### 9.2 原教程里的 SDK 路径失效

最初 `local.properties` 里写的是：

- `/data/data/com.termux/files/home/Android/Sdk`

但当前环境里这个路径并不存在。

后来在共享存储里找到了本地 SDK：

- `/storage/emulated/0/work/PhoneAiAssistant/local-sdk`

### 9.3 共享存储里的 build-tools 不能执行

虽然找到了 SDK，但它位于共享存储，直接执行时报：

- `Permission denied`

根因：

- 共享存储挂载通常带 `noexec`

处理方式：

- 把工具复制到 Termux 私有路径
- 后面发现仍然不够，因为复制出来的 `aapt2` / `zipalign` 是 `x86_64`

### 9.4 共享存储里那套 SDK 是 PC 架构，不是安卓 ARM64

检查 `file` 后发现：

- `aapt2` 是 `x86-64`
- `zipalign` 是 `x86-64`

这在本机 ARM64 安卓上不能执行。

处理方式：

- 改用 Termux 已安装的 ARM64 版工具：
  - `aapt`
  - `aapt2`
  - `zipalign`
  - `apksigner`
  - `d8`

### 9.5 `aapt2 link` 无法加载 include path

无论传：

- SDK `android.jar`
- `/system/framework/framework-res.apk`

`aapt2 link` 都报：

- `failed to load include path`

这说明当前这台设备上的 Termux `aapt2` 路径兼容性并不理想。

处理方式：

- 尝试退回老 `aapt`

### 9.6 `aapt` 对 Manifest 属性兼容性很差

一开始 Manifest 包含了这些较新的常见属性：

- `allowBackup`
- `supportsRtl`
- `exported`
- `windowSoftInputMode`

`aapt` 报：

- `No resource identifier found for attribute ...`

处理方式：

- 把 Manifest 简化到极老、极朴素的写法

后续又发现：

- `versionCode`
- `versionName`
- `minSdkVersion`
- `targetSdkVersion`

这些也会被 `aapt` 报类似错误。

这说明在当前环境里，自己手工重新打一个完整、现代 Manifest 的路线非常不稳。

### 9.7 `d8` 在 class 转 dex 时崩溃

这是 APK 这块最难缠的坑。

报错表现：

- `NullPointerException`
- 崩在 `MainActivity$1.class`
- 后来又崩在 `MainActivity$AppendTask.class`

根因不是 Java 代码语义错误，而是：

- 当前 `d8` / `r8` 在处理某些内部类元数据时崩溃

处理过程：

1. 去掉匿名内部类
2. 改成具名内部类
3. 再把内部任务类改成 `static` 嵌套类
4. 用显式 `MainActivity activity` 引用代替合成外部类引用

最终这一步解决了 `d8` 崩溃问题。

### 9.8 `pm install` 无法直接读取私有目录下的 APK

第一次安装时，包管理器报：

- `Unable to open file`
- `Consider using a file under /data/local/tmp/`

根因：

- `pm install` 由系统服务读取 APK
- 它没有权限直接访问当前这个 FUSE / app-private 路径

处理方式：

- 先把 APK 复制到 `/data/local/tmp/ubuntu2204-launcher.apk`
- 再执行 `pm install -r`

### 9.9 最终绕过 Manifest 重打包问题的方法

由于自己手工重打包完整 APK 的资源和 Manifest 阶段一直不稳定，最终采用了一个更稳的方案：

- 复用已经能正常安装的 `Android_app` 基础 APK 作为外壳
- 只替换其中的 `classes.dex`

也就是说，最终的 APK 流程不是：

- 从零完整打一个 APK

而是：

1. 取现成可安装壳包
2. 编译新的 `MainActivity.java`
3. 用 `d8` 生成新的 `classes.dex`
4. 替换壳包中的 `classes.dex`
5. `zipalign`
6. `apksigner`
7. `pm install`

这是这台设备上最稳、最现实的办法。

---

## 10. 当前 APK 的实际状态

### 10.1 已成功实现

当前 APK 已经：

- 编译成功
- 签名成功
- 安装成功
- 启动成功
- 进程已运行
- 前台 Activity 已切到新的 `MainActivity`

### 10.2 当前包名与图标标签

由于最后走的是“替换现成壳包 dex”的路线，当前安装包仍然沿用了旧壳的身份：

- 包名：`com.example.termuxdemo`
- 启动 Activity：`.MainActivity`
- 桌面标签：`Termux Demo`

但是打开后，实际 UI 已经是新的：

- `Ubuntu 22.04 Launcher`

### 10.3 这意味着什么

这代表：

- 功能已经可用
- 前端入口已经独立于 Termux 前台界面
- 但包名、图标、应用名还没有完全品牌化

如果后续要继续完善，可以把：

- `applicationId`
- 图标
- 桌面标签
- Manifest

彻底做成独立应用。

---

## 11. 关键文件一览

### 11.1 Ubuntu 侧

- Ubuntu rootfs：
  `/data/local/ubuntu-22.04`
- 核心 chroot 逻辑：
  `/data/local/ubuntu-22.04/.host/ubuntu-core.sh`
- 系统级进入入口：
  `/data/local/bin/ubuntu22`
- 系统级停止入口：
  `/data/local/bin/ubuntu22-stop`
- SSH 启动：
  `/data/local/bin/ubuntu22-sshd-start`
- SSH 停止：
  `/data/local/bin/ubuntu22-sshd-stop`
- 修改 root 密码：
  `/data/local/bin/ubuntu22-passwd`
- 开机自启：
  `/data/adb/service.d/ubuntu22-boot.sh`

### 11.2 Android APK 工程

- 工程根目录：
  `/data/data/com.termux/files/home/work/ubuntu2204`
- Activity 源码：
  `/data/data/com.termux/files/home/work/ubuntu2204/app/src/main/java/com/example/ubuntu2204launcher/MainActivity.java`
- 构建脚本：
  `/data/data/com.termux/files/home/work/ubuntu2204/build_and_install.sh`
- 生成 APK：
  `/data/data/com.termux/files/home/work/ubuntu2204/app/build/outputs/apk/debug/app-debug.apk`

### 11.3 参考模板

- 参考 Android 项目：
  `/data/data/com.termux/files/home/work/Android_app`

---

## 12. 实际可复用的经验

### 12.1 对安卓设备上跑 Ubuntu 的经验

- 已 root 的情况下，`chroot` 明显比 `proot` 更适合长期使用。
- rootfs 一定要放 `/data`，不要放共享存储。
- `busybox chroot` 比继续绑死在 Termux 自带 `chroot` 更适合做系统级入口。
- 如果不想依赖 Termux 前台界面，最实用的是：
  - 系统级脚本
  - 本地 SSH
  - 或者自写 APK 前端

### 12.2 对安卓本机开发 APK 的经验

- 文档里的 SDK 路径不一定还存在，必须先实地查。
- 共享存储里的 SDK 很可能是 PC 架构工具，不要默认能在手机本机执行。
- `pm install` 最稳妥的安装源路径是 `/data/local/tmp`。
- 在本机 Termux 环境里，老 `aapt` / `aapt2` / `d8` 组合可能存在大量边缘兼容问题。
- 如果完整打包链路不稳，复用一个已可安装 APK 外壳，再替换 `classes.dex`，是一个非常实用的兜底方案。

### 12.3 工程策略经验

- 先追求“能跑起来”，再追求“包名/图标/品牌完整”。
- 在高度受限环境里，过度追求教科书式工程结构，反而容易卡死在工具链。
- 先把系统入口跑通，再做 UI 前端，是正确顺序。

---

## 13. 当前仍然存在的限制

这套方案已经可用，但还不是最终形态，当前仍有这些限制：

- Ubuntu 不是独立启动系统，而是 `chroot` 用户空间
- APK 仍复用了旧壳包名 `com.example.termuxdemo`
- 桌面标签当前仍是 `Termux Demo`
- 没有做完整图标、品牌和独立 Manifest 收口
- 没有做 VNC / 图形桌面集成
- 没有做独立 SSH 客户端内嵌，而是当前 APK 主要作为本地命令入口

---

## 14. 后续建议

如果继续往前做，建议按这个顺序：

1. 把 APK 的包名、图标、应用名彻底独立出来
2. 在 APK 内加入 root 授权检测和环境状态检测
3. 加入“设置 root 密码”“启动 SSH”“查看端口状态”的专门页面
4. 接入本地 SSH 客户端或 WebSocket 终端组件
5. 如果需要图形桌面，再装 `XFCE + TigerVNC`
6. 最后再考虑把前端做得更接近完整“Ubuntu 启动器”

---

## 15. 一句话总结

这次在安卓平板上落地 Ubuntu 22.04 的关键，不是“找到一个完美的一键工具”，而是：

- 用 `root + chroot` 把 Ubuntu 稳定放到 `/data`
- 用 Magisk `busybox` 做系统级入口
- 用本地 SSH 和自定义 APK 把入口从 Termux 前台界面中剥离出来
- 在 APK 工具链不稳定时，果断采用“复用壳包 + 替换 dex”的务实方案

最终结果是：Ubuntu 22.04 已经稳定跑在本机里，而且已经有了可直接点击的安卓前端入口。
