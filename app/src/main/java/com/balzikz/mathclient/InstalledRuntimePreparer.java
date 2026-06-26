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
import java.nio.file.Files;
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

public final class InstalledRuntimePreparer {
    private static final String ABI_PREFIX = "lib/arm64-v8a/";
    private static final long SAFETY_MARGIN = 64L * 1024L * 1024L;
    private static final String[] LIBRARIES = {
            "libc++_shared.so",
            "libmaesdk.so",
            "libHttpClient.Android.so",
            "libPlayFabMultiplayer.so",
            "libfmod.so",
            "libMediaDecoders_Android.so",
            "libconscrypt_jni.so",
            "libmcfix.so",
            "libminecraftpe.so"
    };

    private InstalledRuntimePreparer() {
    }

    public static String prepare(Context context) {
        StringBuilder out = new StringBuilder("MATH STAGE 5.1 RUNTIME\n");
        out.append("Source: installed Minecraft package\n");
        out.append("Execution during preparation: NO\n\n");

        try {
            PackageInfo packageInfo = packageInfo(context);
            ApplicationInfo appInfo = packageInfo.applicationInfo;
            if (appInfo == null) throw new IllegalStateException("ApplicationInfo missing");

            String versionName = packageInfo.versionName == null ? "unknown" : packageInfo.versionName;
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;

            out.append("Installed version: ").append(versionName).append('\n');
            out.append("Installed version code: ").append(versionCode).append('\n');
            out.append("Supported family: ")
                    .append(BedrockProfile.SUPPORTED_VERSION_PREFIX)
                    .append(".x\n");

            if (!BedrockProfile.isSupportedVersion(versionName)) {
                return out.append("Version gate: BLOCK\n")
                        .append("Runtime preparation: BLOCKED UNKNOWN VERSION")
                        .toString();
            }
            out.append("Version gate: PASS\n\n");

            Map<String, Source> sources = discover(appInfo);
            long payload = 0L;
            for (String name : LIBRARIES) {
                Source source = sources.get(name);
                if (source == null) {
                    return out.append("[MISSING] ").append(name).append('\n')
                            .append("Runtime preparation: BLOCKED MISSING LIBRARY")
                            .toString();
                }
                payload += source.size;
                out.append("[FOUND] ").append(name)
                        .append(" | ").append(formatBytes(source.size)).append('\n');
            }

            File runtime = HostJournal.runtime(context);
            if (!runtime.isDirectory() && !runtime.mkdirs()) {
                throw new IllegalStateException("Cannot create " + runtime);
            }

            long available = new StatFs(runtime.getAbsolutePath()).getAvailableBytes();
            out.append("\nRuntime: ").append(runtime.getAbsolutePath()).append('\n');
            out.append("Payload: ").append(formatBytes(payload)).append('\n');
            out.append("Available: ").append(formatBytes(available)).append('\n');
            if (available < payload + SAFETY_MARGIN) {
                return out.append("Storage gate: BLOCK\n")
                        .append("Runtime preparation: BLOCKED LOW STORAGE")
                        .toString();
            }
            out.append("Storage gate: PASS\n\n");

            List<Result> results = new ArrayList<>();
            for (String name : LIBRARIES) {
                Result result = extract(sources.get(name), new File(runtime, name));
                results.add(result);
                out.append(result.reused ? "[REUSED] " : "[COPIED] ")
                        .append(result.name)
                        .append(" | ").append(formatBytes(result.size))
                        .append("\n  sha256=").append(result.sha256).append('\n');
            }

            File manifest = new File(runtime, "runtime-manifest.txt");
            writeManifest(manifest, versionName, versionCode, results);
            out.append("\nManifest: ").append(manifest.getAbsolutePath()).append('\n');
            out.append("Runtime preparation: READY FOR STAGE 5.1");
        } catch (PackageManager.NameNotFoundException error) {
            out.append("Minecraft Bedrock is not installed.\n")
                    .append("Runtime preparation: BLOCKED");
        } catch (Throwable error) {
            out.append("Runtime preparation: FAILED\n")
                    .append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage());
        }
        return out.toString();
    }

    public static String inspect(Context context) {
        StringBuilder out = new StringBuilder("MATH STAGE 5.1 RUNTIME STATUS\n");
        File runtime = HostJournal.runtime(context);
        out.append("Runtime: ").append(runtime.getAbsolutePath()).append('\n');
        try {
            BedrockProfile.Installed installed = BedrockProfile.installed(context);
            out.append(BedrockProfile.summary(installed)).append("\n\n");
            if (!installed.supported) return out.append("Prepared runtime: BLOCKED VERSION").toString();

            File manifest = new File(runtime, "runtime-manifest.txt");
            if (!manifest.isFile()) return out.append("Prepared runtime: NOT FOUND").toString();
            String manifestText = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
            boolean exactVersion = manifestText.contains("versionName=" + installed.versionName + "\n")
                    && manifestText.contains("versionCode=" + installed.versionCode + "\n");
            out.append("Manifest version: ").append(exactVersion ? "MATCH" : "STALE").append('\n');

            boolean complete = exactVersion;
            for (String name : LIBRARIES) {
                File file = new File(runtime, name);
                boolean ready = file.isFile() && file.canRead() && file.length() > 0;
                out.append(ready ? "[READY] " : "[MISSING] ")
                        .append(name);
                if (ready) out.append(" | ").append(formatBytes(file.length()));
                out.append('\n');
                complete &= ready;
            }
            out.append("Prepared runtime: ").append(complete ? "COMPLETE" : "INCOMPLETE");
        } catch (Throwable error) {
            out.append("Prepared runtime: FAILED\n")
                    .append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage());
        }
        return out.toString();
    }

    public static boolean isReady(Context context) {
        return inspect(context).contains("Prepared runtime: COMPLETE");
    }

    private static PackageInfo packageInfo(Context context)
            throws PackageManager.NameNotFoundException {
        PackageManager manager = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return manager.getPackageInfo(
                    BedrockProfile.TARGET_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0));
        }
        return manager.getPackageInfo(BedrockProfile.TARGET_PACKAGE, 0);
    }

    private static Map<String, Source> discover(ApplicationInfo info) throws Exception {
        List<String> paths = new ArrayList<>();
        if (info.sourceDir != null) paths.add(info.sourceDir);
        if (info.splitSourceDirs != null) Collections.addAll(paths, info.splitSourceDirs);

        Map<String, Source> result = new LinkedHashMap<>();
        for (String path : paths) {
            try (ZipFile zip = new ZipFile(path)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entry.isDirectory()
                            || !entryName.startsWith(ABI_PREFIX)
                            || !entryName.endsWith(".so")) continue;
                    String name = entryName.substring(ABI_PREFIX.length());
                    for (String required : LIBRARIES) {
                        if (required.equals(name) && !result.containsKey(name)) {
                            result.put(name, new Source(path, entryName, entry.getSize(), entry.getCrc()));
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Result extract(Source source, File destination) throws Exception {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IllegalStateException("Cannot remove stale temporary file");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long written = 0L;
        try (ZipFile zip = new ZipFile(source.apkPath)) {
            ZipEntry entry = zip.getEntry(source.entryName);
            if (entry == null) throw new IllegalStateException("Source entry disappeared");
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
            throw new IllegalStateException("Size mismatch for " + destination.getName());
        }

        String copiedHash = hex(digest.digest());
        if (destination.isFile() && destination.length() == written
                && copiedHash.equalsIgnoreCase(sha256(destination))) {
            temporary.delete();
            destination.setReadable(true, true);
            destination.setExecutable(true, true);
            return new Result(destination.getName(), written, copiedHash, source.crc, true);
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Cannot replace " + destination.getName());
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Atomic rename failed for " + destination.getName());
        }
        destination.setReadable(true, true);
        destination.setExecutable(true, true);
        return new Result(destination.getName(), written, copiedHash, source.crc, false);
    }

    private static void writeManifest(
            File manifest,
            String versionName,
            long versionCode,
            List<Result> results) throws Exception {
        StringBuilder text = new StringBuilder();
        text.append("profile=").append(BedrockProfile.ID).append('\n');
        text.append("versionName=").append(versionName).append('\n');
        text.append("versionCode=").append(versionCode).append('\n');
        text.append("abi=").append(BedrockProfile.ABI).append('\n');
        text.append("graphics=").append(BedrockProfile.GRAPHICS_BACKEND).append('\n');
        for (Result result : results) {
            text.append("library=").append(result.name).append('|')
                    .append(result.size).append('|')
                    .append(result.sha256).append('|')
                    .append(Long.toHexString(result.crc)).append('\n');
        }
        try (FileOutputStream output = new FileOutputStream(manifest, false)) {
            output.write(text.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        manifest.setReadable(true, true);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(Locale.ROOT, "%02x", item));
        return out.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1024.0);
    }

    private static final class Source {
        final String apkPath;
        final String entryName;
        final long size;
        final long crc;

        Source(String apkPath, String entryName, long size, long crc) {
            this.apkPath = apkPath;
            this.entryName = entryName;
            this.size = size;
            this.crc = crc;
        }
    }

    private static final class Result {
        final String name;
        final long size;
        final String sha256;
        final long crc;
        final boolean reused;

        Result(String name, long size, String sha256, long crc, boolean reused) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
            this.crc = crc;
            this.reused = reused;
        }
    }
}
