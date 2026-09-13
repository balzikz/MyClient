package com.balzikz.mathclient.foundation;

import android.app.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FoundationApplication extends Application {
    static long startedElapsed;
    final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "foundation-io"));
    final AtomicBoolean busy = new AtomicBoolean(false);

    @Override public void onCreate() {
        super.onCreate();
        startedElapsed = SystemClock.elapsedRealtime();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                SessionLog log = SessionLog.active();
                if (log != null) {
                    log.exception("JAVA_UNCAUGHT", error);
                    StringWriter text = new StringWriter();
                    error.printStackTrace(new PrintWriter(text));
                    log.writeText("java-crash-" + android.os.Process.myPid() + "-" + System.nanoTime() + ".txt", text.toString());
                }
            } catch (Throwable captureError) { Log.e("MathFoundation", "Crash capture failed", captureError); }
            finally {
                // Preserve Android's normal crash reporting; no infinite restart/recovery loop.
                if (previous != null) previous.uncaughtException(thread, error);
                else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
            }
        });
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            private void record(Activity a, String state) {
                SessionLog log = SessionLog.active();
                if (log != null) log.event("LIFECYCLE", a.getClass().getSimpleName() + "." + state);
            }
            public void onActivityCreated(Activity a, Bundle state) { record(a, "created"); }
            public void onActivityStarted(Activity a) { record(a, "started"); }
            public void onActivityResumed(Activity a) { record(a, "resumed"); }
            public void onActivityPaused(Activity a) { record(a, "paused"); }
            public void onActivityStopped(Activity a) { record(a, "stopped"); }
            public void onActivitySaveInstanceState(Activity a, Bundle state) { record(a, "saveState"); }
            public void onActivityDestroyed(Activity a) { record(a, "destroyed"); }
        });
    }
}
