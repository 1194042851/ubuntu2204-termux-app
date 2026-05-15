package com.termux.app;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.android.PackageUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.shell.command.runner.app.AppShell;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class UbuntuRootUtils {

    public static final String SYSTEM_SU_PATH = "/system/bin/su";
    public static final String SYSTEM_SH_PATH = "/system/bin/sh";
    public static final String MAGISK_SU_CONTEXT = "u:r:su:s0";
    public static final String ANDROID_SYSTEM_PATH = "/system/bin:/system/xbin:/product/bin:/system_ext/bin:/vendor/bin:/vendor/xbin:/odm/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin";
    public static final String UBUNTU_ENTRYPOINT = "/data/local/bin/ubuntu22";
    public static final String UBUNTU_STOP_ENTRYPOINT = "/data/local/bin/ubuntu22-stop";
    public static final String UBUNTU_SSHD_START_ENTRYPOINT = "/data/local/bin/ubuntu22-sshd-start";
    public static final String UBUNTU_SSHD_STOP_ENTRYPOINT = "/data/local/bin/ubuntu22-sshd-stop";
    public static final String UBUNTU_PASSWD_ENTRYPOINT = "/data/local/bin/ubuntu22-passwd";
    public static final String UBUNTU_ROOTFS_PATH = "/data/local/ubuntu-22.04";
    public static final String UBUNTU_ROOTFS_TMP_PATH = "/data/local/ubuntu-22.04.tmp";
    public static final String UBUNTU_ROOTFS_BACKUP_PATH = "/data/local/ubuntu-22.04.bak";
    public static final String UBUNTU_INSTALL_MARKER = UBUNTU_ROOTFS_PATH + "/.ubuntu2204-installed";
    public static final String MAGISK_BUSYBOX_PATH = "/data/adb/magisk/busybox";

    private UbuntuRootUtils() {}

    @NonNull
    public static String[] getDefaultUbuntuSessionArguments() {
        return buildSuArguments(buildShellCommand(UBUNTU_ENTRYPOINT, null));
    }

    @NonNull
    public static String getDefaultUbuntuSessionName() {
        return "ubuntu2204";
    }

    public static boolean isExecutableFile(String path) {
        if (path == null || path.isEmpty()) return false;
        File file = new File(path);
        return file.isFile() && file.canExecute();
    }

    public static boolean canRunSu(@NonNull Context context) {
        return runRootCheck(context, "id");
    }

    public static boolean hasUbuntuEntrypoint(@NonNull Context context) {
        return runRootCheck(context, "test -f '" + UBUNTU_ENTRYPOINT + "'");
    }

    public static boolean hasUbuntuRootfs(@NonNull Context context) {
        return runRootCheck(context, "test -d '" + UBUNTU_ROOTFS_PATH + "'");
    }

    public static boolean hasUbuntuInstallMarker(@NonNull Context context) {
        return runRootCheck(context, "test -f '" + UBUNTU_INSTALL_MARKER + "'");
    }

    public static boolean hasMagiskBusybox(@NonNull Context context) {
        return runRootCheck(context, "test -x '" + MAGISK_BUSYBOX_PATH + "'");
    }

    public static boolean canStartUbuntuSession(@NonNull Context context) {
        return runRootCheck(context, buildShellCommand(UBUNTU_ENTRYPOINT, "true"));
    }

    @NonNull
    public static String buildShellCommand(@NonNull String scriptPath, String argument) {
        StringBuilder builder = new StringBuilder();
        builder.append("PATH=").append(ANDROID_SYSTEM_PATH).append(" ").append(SYSTEM_SH_PATH).append(" ").append(scriptPath);
        if (argument != null && !argument.isEmpty())
            builder.append(" ").append(argument);
        return builder.toString();
    }

    private static boolean runRootCheck(@NonNull Context context, @NonNull String shellSnippet) {
        return runRootCheck(context, buildSuArguments(shellSnippet));
    }

    private static boolean runRootCheck(@NonNull Context context, @NonNull String[] arguments) {
        ExecutionCommand executionCommand = new ExecutionCommand(-1, SYSTEM_SU_PATH,
            arguments, null, "/",
            ExecutionCommand.Runner.APP_SHELL.getName(), false);
        executionCommand.commandLabel = "ubuntu root check";

        AppShell appShell = AppShell.execute(context, executionCommand, null,
            new TermuxShellEnvironment(), null, true);

        return appShell != null && executionCommand.isSuccessful() && executionCommand.resultData.exitCode != null
            && executionCommand.resultData.exitCode == 0;
    }

    @NonNull
    public static String getUbuntuWorkingDirectory() {
        return "/";
    }

    @NonNull
    private static String[] buildSuArguments(@NonNull String shellSnippet) {
        return new String[]{"-Z", MAGISK_SU_CONTEXT, "-c", shellSnippet};
    }

    @NonNull
    public static String getMissingRequirementsMessage(@NonNull Context context) {
        List<String> missing = new ArrayList<>();

        if (!canRunSu(context))
            missing.add("- missing su binary: " + SYSTEM_SU_PATH);
        if (!hasUbuntuEntrypoint(context))
            missing.add("- missing ubuntu entrypoint: " + UBUNTU_ENTRYPOINT);
        if (!hasUbuntuRootfs(context))
            missing.add("- missing ubuntu rootfs: " + UBUNTU_ROOTFS_PATH);
        if (!hasMagiskBusybox(context))
            missing.add("- missing magisk busybox: " + MAGISK_BUSYBOX_PATH);
        if (missing.isEmpty()) return "";

        String appName = PackageUtils.getAppNameForPackage(context);
        StringBuilder builder = new StringBuilder();
        builder.append(appName).append(" requires a rooted device with the validated Ubuntu 22.04 backend installed.\n\n");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) builder.append("\n");
            builder.append(missing.get(i));
        }

        builder.append("\n\nExpected backend:\n");
        builder.append("- ").append(UBUNTU_ENTRYPOINT).append("\n");
        builder.append("- ").append(UBUNTU_ROOTFS_PATH).append("\n");
        builder.append("- ").append(MAGISK_BUSYBOX_PATH);

        return builder.toString();
    }
}
