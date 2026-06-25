package com.balzikz.mathclient;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.io.File;

import dalvik.system.BaseDexClassLoader;

public final class GameHostActivity extends com.mojang.minecraftpe.MainActivity {
    private AssetManager targetAssets;
    private boolean surfaceSeen;
    private volatile ClassLoader redirectedClassLoader;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            targetAssets = base.createPackageContext("com.mojang.minecraftpe", 0).getAssets();
        } catch (Throwable ignored) {
            targetAssets = null;
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
        findSurfaceLater(0);
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
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        HostJournal.write(this, "WINDOW_FOCUS", Boolean.toString(focused));
    }

    private void findSurfaceLater(int attempt) {
        getWindow().getDecorView().postDelayed(() -> {
            if (surfaceSeen) return;
            SurfaceView surface = findSurface(getWindow().getDecorView());
            if (surface == null) {
                if (attempt < 24) findSurfaceLater(attempt + 1);
                else HostJournal.write(this, "SURFACE_NOT_FOUND", "25 attempts");
                return;
            }
            surfaceSeen = true;
            HostJournal.write(this, "SURFACE_VIEW_FOUND",
                    surface.getWidth() + "x" + surface.getHeight());
            surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                public void surfaceCreated(SurfaceHolder holder) {
                    HostJournal.write(GameHostActivity.this, "SURFACE_CREATED",
                            Boolean.toString(holder.getSurface().isValid()));
                }
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    HostJournal.write(GameHostActivity.this, "SURFACE_CHANGED",
                            width + "x" + height + " format=" + format);
                }
                public void surfaceDestroyed(SurfaceHolder holder) {
                    HostJournal.write(GameHostActivity.this, "SURFACE_DESTROYED", "released");
                }
            });
            if (surface.getHolder().getSurface().isValid()) {
                HostJournal.write(this, "SURFACE_ALREADY_VALID",
                        surface.getWidth() + "x" + surface.getHeight());
            }
        }, attempt == 0 ? 0 : 250);
    }

    private SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                SurfaceView found = findSurface(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
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
