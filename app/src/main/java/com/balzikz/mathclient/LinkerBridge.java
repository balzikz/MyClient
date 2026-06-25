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
        return LOADED ? "C linker bridge: READY" : "C linker bridge: FAILED\n" + LOAD_ERROR;
    }

    public static String runLinkerLoadTest(String runtimeDirectory) {
        return call("MATH BEDROCK LINKER LOAD LAB", runtimeDirectory, 1);
    }

    public static String runMinecraftLoadTest(String runtimeDirectory) {
        return call("MATH BEDROCK MINECRAFT DLOPEN LAB", runtimeDirectory, 2);
    }

    public static String runMinecraftSymbolProbe(String runtimeDirectory) {
        return call("MATH BEDROCK ENTRYPOINT PROBE", runtimeDirectory, 3);
    }

    public static String runMinecraftJniRegistration(String runtimeDirectory) {
        return call("MATH BEDROCK JNI REGISTRATION LAB", runtimeDirectory, 4);
    }

    private static String call(String title, String runtimeDirectory, int operation) {
        if (!LOADED) {
            return title + "\nVerdict: BLOCKED\nC linker bridge failed to load: " + LOAD_ERROR;
        }
        try {
            switch (operation) {
                case 1:
                    return nativeRunLinkerLoadTest(runtimeDirectory);
                case 2:
                    return nativeRunMinecraftLoadTest(runtimeDirectory);
                case 3:
                    return nativeRunMinecraftSymbolProbe(runtimeDirectory);
                case 4:
                    return nativeRunMinecraftJniRegistration(runtimeDirectory);
                default:
                    return title + "\nVerdict: INVALID OPERATION";
            }
        } catch (Throwable throwable) {
            return title + "\nVerdict: JNI ERROR\n"
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
    }

    private static native String nativeRunLinkerLoadTest(String runtimeDirectory);
    private static native String nativeRunMinecraftLoadTest(String runtimeDirectory);
    private static native String nativeRunMinecraftSymbolProbe(String runtimeDirectory);
    private static native String nativeRunMinecraftJniRegistration(String runtimeDirectory);
}
