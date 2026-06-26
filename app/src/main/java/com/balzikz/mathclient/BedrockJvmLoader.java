package com.balzikz.mathclient;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;

/**
 * Loads the user-provided Bedrock library through the Android Runtime.
 *
 * System.load is intentionally the only operation in this class that loads
 * libminecraftpe.so. This gives the VM ownership of JNI_OnLoad and associates
 * the library with the application's ClassLoader before any native symbol
 * attachment is attempted by the MATH shim.
 */
public final class BedrockJvmLoader {
    private static final Object LOCK = new Object();

    private static boolean attempted;
    private static boolean loaded;
    private static String loadedPath = "";
    private static String status = "NOT_ATTEMPTED";

    private BedrockJvmLoader() {
    }

    public static String load(Context context) {
        synchronized (LOCK) {
            if (loaded) return status;
            if (attempted) {
                throw new IllegalStateException("Bedrock JVM load already failed: " + status);
            }
            attempted = true;

            final File library;
            try {
                library = HostJournal.game(context).getCanonicalFile();
            } catch (IOException error) {
                status = "FAIL canonicalPath=" + describe(error);
                throw new IllegalStateException(status, error);
            }

            if (!library.isFile()) {
                status = "FAIL reason=NOT_A_FILE path=" + library.getAbsolutePath();
                throw new IllegalStateException(status);
            }
            if (!library.canRead()) {
                status = "FAIL reason=NOT_READABLE path=" + library.getAbsolutePath();
                throw new IllegalStateException(status);
            }

            long startedAt = SystemClock.elapsedRealtime();
            try {
                System.load(library.getAbsolutePath());
                long elapsedMs = SystemClock.elapsedRealtime() - startedAt;

                loaded = true;
                loadedPath = library.getAbsolutePath();
                status = "OK loader=System.load jvmOwned=YES"
                        + " elapsedMs=" + elapsedMs
                        + " bytes=" + library.length()
                        + " path=" + loadedPath;
                return status;
            } catch (Throwable error) {
                long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
                status = "FAIL loader=System.load elapsedMs=" + elapsedMs
                        + " error=" + describe(error);

                if (error instanceof Error) throw (Error) error;
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                throw new IllegalStateException(status, error);
            }
        }
    }

    public static boolean isLoaded() {
        synchronized (LOCK) {
            return loaded;
        }
    }

    public static String status() {
        synchronized (LOCK) {
            return status;
        }
    }

    public static String loadedPath() {
        synchronized (LOCK) {
            return loadedPath;
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getName() + ": "
                + (message == null ? "NO MESSAGE" : message.replace('\n', ' ').replace('\r', ' '));
    }
}
