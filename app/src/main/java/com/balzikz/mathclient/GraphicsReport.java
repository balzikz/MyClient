package com.balzikz.mathclient;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Arrays;

public final class GraphicsReport {

    private GraphicsReport() {
    }

    public static String create(Context context) {
        StringBuilder text = new StringBuilder();
        PackageManager manager = context.getPackageManager();

        text.append("Android: ")
                .append(Build.VERSION.RELEASE)
                .append(" (API ")
                .append(Build.VERSION.SDK_INT)
                .append(")\n");
        text.append("Supported ABIs: ")
                .append(Arrays.toString(Build.SUPPORTED_ABIS))
                .append('\n');
        text.append("MATH native ABI: arm64-v8a\n");

        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ConfigurationInfo configuration = activityManager == null
                ? null
                : activityManager.getDeviceConfigurationInfo();

        text.append("Device OpenGL ES: ")
                .append(configuration == null ? "unknown" : configuration.getGlEsVersion())
                .append('\n');
        text.append("Vulkan level feature: ")
                .append(manager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL))
                .append('\n');
        text.append("Vulkan version feature: ")
                .append(manager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION))
                .append('\n');
        text.append("Bedrock active graphics API: unknown before in-process loading");

        return text.toString();
    }
}
