package com.balzikz.mathclient;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.io.File;

import dalvik.system.BaseDexClassLoader;

public final class GameHostActivity extends com.mojang.minecraftpe.MainActivity {
    private AssetManager targetAssets;
    private volatile ClassLoader redirectedClassLoader;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            targetAssets = base.createPackageContext("com.mojang.minecraftpe", 0).getAssets();
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
    public ClassLoader getClassLoader() {
        ClassLoader cached = redirectedClassLoader;
        if (cached != null) return cached;

        ClassLoader original = super.getClassLoader();
        if (!(original instanceof BaseDexClassLoader)) return original;

        File game = HostJournal.game(this);
        if (!game.isFile() || !game.canRead()) return original;

        synchronized (this) {
            if (redirectedClassLoader != null) return redirectedClassLoader;
            try {
                String path = game.getCanonicalPath();
                redirectedClassLoader = new BedrockLibraryClassLoader(
                        (BaseDexClassLoader) original,
                        path);
                HostJournal.write(this, "CLASSLOADER_REDIRECT_READY", path);
                return redirectedClassLoader;
            } catch (Throwable error) {
                HostJournal.write(this, "CLASSLOADER_REDIRECT_FAIL",
                        error.getClass().getName() + ": " + error.getMessage());
                return original;
            }
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        if (!MathApplication.isHostReady()) {
            HostJournal.write(this, "HOST_NOT_READY", MathApplication.hostStatus());
            throw new IllegalStateException(MathApplication.hostStatus());
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        HostJournal.write(this, "BEFORE_GAMEACTIVITY_ONCREATE", "Calling super.onCreate");
        super.onCreate(state);
        HostJournal.write(this, "AFTER_GAMEACTIVITY_ONCREATE", "super.onCreate returned");
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        SurfaceView surface = mSurfaceView;
        HostJournal.write(this, "SURFACE_VIEW_AFTER_ONCREATE",
                surface == null ? "NULL" : surface.getWidth() + "x" + surface.getHeight());
    }

    @Override
    protected void onStart() {
        HostJournal.write(this, "BEFORE_ONSTART", "Calling super.onStart");
        super.onStart();
        HostJournal.write(this, "AFTER_ONSTART", "super.onStart returned");
    }

    @Override
    protected void onResume() {
        HostJournal.write(this, "BEFORE_ONRESUME", "Calling super.onResume");
        super.onResume();
        HostJournal.write(this, "AFTER_ONRESUME", "super.onResume returned");
    }

    @Override
    protected void onPause() {
        HostJournal.write(this, "BEFORE_ONPAUSE", "Calling super.onPause");
        super.onPause();
        HostJournal.write(this, "AFTER_ONPAUSE", "super.onPause returned");
    }

    @Override
    protected void onStop() {
        HostJournal.write(this, "BEFORE_ONSTOP", "Calling super.onStop");
        super.onStop();
        HostJournal.write(this, "AFTER_ONSTOP", "super.onStop returned");
    }

    @Override
    protected void onDestroy() {
        HostJournal.write(this, "BEFORE_ONDESTROY", "Calling super.onDestroy");
        super.onDestroy();
        HostJournal.write(this, "AFTER_ONDESTROY", "super.onDestroy returned");
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        HostJournal.write(this, "BEFORE_WINDOW_FOCUS", Boolean.toString(focused));
        super.onWindowFocusChanged(focused);
        HostJournal.write(this, "AFTER_WINDOW_FOCUS", Boolean.toString(focused));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        HostJournal.write(this, "BEFORE_SURFACE_CREATED",
                Boolean.toString(holder.getSurface().isValid()));
        super.surfaceCreated(holder);
        HostJournal.write(this, "AFTER_SURFACE_CREATED",
                Boolean.toString(holder.getSurface().isValid()));
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        HostJournal.write(this, "BEFORE_SURFACE_CHANGED",
                width + "x" + height + " format=" + format);
        super.surfaceChanged(holder, format, width, height);
        HostJournal.write(this, "AFTER_SURFACE_CHANGED",
                width + "x" + height + " format=" + format);
    }

    @Override
    public void surfaceRedrawNeeded(SurfaceHolder holder) {
        HostJournal.write(this, "BEFORE_SURFACE_REDRAW", "Calling native redraw");
        super.surfaceRedrawNeeded(holder);
        HostJournal.write(this, "AFTER_SURFACE_REDRAW", "Native redraw returned");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        HostJournal.write(this, "BEFORE_SURFACE_DESTROYED", "Calling native destroy");
        super.surfaceDestroyed(holder);
        HostJournal.write(this, "AFTER_SURFACE_DESTROYED", "Native destroy returned");
    }

    private static final class BedrockLibraryClassLoader extends BaseDexClassLoader {
        private final BaseDexClassLoader delegate;
        private final String minecraftPath;

        BedrockLibraryClassLoader(BaseDexClassLoader delegate, String minecraftPath) {
            super("", null, null, delegate);
            this.delegate = delegate;
            this.minecraftPath = minecraftPath;
        }

        @Override
        public String findLibrary(String name) {
            if ("minecraftpe".equals(name) || "libminecraftpe.so".equals(name)) {
                return minecraftPath;
            }
            return delegate.findLibrary(name);
        }
    }
}
