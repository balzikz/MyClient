package com.balzikz.mathclient;

import android.content.Context;

public final class MathShimBridge {
    static {
        System.loadLibrary("mathshim");
    }

    private MathShimBridge() {
    }

    public static String configure(Context context) {
        return nativeConfigure(
                HostJournal.journal(context).getAbsolutePath(),
                HostJournal.game(context).getAbsolutePath());
    }

    public static native String nativeConfigure(String journalPath, String minecraftPath);

    public static native String nativeStatus();
}
