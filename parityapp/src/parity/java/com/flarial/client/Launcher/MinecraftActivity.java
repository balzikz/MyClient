package com.flarial.client.Launcher;

import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import com.balzikz.mathclient.HostJournal;

import org.conscrypt.Conscrypt;

import java.io.File;
import java.security.Security;

/**
 * Minimal Flarial-compatible launch host. It intentionally contains no client
 * UI, modules, hooks or gameplay modifications.
 */
public final class MinecraftActivity extends com.mojang.minecraftpe.MainActivity {
    private GamePackageManager gameManager;

    // Registered by the authorized Flarial libshin.so.
    private native void nativeConfigureShimLogger(String path);
    private native void nativePreloadLibrary(String path);
    private native void nativeOnLauncherLoaded(String minecraftPath);

    // Registered by our no-op parity client library.
    private native void nativeConfigureClientLogger(String path);
    private native void nativeRegisterActivity(MinecraftActivity activity);

    @Override
    public AssetManager getAssets() {
        GamePackageManager manager = gameManager;
        return manager == null ? super.getAssets() : manager.assets();
    }

    @Override
    protected void onCreate(Bundle state) {
        configureFullscreenWindow();
        HostJournal.write(this, "PARITY_ONCREATE_ENTER", "before game manager");

        try {
            gameManager = new GamePackageManager(getApplicationContext());

            HostJournal.write(this, "ORDER_01", "Security.insertProviderAt(Conscrypt, 1)");
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1);
                HostJournal.write(this, "CONSCRYPT_READY", "provider=" + Security.getProviders()[0].getName());
            } catch (Throwable error) {
                HostJournal.write(this, "CONSCRYPT_WARN", error.toString());
            }

            HostJournal.write(this, "ORDER_02", "System.loadLibrary(shin)");
            System.loadLibrary("shin");
            HostJournal.write(this, "SHIN_LOAD_RETURN", "OK");

            File shimLog = new File(getFilesDir(), "flarial-parity-shin.log");
            nativeConfigureShimLogger(shimLog.getAbsolutePath());
            HostJournal.write(this, "ORDER_03", "nativeConfigureShimLogger OK");

            HostJournal.write(this, "ORDER_04", "prepare Minecraft runtime");
            gameManager.prepareRuntime();
            HostJournal.write(this, "RUNTIME_REPORT", gameManager.report());

            HostJournal.write(this, "ORDER_05", "preload Minecraft dependencies");
            gameManager.preloadDependencies();

            HostJournal.write(this, "ORDER_06", "System.load(libminecraftpe.so)");
            gameManager.loadGame();

            HostJournal.write(this, "ORDER_07", "System.loadLibrary(flarialclient no-op bootstrap)");
            System.loadLibrary("flarialclient");
            HostJournal.write(this, "PARITY_CLIENT_LOAD_RETURN", "OK");

            File clientLog = new File(getFilesDir(), "flarial-parity-client.log");
            nativeConfigureClientLogger(clientLog.getAbsolutePath());
            HostJournal.write(this, "ORDER_08", "nativeConfigureClientLogger OK");

            nativeRegisterActivity(this);
            HostJournal.write(this, "ORDER_09", "nativeRegisterActivity OK");

            nativeOnLauncherLoaded(gameManager.gamePath());
            HostJournal.write(this, "ORDER_10", "nativeOnLauncherLoaded RETURN");

            HostJournal.write(this, "ORDER_11", "super.onCreate START");
            super.onCreate(state);
            HostJournal.write(this, "ORDER_12", "super.onCreate RETURN");

            hideSystemBars();
            HostJournal.write(this, "PARITY_LAUNCH_COMPLETE", "first lifecycle return reached");
        } catch (Throwable error) {
            HostJournal.write(this, "PARITY_LAUNCH_FAILED",
                    error.getClass().getName() + ": " + error.getMessage());
            Toast.makeText(this,
                    "Parity launch failed: " + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void configureFullscreenWindow() {
        Window window = getWindow();
        window.setStatusBarColor(0);
        window.setNavigationBarColor(0);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.setAttributes(attributes);
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            Window window = getWindow();
            if (window.getInsetsController() != null) {
                window.getInsetsController().hide(WindowInsets.Type.systemBars());
                window.getInsetsController().setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
