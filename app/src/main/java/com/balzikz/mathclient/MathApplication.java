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

        installCrashJournal();
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
            hostStatus = describe(error);
            HostJournal.write(this, "APPLICATION_LOAD_FAIL", hostStatus);
        }
    }

    private void installCrashJournal() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            HostJournal.write(this, "UNCAUGHT_JAVA_EXCEPTION",
                    thread.getName() + " | " + describe(error));
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getName() + ": " + (message == null ? "NO MESSAGE" : message);
    }

    public static boolean isHostReady() {
        return hostReady;
    }

    public static String hostStatus() {
        return hostStatus;
    }
}
