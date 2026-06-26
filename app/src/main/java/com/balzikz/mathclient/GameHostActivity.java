package com.balzikz.mathclient;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import com.mojang.minecraftpe.MainActivity;

public final class GameHostActivity extends MainActivity {
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
        marker("BEFORE_BEDROCK_GAMEACTIVITY_FORWARD");
        if (!MathApplication.isHostReady()) {
            HostJournal.write(this, "HOST_NOT_READY", MathApplication.hostStatus());
            throw new IllegalStateException(MathApplication.hostStatus());
        }

        HostJournal.write(this, "JAVA_HOST_CLASS",
                "actual=" + getClass().getName()
                        + " mojangMainActivity=" + (this instanceof MainActivity));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        HostJournal.write(this, "BEFORE_BEDROCK_GAMEACTIVITY_FORWARD",
                MathShimBridge.nativeStatus());

        super.onCreate(state);

        marker("AFTER_BEDROCK_GAMEACTIVITY_FORWARD");
        if (!MathShimBridge.nativeIsBedrockForwarded()) {
            String status = MathShimBridge.nativeStatus();
            HostJournal.write(this, "BEDROCK_GAMEACTIVITY_NOT_CONFIRMED", status);
            throw new IllegalStateException(status);
        }

        SurfaceView surface = mSurfaceView;
        HostJournal.write(this, "BEDROCK_GAMEACTIVITY_FORWARD_CONFIRMED",
                "nativeHandle=" + getGameActivityNativeHandle()
                        + " surface=" + (surface == null
                        ? "NULL"
                        : surface.getWidth() + "x" + surface.getHeight())
                        + " " + MathShimBridge.nativeStatus());

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onStart() {
        marker("BEFORE_BEDROCK_ONSTART");
        HostJournal.write(this, "BEFORE_ONSTART", MathShimBridge.nativeStatus());
        super.onStart();
        marker("AFTER_BEDROCK_ONSTART");
        HostJournal.write(this, "AFTER_ONSTART", MathShimBridge.nativeStatus());
    }

    @Override
    protected void onResume() {
        marker("BEFORE_BEDROCK_ONRESUME");
        HostJournal.write(this, "BEFORE_ONRESUME", MathShimBridge.nativeStatus());
        super.onResume();
        marker("AFTER_BEDROCK_ONRESUME");
        HostJournal.write(this, "AFTER_ONRESUME", MathShimBridge.nativeStatus());
    }

    @Override
    protected void onPause() {
        marker("BEFORE_BEDROCK_ONPAUSE");
        HostJournal.write(this, "BEFORE_ONPAUSE", MathShimBridge.nativeStatus());
        super.onPause();
        marker("AFTER_BEDROCK_ONPAUSE");
        HostJournal.write(this, "AFTER_ONPAUSE", MathShimBridge.nativeStatus());
    }

    @Override
    protected void onStop() {
        marker("BEFORE_BEDROCK_ONSTOP");
        HostJournal.write(this, "BEFORE_ONSTOP", MathShimBridge.nativeStatus());
        super.onStop();
        marker("AFTER_BEDROCK_ONSTOP");
        HostJournal.write(this, "AFTER_ONSTOP", MathShimBridge.nativeStatus());
    }

    @Override
    protected void onDestroy() {
        marker("BEFORE_BEDROCK_ONDESTROY");
        HostJournal.write(this, "BEFORE_ONDESTROY", MathShimBridge.nativeStatus());
        super.onDestroy();
        marker("AFTER_BEDROCK_ONDESTROY");
        HostJournal.write(this, "AFTER_ONDESTROY", MathShimBridge.nativeStatus());
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        marker("BEFORE_BEDROCK_WINDOW_FOCUS_" + focused);
        super.onWindowFocusChanged(focused);
        marker("AFTER_BEDROCK_WINDOW_FOCUS_" + focused);
        HostJournal.write(this, "WINDOW_FOCUS", focused + " " + MathShimBridge.nativeStatus());
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        marker("BEFORE_BEDROCK_SURFACE_CREATED");
        super.surfaceCreated(holder);
        marker("AFTER_BEDROCK_SURFACE_CREATED");
        HostJournal.write(this, "SURFACE_CREATED",
                "valid=" + holder.getSurface().isValid() + " " + MathShimBridge.nativeStatus());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        marker("BEFORE_BEDROCK_SURFACE_CHANGED");
        super.surfaceChanged(holder, format, width, height);
        marker("AFTER_BEDROCK_SURFACE_CHANGED");
        HostJournal.write(this, "SURFACE_CHANGED",
                width + "x" + height + " format=" + format + " " + MathShimBridge.nativeStatus());
    }

    @Override
    public void surfaceRedrawNeeded(SurfaceHolder holder) {
        marker("BEFORE_BEDROCK_SURFACE_REDRAW");
        super.surfaceRedrawNeeded(holder);
        marker("AFTER_BEDROCK_SURFACE_REDRAW");
        HostJournal.write(this, "SURFACE_REDRAW", MathShimBridge.nativeStatus());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        marker("BEFORE_BEDROCK_SURFACE_DESTROYED");
        super.surfaceDestroyed(holder);
        marker("AFTER_BEDROCK_SURFACE_DESTROYED");
        HostJournal.write(this, "SURFACE_DESTROYED", MathShimBridge.nativeStatus());
    }

    private static void marker(String value) {
        LinkerBridge.setSignalMarker(value);
    }
}
