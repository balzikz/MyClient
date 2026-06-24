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

    public static void eglLabCreate() {
        if (LOADED) nativeEglLabCreate();
    }

    public static void eglLabResize(int width, int height) {
        if (LOADED) nativeEglLabResize(width, height);
    }

    public static void eglLabRender() {
        if (LOADED) nativeEglLabRender();
    }

    public static void eglLabTouch(float x, float y, boolean pressed) {
        if (LOADED) nativeEglLabTouch(x, y, pressed);
    }

    public static String eglLabStatus() {
        return LOADED ? nativeEglLabStatus() : "Native core unavailable";
    }

    private static native void nativeInitialize();

    private static native String nativeGetCoreInfo();

    private static native String nativeGetLoadedModules();

    private static native void nativeEglLabCreate();

    private static native void nativeEglLabResize(int width, int height);

    private static native void nativeEglLabRender();

    private static native void nativeEglLabTouch(float x, float y, boolean pressed);

    private static native String nativeEglLabStatus();
}
