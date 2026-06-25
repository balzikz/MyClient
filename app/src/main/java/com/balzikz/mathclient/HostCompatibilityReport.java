package com.balzikz.mathclient;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class HostCompatibilityReport {

    private static final String TARGET_PACKAGE = "com.mojang.minecraftpe";
    private static final String ABI_PREFIX = "lib/arm64-v8a/";

    private static final String[] HOST_LIBRARIES = {
            "libc++_shared.so",
            "libmaesdk.so",
            "libHttpClient.Android.so",
            "libPlayFabMultiplayer.so",
            "libfmod.so",
            "libmcfix.so",
            "libminecraftpe.so"
    };

    private HostCompatibilityReport() {
    }

    public static String create(Context context) {
        StringBuilder report = new StringBuilder();
        report.append("MATH BEDROCK HOST LAB\n");
        report.append("Mode: READ-ONLY DISCOVERY\n");
        report.append("Native loading: NOT ATTEMPTED\n\n");

        PackageManager manager = context.getPackageManager();

        try {
            PackageInfo packageInfo = getPackageInfo(manager);
            ApplicationInfo targetInfo = packageInfo.applicationInfo;
            if (targetInfo == null) {
                return report.append("Host compatibility: FAILED\n")
                        .append("Minecraft ApplicationInfo is missing.")
                        .toString();
            }

            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;

            report.append("=== TARGET PACKAGE ===\n");
            report.append("Package: ").append(TARGET_PACKAGE).append('\n');
            report.append("Version: ").append(packageInfo.versionName).append('\n');
            report.append("Version code: ").append(versionCode).append('\n');
            report.append("Process: ").append(targetInfo.processName).append('\n');
            report.append("ABI target: arm64-v8a\n");
            report.append("Base APK: ").append(targetInfo.sourceDir).append('\n');
            report.append("Native directory: ").append(targetInfo.nativeLibraryDir).append('\n');

            Context packageContext = context.createPackageContext(
                    TARGET_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY);

            report.append("\n=== PACKAGE CONTEXT ===\n");
            report.append("PackageContext: ACQUIRED\n");
            report.append("Context package: ").append(packageContext.getPackageName()).append('\n');
            report.append("Official AssetManager: ")
                    .append(packageContext.getAssets() != null ? "ACQUIRED" : "FAILED")
                    .append('\n');

            appendAssetReport(report, packageContext.getAssets());
            appendStorageIsolation(report, context, targetInfo);

            Map<String, Long> libraries = scanNativeLibraries(targetInfo);
            appendLibraryReport(report, libraries, targetInfo.nativeLibraryDir);

            boolean contextReady = TARGET_PACKAGE.equals(packageContext.getPackageName())
                    && packageContext.getAssets() != null;
            boolean requiredReady = true;
            for (String name : HOST_LIBRARIES) {
                if (!libraries.containsKey(name)
                        && !new File(targetInfo.nativeLibraryDir, name).isFile()) {
                    requiredReady = false;
                    break;
                }
            }

            report.append("\n=== HOST VERDICT ===\n");
            report.append("Package discovery: READY\n");
            report.append("PackageContext bridge: ")
                    .append(contextReady ? "READY" : "FAILED")
                    .append('\n');
            report.append("Native dependency set: ")
                    .append(requiredReady ? "READY" : "INCOMPLETE")
                    .append('\n');
            report.append("Storage isolation: ")
                    .append(context.getApplicationInfo().dataDir.equals(targetInfo.dataDir)
                            ? "FAILED" : "READY")
                    .append('\n');
            report.append("Host compatibility: ")
                    .append(contextReady && requiredReady ? "READY FOR STAGE 3.2" : "BLOCKED")
                    .append('\n');
            report.append("Safety: no Minecraft library was loaded or executed.");
        } catch (PackageManager.NameNotFoundException error) {
            report.append("Host compatibility: BLOCKED\n")
                    .append("Minecraft Bedrock is not installed.");
        } catch (Throwable error) {
            report.append("Host compatibility: FAILED\n")
                    .append(error.getClass().getSimpleName())
                    .append(": ")
                    .append(error.getMessage());
        }

        return report.toString();
    }

    private static PackageInfo getPackageInfo(PackageManager manager)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return manager.getPackageInfo(
                    TARGET_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0));
        }
        return manager.getPackageInfo(TARGET_PACKAGE, 0);
    }

    private static void appendAssetReport(StringBuilder report, AssetManager assets) {
        report.append("\n=== OFFICIAL ASSETS ===\n");

        try {
            String[] rootEntries = assets.list("");
            if (rootEntries == null) rootEntries = new String[0];
            Arrays.sort(rootEntries);

            report.append("Asset root entries: ").append(rootEntries.length).append('\n');
            int previewCount = Math.min(rootEntries.length, 16);
            for (int index = 0; index < previewCount; index++) {
                report.append("  - ").append(rootEntries[index]).append('\n');
            }
            if (rootEntries.length > previewCount) {
                report.append("  ... ")
                        .append(rootEntries.length - previewCount)
                        .append(" more\n");
            }
        } catch (Exception error) {
            report.append("Asset listing: FAILED (" )
                    .append(error.getClass().getSimpleName())
                    .append(")\n");
        }

        String[] vanillaCandidates = {
                "resource_packs/vanilla/manifest.json",
                "assets/resource_packs/vanilla/manifest.json"
        };

        String openedPath = null;
        for (String candidate : vanillaCandidates) {
            try (InputStream ignored = assets.open(candidate)) {
                openedPath = candidate;
                break;
            } catch (Exception ignored) {
                // Try the next known layout.
            }
        }

        report.append("Vanilla manifest: ")
                .append(openedPath == null ? "NOT FOUND" : "OPENABLE")
                .append('\n');
        if (openedPath != null) {
            report.append("Vanilla asset path: ").append(openedPath).append('\n');
        }
    }

    private static void appendStorageIsolation(
            StringBuilder report,
            Context mathContext,
            ApplicationInfo targetInfo) {
        report.append("\n=== STORAGE ISOLATION ===\n");
        report.append("MATH dataDir: ")
                .append(mathContext.getApplicationInfo().dataDir)
                .append('\n');
        report.append("Minecraft dataDir: ")
                .append(targetInfo.dataDir)
                .append('\n');
        report.append("Same sandbox: ")
                .append(mathContext.getApplicationInfo().dataDir.equals(targetInfo.dataDir))
                .append('\n');

        File mathExternal = mathContext.getExternalFilesDir(null);
        report.append("MATH external files: ")
                .append(mathExternal == null ? "unavailable" : mathExternal.getAbsolutePath())
                .append('\n');
    }

    private static Map<String, Long> scanNativeLibraries(ApplicationInfo info) throws Exception {
        Map<String, Long> result = new LinkedHashMap<>();
        List<String> apkPaths = new ArrayList<>();
        apkPaths.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            Collections.addAll(apkPaths, info.splitSourceDirs);
        }

        for (String path : apkPaths) {
            if (path == null) continue;
            try (ZipFile zip = new ZipFile(path)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory()
                            && name.startsWith(ABI_PREFIX)
                            && name.endsWith(".so")) {
                        result.put(name.substring(ABI_PREFIX.length()), entry.getSize());
                    }
                }
            }
        }

        List<String> names = new ArrayList<>(result.keySet());
        Collections.sort(names);
        Map<String, Long> sorted = new LinkedHashMap<>();
        for (String name : names) {
            sorted.put(name, result.get(name));
        }
        return sorted;
    }

    private static void appendLibraryReport(
            StringBuilder report,
            Map<String, Long> libraries,
            String nativeLibraryDir) {
        report.append("\n=== NATIVE LIBRARY RESOLVER ===\n");
        report.append("arm64 APK libraries: ").append(libraries.size()).append('\n');

        for (String required : HOST_LIBRARIES) {
            Long size = libraries.get(required);
            File extracted = new File(nativeLibraryDir, required);
            boolean present = size != null || extracted.isFile();
            report.append(present ? "[OK] " : "[MISSING] ")
                    .append(required);
            if (size != null) {
                report.append(" | APK ").append(formatBytes(size));
            } else if (extracted.isFile()) {
                report.append(" | nativeLibraryDir ")
                        .append(formatBytes(extracted.length()));
            }
            report.append('\n');
        }

        report.append("\nComplete arm64 inventory:\n");
        int index = 1;
        for (Map.Entry<String, Long> entry : libraries.entrySet()) {
            report.append(index++)
                    .append(". ")
                    .append(entry.getKey())
                    .append(" | ")
                    .append(formatBytes(entry.getValue()))
                    .append('\n');
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1024.0);
    }
}
