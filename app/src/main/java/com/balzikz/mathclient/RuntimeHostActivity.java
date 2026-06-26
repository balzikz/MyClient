package com.balzikz.mathclient;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;

import com.mojang.minecraftpe.MainActivity;

public final class RuntimeHostActivity extends MainActivity {
    private AssetManager targetAssets;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            targetAssets = base.createPackageContext("com.mojang.minecraftpe", 0).getAssets();
            HostJournal.write(base, "TARGET_ASSETS_READY", "package=com.mojang.minecraftpe");
        } catch (Throwable error) {
            targetAssets = null;
            HostJournal.write(base, "TARGET_ASSETS_FAIL",
                    error.getClass().getName() + ": " + error.getMessage());
        }
    }

    @Override
    public AssetManager getAssets() {
        return targetAssets != null ? targetAssets : super.getAssets();
    }

    @Override
    protected void onCreate(Bundle state) {
        marker("BEFORE_RUNTIME_HOST_ONCREATE");
        if (!MathApplication.isHostReady()) {
            HostJournal.write(this, "HOST_NOT_READY", MathApplication.hostStatus());
            throw new IllegalStateException(MathApplication.hostStatus());
        }

        HostJournal.write(this, "JAVA_HOST_CLASS",
                "actual=" + getClass().getName()
                        + " mojangMainActivity=" + (this instanceof MainActivity));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        HostJournal.write(this, "BEFORE_RUNTIME_HOST_ONCREATE",
                MathShimBridge.combinedStatus());
        super.onCreate(state);
        marker("AFTER_RUNTIME_HOST_ONCREATE");
        HostJournal.write(this, "AFTER_RUNTIME_HOST_ONCREATE",
                "nativeHandle=" + getGameActivityNativeHandle()
                        + " " + MathShimBridge.combinedStatus());

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onStart() {
        marker("BEFORE_BEDROCK_ONSTART");
        HostJournal.write(this, "BEFORE_ONSTART", MathShimBridge.combinedStatus());
        super.onStart();
        marker("AFTER_BEDROCK_ONSTART");
        HostJournal.write(this, "AFTER_ONSTART", MathShimBridge.combinedStatus());
    }

    @Override
    protected void onResume() {
        marker("BEFORE_BEDROCK_ONRESUME");
        HostJournal.write(this, "BEFORE_ONRESUME", MathShimBridge.combinedStatus());
        super.onResume();
        marker("AFTER_BEDROCK_ONRESUME");
        HostJournal.write(this, "AFTER_ONRESUME", MathShimBridge.combinedStatus());
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        marker("BEFORE_BEDROCK_SURFACE_CREATED");
        super.surfaceCreated(holder);
        marker("AFTER_BEDROCK_SURFACE_CREATED");
        HostJournal.write(this, "SURFACE_CREATED",
                "valid=" + holder.getSurface().isValid()
                        + " " + MathShimBridge.combinedStatus());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        marker("BEFORE_BEDROCK_SURFACE_CHANGED");
        super.surfaceChanged(holder, format, width, height);
        marker("AFTER_BEDROCK_SURFACE_CHANGED");
        HostJournal.write(this, "SURFACE_CHANGED",
                width + "x" + height + " format=" + format
                        + " " + MathShimBridge.combinedStatus());
    }

    private static void marker(String value) {
        LinkerBridge.setSignalMarker(value);
    }
}
