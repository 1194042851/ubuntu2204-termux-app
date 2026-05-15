package com.termux.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.termux.R;

public final class UbuntuManagementActivity extends AppCompatActivity {

    public static final String EXTRA_ACTION = "com.termux.app.extra.UBUNTU_ACTION";
    public static final String ACTION_REPAIR = "repair";
    public static final String ACTION_REINSTALL = "reinstall";
    public static final String ACTION_DELETE = "delete";

    private TextView mStatusView;
    private TextView mOperationStatusView;
    private TextView mLogView;
    private ProgressBar mProgressBar;
    private MaterialButton mRefreshButton;
    private MaterialButton mRepairButton;
    private MaterialButton mReinstallButton;
    private MaterialButton mDeleteButton;
    private MaterialButton mCloseButton;
    private final StringBuilder mLogBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ubuntu_management);

        mStatusView = findViewById(R.id.ubuntu_management_status);
        mOperationStatusView = findViewById(R.id.ubuntu_management_operation_status);
        mLogView = findViewById(R.id.ubuntu_management_log);
        mProgressBar = findViewById(R.id.ubuntu_management_progress);
        mRefreshButton = findViewById(R.id.ubuntu_management_refresh_button);
        mRepairButton = findViewById(R.id.ubuntu_management_repair_button);
        mReinstallButton = findViewById(R.id.ubuntu_management_reinstall_button);
        mDeleteButton = findViewById(R.id.ubuntu_management_delete_button);
        mCloseButton = findViewById(R.id.ubuntu_management_close_button);

        mRefreshButton.setOnClickListener(v -> refreshStatus());
        mRepairButton.setOnClickListener(v -> confirmAndRun(
            "修复启动脚本",
            "重新写入 /data/local/bin/ubuntu22* 和 rootfs 内的 .host/ubuntu-core.sh，不删除 Ubuntu 数据。",
            progress -> UbuntuRootfsManager.repairScripts(this, progress)));
        mReinstallButton.setOnClickListener(v -> confirmAndRun(
            "重装 Ubuntu",
            "删除当前 /data/local/ubuntu-22.04，然后从 APK 内置 rootfs 重新安装。",
            progress -> UbuntuRootfsManager.reinstallUbuntu(this, progress)));
        mDeleteButton.setOnClickListener(v -> confirmAndRun(
            "删除 Ubuntu 数据",
            "删除 /data/local/ubuntu-22.04、临时目录、备份目录和 /data/local/bin/ubuntu22*，不可恢复。",
            progress -> UbuntuRootfsManager.deleteUbuntuData(this, progress)));
        mCloseButton.setOnClickListener(v -> finish());

        refreshStatus();
        runRequestedActionIfNeeded();
    }

    private void refreshStatus() {
        mStatusView.setText("Checking status...");
        new Thread(() -> {
            UbuntuRootfsManager.Status status = UbuntuRootfsManager.getStatus(this);
            runOnUiThread(() -> mStatusView.setText(status.getSummary()));
        }, "UbuntuStatusRefresh").start();
    }

    private void confirmAndRun(@NonNull String title, @NonNull String message, @NonNull Operation operation) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Continue", (dialog, which) -> runOperation(title, operation))
            .show();
    }

    private void runRequestedActionIfNeeded() {
        String action = getIntent().getStringExtra(EXTRA_ACTION);
        if (action == null) return;
        mStatusView.post(() -> {
            if (ACTION_REPAIR.equals(action)) {
                confirmAndRun("修复启动脚本", "重新写入 Ubuntu 启动脚本，不删除 Ubuntu 数据。",
                    progress -> UbuntuRootfsManager.repairScripts(this, progress));
            } else if (ACTION_REINSTALL.equals(action)) {
                confirmAndRun("重装 Ubuntu", "删除当前 Ubuntu 后重新释放 APK 内置 rootfs。",
                    progress -> UbuntuRootfsManager.reinstallUbuntu(this, progress));
            } else if (ACTION_DELETE.equals(action)) {
                confirmAndRun("删除 Ubuntu 数据", "删除 Ubuntu rootfs 和启动脚本，不可恢复。",
                    progress -> UbuntuRootfsManager.deleteUbuntuData(this, progress));
            }
        });
    }

    private void runOperation(@NonNull String title, @NonNull Operation operation) {
        setBusy(true);
        clearLog();
        PageProgressReporter progress = new PageProgressReporter();
        progress.update(title + ": preparing...", 0);

        new Thread(() -> {
            try {
                operation.run(progress);
                runOnUiThread(() -> {
                    progress.update(title + ": done", 100);
                    setBusy(false);
                    refreshStatus();
                    new AlertDialog.Builder(this)
                        .setTitle("Done")
                        .setMessage(title + " finished.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    refreshStatus();
                    new AlertDialog.Builder(this)
                        .setTitle(title + " failed")
                        .setMessage(UbuntuRootfsManager.friendlyError(e))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
            }
        }, "UbuntuManagementOperation").start();
    }

    private void setBusy(boolean busy) {
        mRefreshButton.setEnabled(!busy);
        mRepairButton.setEnabled(!busy);
        mReinstallButton.setEnabled(!busy);
        mDeleteButton.setEnabled(!busy);
        mCloseButton.setEnabled(!busy);
    }

    private void clearLog() {
        mLogBuilder.setLength(0);
        mLogView.setText("");
        mProgressBar.setProgress(0);
    }

    private interface Operation {
        void run(@NonNull UbuntuRootfsInstaller.ProgressReporter progress) throws Exception;
    }

    private final class PageProgressReporter implements UbuntuRootfsInstaller.ProgressReporter {
        @Override
        public void update(@NonNull String status, int progress) {
            runOnUiThread(() -> {
                mOperationStatusView.setText(status);
                mProgressBar.setProgress(Math.max(0, Math.min(100, progress)));
            });
        }

        @Override
        public void append(@NonNull String line) {
            runOnUiThread(() -> {
                mLogBuilder.append("- ").append(line).append('\n');
                mLogView.setText(mLogBuilder.toString());
            });
        }
    }
}
