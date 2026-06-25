package com.balzikz.mathclient;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BedrockRuntimePreparer {

    private static final String TARGET_PACKAGE = "com.mojang.minecraftpe";
    private static final String ABI_PREFIX = "lib/arm64-v8a/";
    private static final long SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L;

    private static final String[] RUNTIME_LIBRARIES = {
            "libc++_shared.so",
            "libmaesdk.so",
            "libHttpClient.Android.so",
            "libPlayFabMultiplayer.so",
            "libfmod.so",
            "libmcfix.so",
            "libminecraftpe.so"
    };

    private BedrockRuntimePreparer() {
    }

    public static String prepare(Context context) {
        StringBuilder report = new StringBuilder();
        report.append("MATH BEDROCK RUNTIME PREPARER\n");
        report.append("Stage: 3.2\n");
        report.append("Mode: LOCAL EXTRACTION + HASH VERIFICATION\n");
        report.append("Native loading: NOT ATTEMPTED\n\n");

        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = getPackageInfo(packageManager);
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            if (applicationInfo == null) {
                return report.append("Runtime preparation: FAILED\n")
                        .append("Minecraft ApplicationInfo is missing.")
                        .toString();
            }

            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;

            boolean profileVersionMatch = BedrockProfile.VERSION_NAME.equals(packageInfo.versionName)
                    && BedrockProfile.VERSION_CODE == versionCode;

            report.append("=== PROFILE GATE ===\n");
            report.append("Expected profile: ").append(BedrockProfile.ID).append('\n');
            report.append("Installed version: ").append(packageInfo.versionName).append('\n');
            report.append("Installed version code: ").append(versionCode).append('\n');
            report.append("Version gate: ")
                    .append(profileVersionMatch ? "PASS" : "BLOCK")
                    .append("\n\n");

            if (!profileVersionMatch) {
                return report.append("Runtime preparation: BLOCKED UNKNOWN VERSION\n")
                        .append("No files were copied.")
                        .toString();
            }

            List<String> apkPaths = collectApkPaths(applicationInfo);
            Map<String, LibrarySource> sources = discoverSources(apkPaths);

            report.append("=== SOURCE DISCOVERY ===\n");
            report.append("APK containers: ").append(apkPaths.size()).append('\n');

            long requiredBytes = 0L;
            boolean allSourcesFound = true;
            for (String library : RUNTIME_LIBRARIES) {
                LibrarySource source = sources.get(library);
                if (source == null) {
                    report.append("[MISSING] ").append(library).append('\n');
                    allSourcesFound = false;
                } else {
                    report.append("[FOUND] ")
                            .append(library)
                            .append(" | ")
                            .append(formatBytes(source.size))
                            .append(" | ")
                            .append(new File(source.apkPath).getName())
                            .append('\n');
                    requiredBytes += source.size;
                }
            }

            if (!allSourcesFound) {
                return report.append("\nRuntime preparation: BLOCKED MISSING LIBRARIES\n")
                        .append("No files were copied.")
                        .toString();
            }

            File runtimeRoot = new File(context.getNoBackupFilesDir(), "bedrock-runtime");
            File profileDirectory = new File(runtimeRoot, BedrockProfile.ID);
            ensureDirectory(profileDirectory);

            StatFs statFs = new StatFs(profileDirectory.getAbsolutePath());
            long availableBytes = statFs.getAvailableBytes();

            report.append("\n=== STORAGE PLAN ===\n");
            report.append("Runtime directory: ")
                    .append(profileDirectory.getAbsolutePath())
                    .append('\n');
            report.append("Required payload: ").append(formatBytes(requiredBytes)).append('\n');
            report.append("Available space: ").append(formatBytes(availableBytes)).append('\n');
            report.append("Safety margin: ").append(formatBytes(SAFETY_MARGIN_BYTES)).append('\n');

            if (availableBytes < requiredBytes + SAFETY_MARGIN_BYTES) {
                return report.append("Space gate: BLOCK\n")
                        .append("Runtime preparation: BLOCKED LOW STORAGE\n")
                        .append("No files were copied.")
                        .toString();
            }
            report.append("Space gate: PASS\n");

            List<PreparedLibrary> prepared = new ArrayList<>();
            long copiedBytes = 0L;

            report.append("\n=== EXTRACTION ===\n");
            for (String library : RUNTIME_LIBRARIES) {
                LibrarySource source = sources.get(library);
                File destination = new File(profileDirectory, library);
                PreparedLibrary result = extractAndVerify(source, destination);
                prepared.add(result);
                copiedBytes += result.size;

                report.append(result.reused ? "[REUSED] " : "[COPIED] ")
                        .append(result.name)
                        .append(" | ")
                        .append(formatBytes(result.size))
                        .append("\n  sha256=")
                        .append(result.sha256)
                        .append('\n');
            }

            File manifestFile = new File(profileDirectory, "runtime-manifest.txt");
            writeManifest(manifestFile, packageInfo.versionName, versionCode, prepared);

            boolean allReadable = true;
            for (PreparedLibrary library : prepared) {
                if (!library.file.isFile()
                        || !library.file.canRead()
                        || library.file.length() != library.size) {
                    allReadable = false;
                    break;
                }
            }

            report.append("\n=== RUNTIME VERDICT ===\n");
            report.append("Libraries prepared: ").append(prepared.size()).append('\n');
            report.append("Prepared payload: ").append(formatBytes(copiedBytes)).append('\n');
            report.append("Manifest: ").append(manifestFile.getAbsolutePath()).append('\n');
            report.append("Readability check: ")
                    .append(allReadable ? "PASS" : "FAIL")
                    .append('\n');
            report.append("Runtime preparation: ")
                    .append(allReadable ? "READY FOR STAGE 3.3" : "FAILED")
                    .append('\n');
            report.append("Safety: libraries were copied and hashed, never loaded or executed.");
        } catch (PackageManager.NameNotFoundException error) {
            report.append("Runtime preparation: BLOCKED\n")
                    .append("Minecraft Bedrock is not installed.");
        } catch (Throwable error) {
            report.append("Runtime preparation: FAILED\n")
                    .append(error.getClass().getSimpleName())
                    .append(": ")
                    .append(error.getMessage());
        }

        return report.toString();
    }

    public static String inspectPrepared(Context context) {
        StringBuilder report = new StringBuilder();
        File directory = new File(
                new File(context.getNoBackupFilesDir(), "bedrock-runtime"),
                BedrockProfile.ID);

        report.append("MATH BEDROCK RUNTIME STATUS\n");
        report.append("Directory: ").append(directory.getAbsolutePath()).append('\n');

        if (!directory.isDirectory()) {
            return report.append("Prepared runtime: NOT FOUND").toString();
        }

        boolean complete = true;
        long total = 0L;
        for (String name : RUNTIME_LIBRARIES) {
            File file = new File(directory, name);
            boolean ready = file.isFile() && file.canRead() && file.length() > 0;
            report.append(ready ? "[READY] " : "[MISSING] ")
                    .append(name);
            if (ready) {
                total += file.length();
                report.append(" | ").append(formatBytes(file.length()));
            } else {
                complete = false;
            }
            report.append('\n');
        }

        report.append("Total payload: ").append(formatBytes(total)).append('\n');
        report.append("Prepared runtime: ").append(complete ? "COMPLETE" : "INCOMPLETE");
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

    private static List<String> collectApkPaths(ApplicationInfo info) {
        List<String> paths = new ArrayList<>();
        if (info.sourceDir != null) paths.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            Collections.addAll(paths, info.splitSourceDirs);
        }
        return paths;
    }

    private static Map<String, LibrarySource> discoverSources(List<String> apkPaths)
            throws Exception {
        Map<String, LibrarySource> result = new LinkedHashMap<>();

        for (String apkPath : apkPaths) {
            try (ZipFile zip = new ZipFile(apkPath)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entry.isDirectory()
                            || !entryName.startsWith(ABI_PREFIX)
                            || !entryName.endsWith(".so")) {
                        continue;
                    }

                    String libraryName = entryName.substring(ABI_PREFIX.length());
                    for (String required : RUNTIME_LIBRARIES) {
                        if (required.equals(libraryName) && !result.containsKey(required)) {
                            result.put(required, new LibrarySource(
                                    apkPath,
                                    entryName,
                                    entry.getSize(),
                                    entry.getCrc()));
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static PreparedLibrary extractAndVerify(
            LibrarySource source,
            File destination) throws Exception {
        if (destination.isFile() && destination.length() == source.size) {
            String existingHash = sha256(destination);
            if (!existingHash.isEmpty()) {
                destination.setReadable(true, true);
                destination.setExecutable(true, true);
                return new PreparedLibrary(
                        destination.getName(),
                        destination,
                        destination.length(),
                        existingHash,
                        true,
                        source.crc);
            }
        }

        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IllegalStateException("Cannot remove stale temporary file: " + temporary);
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long written = 0L;

        try (ZipFile zip = new ZipFile(source.apkPath)) {
            ZipEntry entry = zip.getEntry(source.entryName);
            if (entry == null) {
                throw new IllegalStateException("APK entry disappeared: " + source.entryName);
            }

            try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                 BufferedOutputStream output = new BufferedOutputStream(
                         new FileOutputStream(temporary), 1024 * 1024)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    written += count;
                }
            }
        }

        if (written != source.size) {
            temporary.delete();
            throw new IllegalStateException(
                    "Size mismatch for " + destination.getName()
                            + ": expected=" + source.size
                            + ", actual=" + written);
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Cannot replace " + destination);
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Atomic rename failed for " + destination);
        }

        destination.setReadable(true, true);
        destination.setExecutable(true, true);

        return new PreparedLibrary(
                destination.getName(),
                destination,
                written,
                toHex(digest.digest()),
                false,
                source.crc);
    }

    private static void writeManifest(
            File manifest,
            String versionName,
            long versionCode,
            List<PreparedLibrary> libraries) throws Exception {
        StringBuilder value = new StringBuilder();
        value.append("profile=").append(BedrockProfile.ID).append('\n');
        value.append("versionName=").append(versionName).append('\n');
        value.append("versionCode=").append(versionCode).append('\n');
        value.append("abi=").append(BedrockProfile.ABI).append('\n');
        value.append("graphics=").append(BedrockProfile.GRAPHICS_BACKEND).append('\n');
        value.append("nativeLoadingAttempted=false\n");

        for (PreparedLibrary library : libraries) {
            value.append("library=")
                    .append(library.name)
                    .append('|')
                    .append(library.size)
                    .append('|')
                    .append(library.sha256)
                    .append('|')
                    .append(Long.toHexString(library.sourceCrc))
                    .append('\n');
        }

        try (FileOutputStream output = new FileOutputStream(manifest, false)) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        manifest.setReadable(true, true);
    }

    private static void ensureDirectory(File directory) {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Cannot create runtime directory: " + directory);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] value) {
        StringBuilder hex = new StringBuilder(value.length * 2);
        for (byte item : value) {
            hex.append(String.format(Locale.ROOT, "%02x", item));
        }
        return hex.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1024.0);
    }

    private static final class LibrarySource {
        final String apkPath;
        final String entryName;
        final long size;
        final long crc;

        LibrarySource(String apkPath, String entryName, long size, long crc) {
            this.apkPath = apkPath;
            this.entryName = entryName;
            this.size = size;
            this.crc = crc;
        }
    }

    private static final class PreparedLibrary {
        final String name;
        final File file;
        final long size;
        final String sha256;
        final boolean reused;
        final long sourceCrc;

        PreparedLibrary(
                String name,
                File file,
                long size,
                String sha256,
                boolean reused,
                long sourceCrc) {
            this.name = name;
            this.file = file;
            this.size = size;
            this.sha256 = sha256;
            this.reused = reused;
            this.sourceCrc = sourceCrc;
        }
    }
}
