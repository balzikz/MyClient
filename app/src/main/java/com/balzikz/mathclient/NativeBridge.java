package com.balzikz.mathclient;

public final class NativeBridge {

    private static final String LIBRARY_NAME = "mathclient";
    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean loaded = false;
        String loadError = "";

        try {
            System.loadLibrary(LIBRARY_NAME);
            nativeInitialize();
            loaded = true;
        } catch (Throwable throwable) {
            loadError = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }

        LOADED = loaded;
        LOAD_ERROR = loadError;
    }

    private NativeBridge() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static String getStatusText() {
        if (!LOADED) {
            return "Native Core: FAILED\n" + LOAD_ERROR;
        }

        try {
            return nativeGetCoreInfo();
        } catch (Throwable throwable) {
            return "Native Core: JNI ERROR\n"
                    + throwable.getClass().getSimpleName()
                    + ": "
                    + throwable.getMessage();
        }
    }

    public static String getLoadedModules() {
        if (!LOADED) {
            return "ModuleScanner unavailable: native core failed to load.";
        }

        try {
            return nativeGetLoadedModules();
        } catch (Throwable throwable) {
            return "ModuleScanner JNI error: "
                    + throwable.getClass().getSimpleName()
                    + ": "
                    + throwable.getMessage();
        }
    }

    private static native void nativeInitialize();

    private static native String nativeGetCoreInfo();

    private static native String nativeGetLoadedModules();
}
