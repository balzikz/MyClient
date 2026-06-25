package com.balzikz.mathclient;

public final class LinkerBridge {

    private static final String LIBRARY_NAME = "mathlinker";
    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean loaded = false;
        String error = "";
        try {
            System.loadLibrary(LIBRARY_NAME);
            loaded = true;
        } catch (Throwable throwable) {
            error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        LOADED = loaded;
        LOAD_ERROR = error;
    }

    private LinkerBridge() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static String loadStatus() {
        return LOADED
                ? "C linker bridge: READY"
                : "C linker bridge: FAILED\n" + LOAD_ERROR;
    }

    public static String runLinkerLoadTest(String runtimeDirectory) {
        if (!LOADED) {
            return bridgeFailure("MATH BEDROCK LINKER LOAD LAB");
        }

        try {
            return nativeRunLinkerLoadTest(runtimeDirectory);
        } catch (Throwable throwable) {
            return jniFailure("MATH BEDROCK LINKER LOAD LAB", throwable);
        }
    }

    public static String runMinecraftLoadTest(String runtimeDirectory) {
        if (!LOADED) {
            return bridgeFailure("MATH BEDROCK MINECRAFT DLOPEN LAB");
        }

        try {
            return nativeRunMinecraftLoadTest(runtimeDirectory);
        } catch (Throwable throwable) {
            return jniFailure("MATH BEDROCK MINECRAFT DLOPEN LAB", throwable);
        }
    }

    private static String bridgeFailure(String title) {
        return title + "\n"
                + "Verdict: BLOCKED\n"
                + "C linker bridge failed to load: "
                + LOAD_ERROR;
    }

    private static String jniFailure(String title, Throwable throwable) {
        return title + "\n"
                + "Verdict: JNI ERROR\n"
                + throwable.getClass().getSimpleName()
                + ": "
                + throwable.getMessage();
    }

    private static native String nativeRunLinkerLoadTest(String runtimeDirectory);

    private static native String nativeRunMinecraftLoadTest(String runtimeDirectory);
}
