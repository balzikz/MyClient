package com.balzikz.mathclient;

import android.app.Application;
import android.os.Build;

import java.io.File;

public final class MathApplication extends Application {
    private static volatile boolean hostReady;
    private static volatile String hostStatus = "NOT STARTED";

    @Override
    public void onCreate() {
        super.onCreate();
        String process = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? Application.getProcessName() : getPackageName();
        if (!process.endsWith(":game_host")) return;

        HostJournal.write(this, "APPLICATION_START", process);
        try {
            File runtime = HostJournal.runtime(this);
            File manifest = new File(runtime, "runtime-manifest.txt");
            File game = HostJournal.game(this);
            if (!runtime.isDirectory() || !manifest.isFile() || !game.isFile()) {
                throw new IllegalStateException("Bedrock runtime is missing");
            }

            String preload = LinkerBridge.prepareMinecraftHost(runtime.getAbsolutePath());
            hostStatus = preload;
            if (!preload.contains("Verdict: READY FOR SYSTEM LOAD")) {
                throw new IllegalStateException(preload);
            }

            HostJournal.write(this, "DEPENDENCIES_READY", preload);
            HostJournal.write(this, "BEFORE_SYSTEM_LOAD", game.getCanonicalPath());
            System.load(game.getCanonicalPath());
            hostReady = true;
            hostStatus = "SYSTEM LOAD PASS";
            HostJournal.write(this, "SYSTEM_LOAD_PASS", "JNI_OnLoad returned");
        } catch (Throwable error) {
            hostReady = false;
            hostStatus = error.getClass().getName() + ": " + error.getMessage();
            HostJournal.write(this, "APPLICATION_LOAD_FAIL", hostStatus);
        }
    }

    public static boolean isHostReady() {
        return hostReady;
    }

    public static String hostStatus() {
        return hostStatus;
    }
}
