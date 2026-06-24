package com.balzikz.mathclient;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

public final class PackageReport {

    private static final String TARGET_PACKAGE = "com.mojang.minecraftpe";

    private PackageReport() {
    }

    public static String create(Context context) {
        StringBuilder text = new StringBuilder();
        PackageManager manager = context.getPackageManager();

        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageInfo = manager.getPackageInfo(
                        TARGET_PACKAGE,
                        PackageManager.PackageInfoFlags.of(0));
            } else {
                packageInfo = manager.getPackageInfo(TARGET_PACKAGE, 0);
            }

            ApplicationInfo appInfo = packageInfo.applicationInfo;
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;

            text.append("Package: ").append(TARGET_PACKAGE).append('\n');
            text.append("Version: ").append(packageInfo.versionName).append('\n');
            text.append("Version code: ").append(versionCode).append('\n');
            text.append("Process: ").append(appInfo.processName).append('\n');
            text.append("Target SDK: ").append(appInfo.targetSdkVersion).append('\n');
            text.append("Min SDK: ").append(appInfo.minSdkVersion).append('\n');
            text.append("Base APK: ").append(appInfo.sourceDir).append('\n');
            text.append("Native libraries: ").append(appInfo.nativeLibraryDir).append('\n');

            String[] splitPaths = appInfo.splitSourceDirs;
            String[] splitNames = packageInfo.splitNames;
            int count = splitPaths == null ? 0 : splitPaths.length;
            text.append("Split APK count: ").append(count).append('\n');

            for (int index = 0; index < count; index++) {
                String name = splitNames != null && index < splitNames.length
                        ? splitNames[index]
                        : "split-" + index;
                text.append(index + 1).append(". ")
                        .append(name).append(" -> ")
                        .append(splitPaths[index]).append('\n');
            }

            Intent launchIntent = manager.getLaunchIntentForPackage(TARGET_PACKAGE);
            ComponentName component = launchIntent == null ? null : launchIntent.getComponent();
            text.append("Launch component: ")
                    .append(component == null ? "not resolved" : component.flattenToShortString());
        } catch (PackageManager.NameNotFoundException error) {
            text.append("Minecraft Bedrock is not installed.");
        }

        return text.toString();
    }
}
