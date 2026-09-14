package com.balzikz.mathclient.foundation;

import android.view.Surface;

/** All target-native code is deliberately absent. Only our own library is loaded by this owner. */
final class NativeProbe {
    private static boolean loaded;
    static synchronized void load(SessionLog log) {
        if (loaded) return;
        log.event("PROBE_SYSTEM_LOAD_BEGIN", "owner=" + NativeProbe.class.getClassLoader()
                + " contextLoader=" + Thread.currentThread().getContextClassLoader());
        System.loadLibrary("mathprobe");
        loaded = true;
        log.event("PROBE_JNI_READY", describe());
    }
    private static native String describe();
    static native String draw(Surface surface, int width, int height);
}
