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
            return "MATH BEDROCK LINKER LOAD LAB\n"
                    + "Linker verdict: BLOCKED\n"
                    + "C linker bridge failed to load: "
                    + LOAD_ERROR;
        }

        try {
            return nativeRunLinkerLoadTest(runtimeDirectory);
        } catch (Throwable throwable) {
            return "MATH BEDROCK LINKER LOAD LAB\n"
                    + "Linker verdict: JNI ERROR\n"
                    + throwable.getClass().getSimpleName()
                    + ": "
                    + throwable.getMessage();
        }
    }

    private static native String nativeRunLinkerLoadTest(String runtimeDirectory);
}
