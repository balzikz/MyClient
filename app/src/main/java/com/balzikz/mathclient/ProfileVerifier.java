package com.balzikz.mathclient;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

public final class ProfileVerifier {

    private static final String TARGET_PACKAGE = "com.mojang.minecraftpe";

    private ProfileVerifier() {
    }

    public static String verify(Context context, String elfReport) {
        try {
            PackageInfo info;
            PackageManager manager = context.getPackageManager();
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
            String buildId = valueAfter(elfReport, "GNU Build ID: ");
            String sha256 = valueAfter(elfReport, "SHA-256: ");

            boolean exactMatch = BedrockProfile.matches(
                    info.versionName,
                    versionCode,
                    buildId,
                    sha256);
            return BedrockProfile.summary(exactMatch);
        } catch (Exception error) {
            return BedrockProfile.summary(false)
                    + "\nVerifier error: "
                    + error.getClass().getSimpleName()
                    + ": "
                    + error.getMessage();
        }
    }

    private static String valueAfter(String text, String prefix) {
        int start = text.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        start += prefix.length();
        int end = text.indexOf('\n', start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }
}
