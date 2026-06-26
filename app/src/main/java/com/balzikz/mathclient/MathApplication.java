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
        HostJournal.write(this, "APPLICATION_START", process + " stage=5.0.1");
        LinkerBridge.setSignalMarker("APPLICATION_START");
        String signalInstall = LinkerBridge.installSignalTrace(
                HostJournal.signalTrace(this).getAbsolutePath());
        HostJournal.write(this, "SIGNAL_TRACE_INSTALL", signalInstall);

        try {
            File runtime = HostJournal.runtime(this);
            File manifest = new File(runtime, "runtime-manifest.txt");
            File game = HostJournal.game(this);
            if (!runtime.isDirectory() || !manifest.isFile() || !game.isFile()) {
                throw new IllegalStateException("Bedrock runtime is missing");
            }

            LinkerBridge.setSignalMarker("PREPARE_STAGE_5_0_1_RUNTIME");
            String preload = LinkerBridge.prepareMinecraftHost(runtime.getAbsolutePath());
            if (!preload.contains("Verdict: READY FOR SYSTEM LOAD")) {
                throw new IllegalStateException(preload);
            }
            HostJournal.write(this, "DEPENDENCIES_READY", preload);

            LinkerBridge.setSignalMarker("CONFIGURE_MATH_SHIM");
            String shimStatus = MathShimBridge.configure(this);
            HostJournal.write(this, "MATH_SHIM_CONFIGURED", shimStatus);

            LinkerBridge.setSignalMarker("BEDROCK_SYSTEM_LOAD_START");
            HostJournal.write(this, "BEDROCK_SYSTEM_LOAD_START",
                    "loader=System.load"
                            + " bytes=" + game.length()
                            + " path=" + game.getAbsolutePath()
                            + " nativeBindBeforeLoad=NO"
                            + " manualJniOnLoad=NO");

            String loadStatus = BedrockJvmLoader.load(this);
            HostJournal.write(this, "BEDROCK_SYSTEM_LOAD_RETURN", loadStatus);
            if (!BedrockJvmLoader.isLoaded()) {
                throw new IllegalStateException(loadStatus);
            }

            String signalRefresh = LinkerBridge.refreshSignalTrace();
            HostJournal.write(this, "SIGNAL_TRACE_REFRESH_AFTER_SYSTEM_LOAD", signalRefresh);

            LinkerBridge.setSignalMarker("BEDROCK_CONNECT_START");
            String connectionStatus = MathShimBridge.connectLoadedRuntime(
                    this,
                    BedrockJvmLoader.loadedPath());
            HostJournal.write(this, "BEDROCK_CONNECT_RESULT", connectionStatus);
            if (!MathShimBridge.nativeIsRuntimeConnected()) {
                throw new IllegalStateException(connectionStatus);
            }

            hostReady = true;
            hostStatus = "jvmLoad=" + loadStatus
                    + " | connection=" + connectionStatus
                    + " | shim=" + shimStatus;
            LinkerBridge.setSignalMarker("BEDROCK_ENTRYPOINTS_READY");
            HostJournal.write(this, "HOST_RUNTIME_READY", hostStatus);
        } catch (Throwable error) {
            hostReady = false;
            hostStatus = describe(error)
                    + " | jvmLoad=" + BedrockJvmLoader.status()
                    + " | connection=" + safeConnectionStatus();
            LinkerBridge.setSignalMarker("APPLICATION_LOAD_FAIL");
            HostJournal.write(this, "APPLICATION_LOAD_FAIL", hostStatus);
        }
    }

    private String safeConnectionStatus() {
        try {
            return MathShimBridge.nativeHandoffStatus();
        } catch (Throwable error) {
            return describe(error);
        }
    }

    private void installCrashJournal() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            LinkerBridge.setSignalMarker("UNCAUGHT_JAVA_EXCEPTION");
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
