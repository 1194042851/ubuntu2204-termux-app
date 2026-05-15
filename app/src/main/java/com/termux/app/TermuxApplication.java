package com.termux.app;

import android.app.Application;
import android.content.res.AssetManager;
import android.content.Context;

import com.termux.BuildConfig;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.theme.TermuxThemeUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class TermuxApplication extends Application {

    private static final String LOG_TAG = "TermuxApplication";
    private static final String DEFAULT_TERMINAL_FONT_ASSET_PATH = "fonts/CascadiaMono.ttf";

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // Set TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER and TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT
        TermuxBootstrap.setTermuxPackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT);

        // Init app wide SharedProperties loaded from termux.properties
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        // Init app wide shell manager
        TermuxShellManager shellManager = TermuxShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create app files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code.
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isFilesDirectoryAccessible = error == null;
        if (isFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "App files directory is accessible");

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps app directory failed\n" + error);
                return;
            }

            TermuxAmSocketServer.setupTermuxAmSocketServer(context);

            ensureBundledTerminalFontInstalled();
        } else {
            Logger.logErrorExtended(LOG_TAG, "App files directory is not accessible\n" + error);
        }

        TermuxShellEnvironment.init(this);

        if (isFilesDirectoryAccessible)
            TermuxShellEnvironment.writeEnvironmentToFile(this);
    }

    private void ensureBundledTerminalFontInstalled() {
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;

        if (fontFile.isFile() && fontFile.length() > 0) {
            Logger.logVerbose(LOG_TAG, "Skipping bundled terminal font install since custom font already exists at \""
                + fontFile.getAbsolutePath() + "\"");
            return;
        }

        Error error = FileUtils.createDirectoryFile("termux data home", TermuxConstants.TERMUX_DATA_HOME_DIR_PATH);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, "Failed to create terminal font parent directory\n" + error);
            return;
        }

        AssetManager assetManager = getAssets();
        try (InputStream inputStream = assetManager.open(DEFAULT_TERMINAL_FONT_ASSET_PATH);
             FileOutputStream outputStream = new FileOutputStream(fontFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.getFD().sync();
            Logger.logInfo(LOG_TAG, "Installed bundled terminal font to \"" + fontFile.getAbsolutePath() + "\"");
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to install bundled terminal font from asset \"" + DEFAULT_TERMINAL_FONT_ASSET_PATH + "\"", e);
        }
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
