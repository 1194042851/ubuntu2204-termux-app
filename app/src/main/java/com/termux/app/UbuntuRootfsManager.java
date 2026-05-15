package com.termux.app;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

public final class UbuntuRootfsManager {

    private UbuntuRootfsManager() {}

    public static final class Status {
        public final boolean rootAvailable;
        public final boolean busyboxAvailable;
        public final boolean rootfsExists;
        public final boolean markerExists;
        public final boolean entrypointExists;
        public final boolean canStart;
        public final String rootfsSize;

        Status(boolean rootAvailable, boolean busyboxAvailable, boolean rootfsExists,
               boolean markerExists, boolean entrypointExists, boolean canStart,
               @NonNull String rootfsSize) {
            this.rootAvailable = rootAvailable;
            this.busyboxAvailable = busyboxAvailable;
            this.rootfsExists = rootfsExists;
            this.markerExists = markerExists;
            this.entrypointExists = entrypointExists;
            this.canStart = canStart;
            this.rootfsSize = rootfsSize;
        }

        @NonNull
        public String getSummary() {
            if (!rootAvailable) return "Root: unavailable\nUbuntu: cannot check";
            if (!rootfsExists) return "Root: available\nUbuntu: not installed";
            if (!entrypointExists) return "Root: available\nUbuntu: installed, entrypoint missing";
            if (!busyboxAvailable) return "Root: available\nUbuntu: installed, busybox missing";
            if (!canStart) return "Root: available\nUbuntu: start check failed\nSize: " + rootfsSize;
            return "Root: available\nUbuntu: startable\nSize: " + rootfsSize;
        }
    }

    public static final class CommandResult {
        public final int exitCode;
        public final String output;

        CommandResult(int exitCode, @NonNull String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean isSuccessful() {
            return exitCode == 0;
        }
    }

    @NonNull
    public static Status getStatus(@NonNull Context context) {
        boolean rootAvailable = canRunRootCommand("id");
        boolean busyboxAvailable = rootAvailable && hasBusybox();
        boolean rootfsExists = rootAvailable && runRootTest("test -d '" + UbuntuRootUtils.UBUNTU_ROOTFS_PATH + "'");
        boolean markerExists = rootAvailable && runRootTest("test -f '" + UbuntuRootUtils.UBUNTU_INSTALL_MARKER + "'");
        boolean entrypointExists = rootAvailable && runRootTest("test -f '" + UbuntuRootUtils.UBUNTU_ENTRYPOINT + "'");
        boolean canStart = rootAvailable && busyboxAvailable && rootfsExists && entrypointExists
            && runRootTest(UbuntuRootUtils.buildShellCommand(UbuntuRootUtils.UBUNTU_ENTRYPOINT, "true"));
        String rootfsSize = rootfsExists ? getRootfsSize() : "-";

        return new Status(rootAvailable, busyboxAvailable, rootfsExists, markerExists,
            entrypointExists, canStart, rootfsSize);
    }

    public static void deleteUbuntuData(@NonNull Context context,
                                        @NonNull UbuntuRootfsInstaller.ProgressReporter progress) throws Exception {
        progress.update("Stopping Ubuntu sessions...", 10);
        progress.append("Trying to stop chroot and background services.");
        runRootCommandIgnoringFailure("if [ -x " + shellQuote(UbuntuRootUtils.UBUNTU_STOP_ENTRYPOINT)
            + " ]; then " + UbuntuRootUtils.buildShellCommand(UbuntuRootUtils.UBUNTU_STOP_ENTRYPOINT, null)
            + " || true; fi");

        progress.update("Deleting Ubuntu rootfs...", 35);
        progress.append("Removing " + UbuntuRootUtils.UBUNTU_ROOTFS_PATH + " and temp/backup folders.");
        CommandResult deleteRootfs = runRootCommand("rm -rf "
            + shellQuote(UbuntuRootUtils.UBUNTU_ROOTFS_PATH) + " "
            + shellQuote(UbuntuRootUtils.UBUNTU_ROOTFS_TMP_PATH) + " "
            + shellQuote(UbuntuRootUtils.UBUNTU_ROOTFS_BACKUP_PATH));
        if (!deleteRootfs.isSuccessful()) {
            throw new IOException("Failed to delete Ubuntu rootfs.\n" + deleteRootfs.output);
        }

        progress.update("Deleting entrypoint scripts...", 70);
        CommandResult deleteScripts = runRootCommand("rm -f "
            + shellQuote(UbuntuRootUtils.UBUNTU_ENTRYPOINT) + " "
            + shellQuote(UbuntuRootUtils.UBUNTU_STOP_ENTRYPOINT) + " "
            + shellQuote(UbuntuRootUtils.UBUNTU_SSHD_START_ENTRYPOINT) + " "
            + shellQuote(UbuntuRootUtils.UBUNTU_SSHD_STOP_ENTRYPOINT) + " "
            + shellQuote(UbuntuRootUtils.UBUNTU_PASSWD_ENTRYPOINT));
        if (!deleteScripts.isSuccessful()) {
            throw new IOException("Failed to delete Ubuntu entrypoint scripts.\n" + deleteScripts.output);
        }

        UbuntuRootfsInstaller.clearInstallReady(context);
        progress.update("Ubuntu data deleted.", 100);
        progress.append("This should be done before uninstalling the APK to avoid leftovers in /data/local.");
    }

