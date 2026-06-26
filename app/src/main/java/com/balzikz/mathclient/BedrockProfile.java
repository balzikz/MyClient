package com.balzikz.mathclient;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;

public final class BedrockProfile {
    public static final String TARGET_PACKAGE = "com.mojang.minecraftpe";
    public static final String SUPPORTED_VERSION_PREFIX = "1.26.30";
    public static final String ABI = "arm64-v8a";
    public static final String GRAPHICS_BACKEND = "OPENGL_ES";

    private BedrockProfile() {
    }

    public static boolean isSupportedVersion(String versionName) {
        return versionName != null && versionName.startsWith(SUPPORTED_VERSION_PREFIX);
    }

    public static Installed installed(Context context) throws PackageManager.NameNotFoundException {
        PackageManager manager = context.getPackageManager();
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            info = manager.getPackageInfo(
                    TARGET_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0));
        } else {
            info = manager.getPackageInfo(TARGET_PACKAGE, 0);
        }
        long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
        String versionName = info.versionName == null ? "unknown" : info.versionName;
        return new Installed(versionName, versionCode, isSupportedVersion(versionName));
    }

    public static String runtimeId(Context context) {
        try {
            Installed value = installed(context);
            return runtimeId(value.versionName, value.versionCode);
        } catch (Throwable ignored) {
            return "bedrock-missing-arm64-gles";
        }
    }

    public static String runtimeId(String versionName, long versionCode) {
        String safeVersion = sanitize(versionName == null ? "unknown" : versionName);
        return String.format(Locale.ROOT,
                "bedrock-%s-%d-arm64-gles",
                safeVersion,
                versionCode);
    }

    public static String summary(Installed value) {
        return "Installed version: " + value.versionName + "\n"
                + "Installed version code: " + value.versionCode + "\n"
                + "Supported family: " + SUPPORTED_VERSION_PREFIX + ".x\n"
                + "ABI: " + ABI + "\n"
                + "Graphics backend: " + GRAPHICS_BACKEND + "\n"
                + "Profile action: " + (value.supported ? "ALLOW" : "BLOCK UNKNOWN BUILD");
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public static final class Installed {
        public final String versionName;
        public final long versionCode;
        public final boolean supported;

        Installed(String versionName, long versionCode, boolean supported) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.supported = supported;
        }
    }
}
