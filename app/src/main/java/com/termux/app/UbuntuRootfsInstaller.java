package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.StatFs;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class UbuntuRootfsInstaller {

    private static final String LOG_TAG = "UbuntuRootfsInstaller";

    private static final String PREFS_NAME = "ubuntu-rootfs-installer";
    private static final String PREF_INSTALL_READY = "install-ready";

    private static final String SUPPORTED_ABI = "arm64-v8a";
    private static final String ROOTFS_ASSET_PATH = "ubuntu/rootfs-arm64.tgz";
    private static final String ROOTFS_SHA256_ASSET_PATH = "ubuntu/rootfs-arm64.sha256";
    private static final String UBUNTU_CORE_ASSET_PATH = "ubuntu/host/ubuntu-core.sh";
    private static final String[] SCRIPT_ASSET_NAMES = {
        "ubuntu22",
        "ubuntu22-stop",
        "ubuntu22-sshd-start",
        "ubuntu22-sshd-stop",
        "ubuntu22-passwd"
    };

    private static final long ONE_MIB = 1024L * 1024L;
    private static final long ONE_GIB = 1024L * 1024L * 1024L;

    private UbuntuRootfsInstaller() {}

    public interface ProgressReporter {
        void update(@NonNull String status, int progress);
        void append(@NonNull String line);
    }

    public static boolean isInstalled(@NonNull Context context) {
        String command = "test -x " + UbuntuRootfsManager.shellQuote(UbuntuRootUtils.MAGISK_BUSYBOX_PATH)
            + " && test -d " + UbuntuRootfsManager.shellQuote(UbuntuRootUtils.UBUNTU_ROOTFS_PATH)
            + " && test -f " + UbuntuRootfsManager.shellQuote(UbuntuRootUtils.UBUNTU_ENTRYPOINT)
            + " && (test -f " + UbuntuRootfsManager.shellQuote(UbuntuRootUtils.UBUNTU_INSTALL_MARKER)
            + " || " + UbuntuRootUtils.buildShellCommand(UbuntuRootUtils.UBUNTU_ENTRYPOINT, "true") + ")";
        try {
            return UbuntuRootfsManager.runRootCommand(command).isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    public static void setupIfNeeded(@NonNull Activity activity, @NonNull Runnable whenDone, @Nullable Runnable whenFailed) {
        if (isInstalled(activity)) {
            markInstallReady(activity);
            whenDone.run();
            return;
        }

        InstallProgressUi progressUi = new InstallProgressUi(activity);
        progressUi.show();

        new Thread(() -> {
            try {
                progressUi.update("正在检查 Ubuntu 22.04 安装状态...", 2);
                progressUi.append("检查本机是否已经存在可启动的 Ubuntu rootfs。");
                if (isInstalled(activity)) {
                    markInstallReady(activity);
                    progressUi.update("检测到 Ubuntu 22.04 已安装，正在启动终端...", 100);
                    progressUi.append("已找到可启动的 Ubuntu 后端，跳过首次安装。");
                } else {
                    installBlocking(activity, progressUi);
                    progressUi.update("Ubuntu 22.04 安装完成，正在启动终端...", 100);
                    progressUi.append("安装完成，准备进入 Ubuntu。");
                }
                activity.runOnUiThread(() -> {
                    progressUi.dismiss();
                    whenDone.run();
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Ubuntu rootfs installation failed", e);
                activity.runOnUiThread(() -> showInstallErrorDialog(activity, progressUi, whenDone, whenFailed,
                    Logger.getStackTracesStringArray(e)[0]));
            }
        }, "UbuntuRootfsInstaller").start();
    }

    public static void installBlocking(@NonNull Context context, @NonNull ProgressReporter progressUi) throws Exception {
        install(context, progressUi);
        markInstallReady(context);
    }

    public static void repairScriptsBlocking(@NonNull Context context, @NonNull ProgressReporter progressUi) throws Exception {
        progressUi.update("正在检查 Ubuntu rootfs...", 10);
        ensureRootAndBusybox();

        CommandResult rootfsResult = runRootCommand("test -d " + UbuntuRootUtils.UBUNTU_ROOTFS_PATH);
        if (!rootfsResult.isSuccessful()) {
            throw new IOException("未找到 Ubuntu rootfs，请先执行重装 Ubuntu。");
        }

        progressUi.update("正在修复 Ubuntu 宿主启动脚本...", 35);
        installUbuntuCoreScript(context, UbuntuRootUtils.UBUNTU_ROOTFS_PATH);

        progressUi.update("正在修复 /data/local/bin 启动入口...", 70);
        installEntrypointScripts(context);

        progressUi.update("正在验证 Ubuntu 启动入口...", 90);
        if (!UbuntuRootUtils.canStartUbuntuSession(context)) {
            throw new IOException("启动脚本已写入，但 Ubuntu 启动验证失败。");
        }

        markInstallReady(context);
        progressUi.update("启动脚本修复完成。", 100);
        progressUi.append("Ubuntu 启动入口已恢复。");
    }

    private static boolean isInstallReadyCached(@NonNull Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_INSTALL_READY, false);
    }

    public static void markInstallReady(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_INSTALL_READY, true)
            .apply();
    }

    public static void clearInstallReady(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_INSTALL_READY)
            .apply();
    }

    private static void install(@NonNull Context context, @NonNull ProgressReporter progressUi) throws Exception {
        progressUi.update("正在检查设备环境...", 2);
        progressUi.append("检查 CPU ABI、root 权限和 Magisk busybox。");
        ensureSupportedAbi();
        ensureRootAndBusybox();

        long rootfsAssetSize = getAssetLength(context, ROOTFS_ASSET_PATH);
        ensureEnoughDataSpace(rootfsAssetSize);

        progressUi.update("正在校验内置 rootfs 包...", 8);
        String expectedSha256 = readExpectedSha256(context);
        String actualSha256 = calculateAssetSha256(context, ROOTFS_ASSET_PATH, rootfsAssetSize, progressUi);
        if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
            throw new IOException("内置 rootfs 校验失败。\nexpected=" + expectedSha256 + "\nactual=" + actualSha256);
        }
        progressUi.append("rootfs SHA-256 校验通过。");

        try {
            progressUi.update("正在准备安装目录...", 30);
            runRootCommand("rm -rf " + UbuntuRootUtils.UBUNTU_ROOTFS_TMP_PATH
                + " && mkdir -p " + UbuntuRootUtils.UBUNTU_ROOTFS_TMP_PATH);

            progressUi.append("正在解压 rootfs 到 " + UbuntuRootUtils.UBUNTU_ROOTFS_TMP_PATH + "。");
            extractRootfs(context, rootfsAssetSize, progressUi);

            progressUi.update("正在写入宿主启动逻辑...", 80);
            installUbuntuCoreScript(context, UbuntuRootUtils.UBUNTU_ROOTFS_TMP_PATH);

            progressUi.update("正在写入安装标记...", 82);
            writeMarker(context, expectedSha256);

            progressUi.update("正在切换到正式目录...", 86);
            finalizeRootfs();

            progressUi.update("正在写入启动脚本...", 90);
            installEntrypointScripts(context);

            progressUi.update("正在验证 Ubuntu 是否可以启动...", 96);
            if (!UbuntuRootUtils.canStartUbuntuSession(context)) {
                restoreBackupRootfs();
                throw new IOException("Ubuntu 安装完成后启动校验失败，已尝试恢复旧 rootfs。");
            }

            runRootCommand("rm -rf " + UbuntuRootUtils.UBUNTU_ROOTFS_BACKUP_PATH);
        } catch (Exception e) {
            runRootCommandIgnoringFailure("rm -rf " + UbuntuRootUtils.UBUNTU_ROOTFS_TMP_PATH);
            throw e;
        }
    }

    private static void ensureSupportedAbi() throws IOException {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (SUPPORTED_ABI.equals(abi)) return;
        }
        throw new IOException("当前设备 ABI 不支持。需要 " + SUPPORTED_ABI + "，当前为 "
            + join(Build.SUPPORTED_ABIS));
    }

    private static void ensureRootAndBusybox() throws IOException, InterruptedException {
        CommandResult rootResult = runRootCommand("id");
        if (!rootResult.isSuccessful()) {
            throw new IOException("root 权限检查失败。\n" + rootResult.output);
        }

        CommandResult busyboxResult = runRootCommand("test -x " + UbuntuRootUtils.MAGISK_BUSYBOX_PATH);
        if (!busyboxResult.isSuccessful()) {
            throw new IOException("未找到可执行的 Magisk busybox: " + UbuntuRootUtils.MAGISK_BUSYBOX_PATH);
        }
    }

    private static void ensureEnoughDataSpace(long rootfsAssetSize) throws IOException {
        StatFs statFs = new StatFs("/data");
        long availableBytes = statFs.getAvailableBytes();
        long requiredBytes = Math.max(6L * ONE_GIB, rootfsAssetSize * 3L + ONE_GIB);
        if (availableBytes < requiredBytes) {
            throw new IOException("设备 /data 空间不足。可用 " + formatBytes(availableBytes)
                + "，建议至少保留 " + formatBytes(requiredBytes) + "。");
        }
    }

    private static long getAssetLength(@NonNull Context context, @NonNull String assetPath) throws IOException {
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(assetPath)) {
            return descriptor.getLength();
        }
    }

    @NonNull
    private static String readExpectedSha256(@NonNull Context context) throws IOException {
        try (InputStream inputStream = context.getAssets().open(ROOTFS_SHA256_ASSET_PATH);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            String content = outputStream.toString(StandardCharsets.UTF_8.name()).trim();
            int spaceIndex = content.indexOf(' ');
            return (spaceIndex > 0 ? content.substring(0, spaceIndex) : content).trim();
        }
    }

    @NonNull
    private static String calculateAssetSha256(@NonNull Context context, @NonNull String assetPath, long totalBytes,
                                               @NonNull ProgressReporter progressUi) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[(int) ONE_MIB];
        long readBytes = 0;
        int lastProgress = -1;
        long startedAt = System.currentTimeMillis();

        try (InputStream inputStream = context.getAssets().open(assetPath, AssetManager.ACCESS_STREAMING)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                readBytes += read;

                int progress = 8 + getWeightedProgress(readBytes, totalBytes, 20);
                if (progress != lastProgress) {
                    progressUi.update("正在校验内置 rootfs 包... " + progress + "% " + getProgressHint(readBytes, totalBytes, startedAt), progress);
                    lastProgress = progress;
                }
            }
        }

        return toHex(digest.digest());
    }

    private static void extractRootfs(@NonNull Context context, long totalBytes,
                                      @NonNull ProgressReporter progressUi) throws Exception {
        String command = UbuntuRootUtils.MAGISK_BUSYBOX_PATH + " tar -xzf - -C "
            + UbuntuRootUtils.UBUNTU_ROOTFS_TMP_PATH;

        try (InputStream inputStream = context.getAssets().open(ROOTFS_ASSET_PATH, AssetManager.ACCESS_STREAMING)) {
            CommandResult result = runRootCommandWithInput(command, inputStream, totalBytes, progressUi, 32, 48,
                "正在释放 Ubuntu rootfs...");
            if (!result.isSuccessful()) {
                throw new IOException("rootfs 解压失败。\n" + result.output);
            }
        }
    }

    private static void writeMarker(@NonNull Context context, @NonNull String sha256) throws Exception {
        String marker = "version=22.04\n"
            + "abi=" + SUPPORTED_ABI + "\n"
            + "rootfs_sha256=" + sha256 + "\n"
            + "installed_at=" + System.currentTimeMillis() + "\n"
            + "installer_app=" + context.getPackageName() + "\n";

        try (InputStream inputStream = new java.io.ByteArrayInputStream(marker.getBytes(StandardCharsets.UTF_8))) {
            CommandResult result = runRootCommandWithInput("cat > "
                + UbuntuRootUtils.UBUNTU_ROOTFS_TMP_PATH + "/.ubuntu2204-installed",
                inputStream, marker.length(), null, 0, 0, null);
            if (!result.isSuccessful()) {
                throw new IOException("写入安装标记失败。\n" + result.output);
            }
        }
    }

    private static void finalizeRootfs() throws IOException, InterruptedException {
        String command = ""
            + "if [ -x " + UbuntuRootUtils.UBUNTU_STOP_ENTRYPOINT + " ]; then "
            + UbuntuRootUtils.UBUNTU_STOP_ENTRYPOINT + " || true; fi; "
            + "rm -rf " + UbuntuRootUtils.UBUNTU_ROOTFS_BACKUP_PATH + "; "
            + "if [ -e " + UbuntuRootUtils.UBUNTU_ROOTFS_PATH + " ]; then "
            + "mv " + UbuntuRootUtils.UBUNTU_ROOTFS_PATH + " " + UbuntuRootUtils.UBUNTU_ROOTFS_BACKUP_PATH + " || exit 10; "
            + "fi; "
            + "if mv " + UbuntuRootUtils.UBUNTU_ROOTFS_TMP_PATH + " " + UbuntuRootUtils.UBUNTU_ROOTFS_PATH + "; then "
            + "chmod 755 " + UbuntuRootUtils.UBUNTU_ROOTFS_PATH + "; "
            + "else "
            + "if [ -e " + UbuntuRootUtils.UBUNTU_ROOTFS_BACKUP_PATH + " ]; then "
            + "mv " + UbuntuRootUtils.UBUNTU_ROOTFS_BACKUP_PATH + " " + UbuntuRootUtils.UBUNTU_ROOTFS_PATH + "; "
            + "fi; exit 11; fi";

        CommandResult result = runRootCommand(command);
        if (!result.isSuccessful()) {
            throw new IOException("切换 Ubuntu rootfs 目录失败。\n" + result.output);
        }
    }

    private static void restoreBackupRootfs() {
        runRootCommandIgnoringFailure("rm -rf " + UbuntuRootUtils.UBUNTU_ROOTFS_PATH
            + "; if [ -e " + UbuntuRootUtils.UBUNTU_ROOTFS_BACKUP_PATH + " ]; then "
            + "mv " + UbuntuRootUtils.UBUNTU_ROOTFS_BACKUP_PATH + " " + UbuntuRootUtils.UBUNTU_ROOTFS_PATH + "; fi");
    }

    private static void installEntrypointScripts(@NonNull Context context) throws Exception {
        CommandResult mkdirResult = runRootCommand("mkdir -p /data/local/bin && chmod 755 /data/local/bin");
        if (!mkdirResult.isSuccessful()) {
            throw new IOException("创建 /data/local/bin 失败。\n" + mkdirResult.output);
        }

        for (String scriptName : SCRIPT_ASSET_NAMES) {
            String assetPath = "ubuntu/bin/" + scriptName;
            String targetPath = "/data/local/bin/" + scriptName;
            try (InputStream inputStream = context.getAssets().open(assetPath, AssetManager.ACCESS_STREAMING)) {
                CommandResult result = runRootCommandWithInput("cat > " + targetPath + " && chmod 755 " + targetPath,
                    inputStream, -1, null, 0, 0, null);
                if (!result.isSuccessful()) {
                    throw new IOException("写入启动脚本失败: " + targetPath + "\n" + result.output);
                }
            }
        }
    }

    private static void installUbuntuCoreScript(@NonNull Context context, @NonNull String rootfsPath) throws Exception {
        String targetPath = rootfsPath + "/.host/ubuntu-core.sh";
        CommandResult mkdirResult = runRootCommand("mkdir -p " + rootfsPath + "/.host && chmod 755 "
            + rootfsPath + "/.host");
        if (!mkdirResult.isSuccessful()) {
            throw new IOException("创建 Ubuntu 宿主脚本目录失败。\n" + mkdirResult.output);
        }

        try (InputStream inputStream = context.getAssets().open(UBUNTU_CORE_ASSET_PATH, AssetManager.ACCESS_STREAMING)) {
            CommandResult result = runRootCommandWithInput("cat > " + targetPath + " && chmod 755 " + targetPath,
                inputStream, -1, null, 0, 0, null);
            if (!result.isSuccessful()) {
                throw new IOException("写入 Ubuntu 宿主启动脚本失败: " + targetPath + "\n" + result.output);
            }
        }
    }

    @NonNull
    private static CommandResult runRootCommand(@NonNull String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(UbuntuRootUtils.SYSTEM_SU_PATH, "-Z",
            UbuntuRootUtils.MAGISK_SU_CONTEXT, "-c", command).redirectErrorStream(true).start();
        String output = readProcessOutput(process);
        int exitCode = process.waitFor();
        return new CommandResult(exitCode, output);
    }

    private static void runRootCommandIgnoringFailure(@NonNull String command) {
        try {
            runRootCommand(command);
        } catch (Exception ignored) {
            // Best effort cleanup only.
        }
    }

    @NonNull
    private static CommandResult runRootCommandWithInput(@NonNull String command, @NonNull InputStream inputStream,
                                                        long totalBytes, @Nullable ProgressReporter progressUi,
                                                        int progressStart, int progressWeight,
                                                        @Nullable String progressMessage)
        throws IOException, InterruptedException {
        Process process = new ProcessBuilder(UbuntuRootUtils.SYSTEM_SU_PATH, "-Z",
            UbuntuRootUtils.MAGISK_SU_CONTEXT, "-c", command).redirectErrorStream(true).start();

        StringBuilder outputBuilder = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append('\n');
                }
            } catch (IOException e) {
                outputBuilder.append(e.getMessage()).append('\n');
            }
        }, "UbuntuRootfsInstallerOutput");
        outputReader.start();

        byte[] buffer = new byte[(int) ONE_MIB];
        long writtenBytes = 0;
        int lastProgress = -1;
        long startedAt = System.currentTimeMillis();
        try (OutputStream outputStream = process.getOutputStream()) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
                writtenBytes += read;

                if (progressUi != null && totalBytes > 0 && progressMessage != null) {
                    int progress = progressStart + getWeightedProgress(writtenBytes, totalBytes, progressWeight);
                    if (progress != lastProgress) {
                        progressUi.update(progressMessage + " " + progress + "% " + getProgressHint(writtenBytes, totalBytes, startedAt), progress);
                        lastProgress = progress;
                    }
                }
            }
        } catch (IOException e) {
            process.destroy();
            throw e;
        }

        int exitCode = process.waitFor();
        outputReader.join();
        return new CommandResult(exitCode, outputBuilder.toString());
    }

    @NonNull
    private static String readProcessOutput(@NonNull Process process) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private static int getWeightedProgress(long currentBytes, long totalBytes, int weight) {
        if (totalBytes <= 0) return 0;
        return (int) Math.min(weight, (currentBytes * weight) / totalBytes);
    }

    @NonNull
    private static String toHex(@NonNull byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            hex[i * 2] = digits[value >>> 4];
            hex[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(hex);
    }

    @NonNull
    private static String join(@NonNull String[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(values[i]);
        }
        return builder.toString();
    }

    @NonNull
    private static String formatBytes(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / (double) ONE_GIB);
    }

    @NonNull
    private static String getProgressHint(long currentBytes, long totalBytes, long startedAt) {
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startedAt);
        double bytesPerSecond = currentBytes * 1000d / elapsedMs;
        if (bytesPerSecond <= 1d || currentBytes <= 0 || totalBytes <= 0) return "";

        long remainingMs = (long) ((totalBytes - currentBytes) * 1000d / bytesPerSecond);
        return "(" + formatBytesPerSecond(bytesPerSecond) + ", 剩余约 " + formatDuration(remainingMs) + ")";
    }

    @NonNull
    private static String formatBytesPerSecond(double bytesPerSecond) {
        double mib = bytesPerSecond / (double) ONE_MIB;
        return String.format(Locale.US, "%.1f MB/s", mib);
    }

    @NonNull
    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0) return seconds + " 秒";
        return minutes + " 分 " + seconds + " 秒";
    }

    private static void showInstallErrorDialog(@NonNull Activity activity, @NonNull InstallProgressUi progressUi,
                                               @NonNull Runnable whenDone, @Nullable Runnable whenFailed,
                                               @NonNull String message) {
        progressUi.dismiss();

        try {
            new AlertDialog.Builder(activity)
                .setTitle("Ubuntu 22.04 安装失败")
                .setMessage(getFriendlyErrorMessage(message))
                .setNegativeButton("退出", (dialog, which) -> {
                    dialog.dismiss();
                    if (whenFailed != null) whenFailed.run();
                    activity.finish();
                })
                .setPositiveButton("重试", (dialog, which) -> {
                    dialog.dismiss();
                    setupIfNeeded(activity, whenDone, whenFailed);
                })
                .setNeutralButton("Ubuntu 管理", (dialog, which) -> {
                    dialog.dismiss();
                    activity.startActivity(new android.content.Intent(activity, UbuntuManagementActivity.class));
                    if (whenFailed != null) whenFailed.run();
                })
                .show();
        } catch (WindowManager.BadTokenException e) {
            if (whenFailed != null) whenFailed.run();
        }
    }

    @NonNull
    private static String getFriendlyErrorMessage(@NonNull String rawMessage) {
        String lower = rawMessage.toLowerCase(Locale.US);
        String suggestion;

        if (lower.contains("root") || lower.contains("su")) {
            suggestion = "没有获得 root 权限，或者 su 授权被拒绝。请确认设备已 root，并在授权弹窗里允许本应用。";
        } else if (lower.contains("busybox")) {
            suggestion = "没有找到 Magisk busybox。请确认当前 root 方案是 Magisk，并且 /data/adb/magisk/busybox 存在。";
        } else if (lower.contains("abi")) {
            suggestion = "设备 CPU 架构不匹配。当前 APK 内置的是 arm64-v8a Ubuntu rootfs。";
        } else if (lower.contains("space") || lower.contains("空间")) {
            suggestion = "设备 /data 空间不足。rootfs 释放需要同时容纳压缩包、临时目录和最终目录。";
        } else if (lower.contains("sha") || lower.contains("校验")) {
            suggestion = "内置 rootfs 校验失败，APK 里的 rootfs 可能损坏，请重新打包或重新安装 APK。";
        } else if (lower.contains("tar") || lower.contains("解压")) {
            suggestion = "rootfs 解压失败，通常和 busybox、root 权限或剩余空间有关。";
        } else {
            suggestion = "安装过程没有完成。可以重试，或进入 Ubuntu 管理页执行修复/重装。";
        }

        return suggestion + "\n\n技术详情：\n" + rawMessage;
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, @NonNull String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        boolean isSuccessful() {
            return exitCode == 0;
        }
    }

    private static final class InstallProgressUi implements ProgressReporter {
        private final Activity activity;
        private final StringBuilder logBuilder = new StringBuilder();
        private AlertDialog dialog;
        private TextView statusView;
        private TextView logView;
        private ProgressBar progressBar;

        InstallProgressUi(@NonNull Activity activity) {
            this.activity = activity;
        }

        void show() {
            activity.runOnUiThread(() -> {
                LinearLayout container = new LinearLayout(activity);
                container.setOrientation(LinearLayout.VERTICAL);
                int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
                container.setPadding(padding, padding, padding, padding);

                statusView = new TextView(activity);
                statusView.setText("正在准备 Ubuntu 22.04 安装...");
                container.addView(statusView);

                progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                progressBar.setMax(100);
                progressBar.setProgress(0);
                LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                progressParams.setMargins(0, padding / 2, 0, padding / 2);
                container.addView(progressBar, progressParams);

                logView = new TextView(activity);
                logView.setTextIsSelectable(true);
                ScrollView scrollView = new ScrollView(activity);
                scrollView.addView(logView);
                container.addView(scrollView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (180 * activity.getResources().getDisplayMetrics().density)));

                dialog = new AlertDialog.Builder(activity)
                    .setTitle("首次安装 Ubuntu 22.04")
                    .setView(container)
                    .setCancelable(false)
                    .create();
                dialog.setCanceledOnTouchOutside(false);
                dialog.show();
            });
        }

        public void update(@NonNull String status, int progress) {
            activity.runOnUiThread(() -> {
                if (statusView != null) statusView.setText(status);
                if (progressBar != null) progressBar.setProgress(Math.max(0, Math.min(100, progress)));
            });
        }

        public void append(@NonNull String line) {
            activity.runOnUiThread(() -> {
                logBuilder.append("- ").append(line).append('\n');
                if (logView != null) logView.setText(logBuilder.toString());
            });
        }

        void dismiss() {
            activity.runOnUiThread(() -> {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
            });
        }
    }
}