    public static void reinstallUbuntu(@NonNull Context context,
                                       @NonNull UbuntuRootfsInstaller.ProgressReporter progress) throws Exception {
        progress.update("Preparing reinstall...", 1);
        progress.append("Reinstall first removes current Ubuntu data, then extracts the built-in rootfs.");
        deleteUbuntuData(context, progress);
        UbuntuRootfsInstaller.installBlocking(context, progress);
    }

    public static void repairScripts(@NonNull Context context,
                                     @NonNull UbuntuRootfsInstaller.ProgressReporter progress) throws Exception {
        UbuntuRootfsInstaller.repairScriptsBlocking(context, progress);
    }

    @NonNull
    public static CommandResult runRootCommand(@NonNull String command) throws IOException, InterruptedException {
        Process process = startSuProcess(command, true);
        String output = readProcessOutput(process);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            Process fallback = startSuProcess(command, false);
            String fallbackOutput = readProcessOutput(fallback);
            int fallbackExitCode = fallback.waitFor();
            if (fallbackExitCode == 0 || fallbackOutput.length() > output.length()) {
                return new CommandResult(fallbackExitCode, fallbackOutput);
            }
        }
        return new CommandResult(exitCode, output);
    }

    public static void runRootCommandIgnoringFailure(@NonNull String command) {
        try {
            runRootCommand(command);
        } catch (Exception ignored) {
            // Best effort cleanup only.
        }
    }

    @NonNull
    public static String shellQuote(@NonNull String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @NonNull
    private static String getRootfsSize() {
        try {
            CommandResult result = runRootCommand("du -sh " + shellQuote(UbuntuRootUtils.UBUNTU_ROOTFS_PATH) + " 2>/dev/null | awk '{print $1}'");
            String output = result.output.trim();
            if (!output.isEmpty()) return output;
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    @NonNull
    public static String friendlyError(@NonNull Throwable throwable) {
        String message = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        String lower = message.toLowerCase(Locale.US);

        if (lower.contains("su") || lower.contains("root")) {
            return "No root permission, or su was denied.\n\nDetails:\n" + message;
        }
        if (lower.contains("busybox")) {
            return "Magisk busybox is unavailable.\n\nDetails:\n" + message;
        }
        if (lower.contains("space")) {
            return "Not enough /data space.\n\nDetails:\n" + message;
        }
        if (lower.contains("rootfs")) {
            return "Ubuntu rootfs is in a bad state.\n\nDetails:\n" + message;
        }
        return "Operation failed.\n\nDetails:\n" + message;
    }

    private static boolean hasBusybox() {
        return runRootTest("test -x '" + UbuntuRootUtils.MAGISK_BUSYBOX_PATH + "'");
    }

    private static boolean runRootTest(@NonNull String command) {
        try {
            return runRootCommand(command).isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean canRunRootCommand(@NonNull String command) {
        return runRootTest(command);
    }

    @NonNull
    private static Process startSuProcess(@NonNull String command, boolean preferContext) throws IOException {
        String[] args = preferContext
            ? new String[]{UbuntuRootUtils.SYSTEM_SU_PATH, "-Z", UbuntuRootUtils.MAGISK_SU_CONTEXT, "-c", command}
            : new String[]{UbuntuRootUtils.SYSTEM_SU_PATH, "-c", command};
        return new ProcessBuilder(args).redirectErrorStream(true).start();
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
}
