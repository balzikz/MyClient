package com.balzikz.mathclient;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApkNativeInventory {

    private static final String TARGET_PACKAGE = "com.mojang.minecraftpe";
    private static final String ABI_PREFIX = "lib/arm64-v8a/";

    private ApkNativeInventory() {
    }

    public static String create(Context context) {
        StringBuilder report = new StringBuilder();

        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(TARGET_PACKAGE, 0);
            File apkFile = new File(info.sourceDir);

            report.append("APK size: ")
                    .append(formatBytes(apkFile.length()))
                    .append('\n');

            List<String> rows = new ArrayList<>();
            List<String> graphicsCandidates = new ArrayList<>();

            try (ZipFile zip = new ZipFile(apkFile)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (!entry.isDirectory()
                            && name.startsWith(ABI_PREFIX)
                            && name.endsWith(".so")) {
                        String fileName = name.substring(ABI_PREFIX.length());
                        String row = fileName
                                + " | " + formatBytes(entry.getSize())
                                + " | crc=" + Long.toHexString(entry.getCrc());
                        rows.add(row);

                        String lower = fileName.toLowerCase(Locale.ROOT);
                        if (lower.contains("vulkan")
                                || lower.contains("gles")
                                || lower.contains("egl")
                                || lower.contains("render")
                                || lower.contains("minecraft")) {
                            graphicsCandidates.add(fileName);
                        }
                    }
                }
            }

            Collections.sort(rows);
            Collections.sort(graphicsCandidates);

            report.append("arm64 native libraries: ")
                    .append(rows.size())
                    .append('\n');
            report.append("Inventory SHA-256: ")
                    .append(inventoryHash(rows))
                    .append('\n');

            report.append("Graphics / engine candidates:\n");
            if (graphicsCandidates.isEmpty()) {
                report.append("  none by filename\n");
            } else {
                for (String candidate : graphicsCandidates) {
                    report.append("  - ").append(candidate).append('\n');
                }
            }

            report.append("Native library inventory:\n");
            for (int index = 0; index < rows.size(); index++) {
                report.append(index + 1)
                        .append(". ")
                        .append(rows.get(index))
                        .append('\n');
            }
        } catch (PackageManager.NameNotFoundException error) {
            report.append("Minecraft Bedrock is not installed.");
        } catch (Exception error) {
            report.append("APK inventory failed: ")
                    .append(error.getClass().getSimpleName())
                    .append(": ")
                    .append(error.getMessage());
        }

        return report.toString();
    }

    private static String inventoryHash(List<String> rows) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String row : rows) {
            digest.update(row.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }

        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }
        double mib = kib / 1024.0;
        return String.format(Locale.ROOT, "%.1f MiB", mib);
    }
}
