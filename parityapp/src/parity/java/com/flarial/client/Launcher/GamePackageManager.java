package com.flarial.client.Launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;

import com.balzikz.mathclient.HostJournal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class GamePackageManager {
    public static final String MINECRAFT_PACKAGE = "com.mojang.minecraftpe";
    private static final String ABI = "arm64-v8a";
    private static final String ZIP_PREFIX = "lib/" + ABI + "/";

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

    private static final String[] PRELOAD_ORDER = {
            "libc++_shared.so",
            "libfmod.so",
            "libHttpClient.Android.so",
            "libmaesdk.so",
            "libPlayFabMultiplayer.so",
            "libMediaDecoders_Android.so",
            "libconscrypt_jni.so",
            "libmcfix.so"
    };

    private final Context launcherContext;
    private final Context gameContext;
    private final ApplicationInfo gameInfo;
    private final PackageInfo gamePackageInfo;
    private final File runtimeDirectory;
    private final AssetManager mergedAssets;
    private final Map<String, String> sources = new LinkedHashMap<>();

    public GamePackageManager(Context context) throws Exception {
        launcherContext = context.getApplicationContext();
        PackageManager packageManager = launcherContext.getPackageManager();
        gamePackageInfo = packageInfo(packageManager, MINECRAFT_PACKAGE);
        gameInfo = applicationInfo(packageManager, MINECRAFT_PACKAGE);
        gameContext = launcherContext.createPackageContext(
                MINECRAFT_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        runtimeDirectory = new File(launcherContext.getCacheDir(), "lib/" + ABI);
        if (!runtimeDirectory.isDirectory() && !runtimeDirectory.mkdirs()) {
            throw new IllegalStateException("Cannot create runtime directory " + runtimeDirectory);
        }
        mergedAssets = createMergedAssets();
        HostJournal.write(launcherContext, "GAME_MANAGER_CREATED",
                "version=" + gamePackageInfo.versionName
                        + " code=" + longVersionCode(gamePackageInfo)
                        + " runtime=" + runtimeDirectory.getAbsolutePath());
    }

    public AssetManager assets() {
        return mergedAssets;
    }

    public String gamePath() {
        return libraryFile("libminecraftpe.so").getAbsolutePath();
    }

    public void prepareRuntime() throws Exception {
        refreshCacheIfNeeded();
        sources.clear();
        copyFromNativeLibraryDirectory();
        copyFromApkContainers();

        List<String> missing = new ArrayList<>();
        for (String name : LIBRARIES) {
            File file = libraryFile(name);
            if (!file.isFile() || file.length() <= 0L) missing.add(name);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing Minecraft libraries: " + missing);
        }

        writeManifest();
        HostJournal.write(launcherContext, "RUNTIME_PREPARED",
                "libraries=" + LIBRARIES.length + " game=" + describe(libraryFile("libminecraftpe.so")));
    }

    public void preloadDependencies() {
        for (String name : PRELOAD_ORDER) {
            File file = libraryFile(name);
            HostJournal.write(launcherContext, "DEPENDENCY_LOAD_START",
                    name + " path=" + file.getAbsolutePath());
            System.load(file.getAbsolutePath());
            HostJournal.write(launcherContext, "DEPENDENCY_LOAD_RETURN", name + " OK");
        }
    }

    public void loadGame() {
        File game = libraryFile("libminecraftpe.so");
        HostJournal.write(launcherContext, "MINECRAFT_SYSTEM_LOAD_START", describe(game));
        long started = System.nanoTime();
        System.load(game.getAbsolutePath());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        HostJournal.write(launcherContext, "MINECRAFT_SYSTEM_LOAD_RETURN",
                "OK elapsedMs=" + elapsedMs + " " + describe(game));
    }

    public String report() {
        StringBuilder result = new StringBuilder();
        result.append("Minecraft package: ").append(MINECRAFT_PACKAGE).append('\n');
        result.append("Version: ").append(gamePackageInfo.versionName)
                .append(" (").append(longVersionCode(gamePackageInfo)).append(")\n");
        result.append("Native dir: ").append(gameInfo.nativeLibraryDir).append('\n');
        result.append("Runtime: ").append(runtimeDirectory.getAbsolutePath()).append('\n');
        for (String name : LIBRARIES) {
            File file = libraryFile(name);
            result.append(file.isFile() ? "[READY] " : "[MISSING] ")
                    .append(name)
                    .append(" bytes=").append(file.isFile() ? file.length() : 0L)
                    .append(" source=").append(sources.getOrDefault(name, "cached/unknown"))
                    .append('\n');
        }
        return result.toString();
    }

    private AssetManager createMergedAssets() throws Exception {
        AssetManager manager;
        try {
            Method factory = AssetManager.class.getDeclaredMethod("newInstance");
            factory.setAccessible(true);
            manager = (AssetManager) factory.invoke(null);
        } catch (Throwable ignored) {
            manager = AssetManager.class.getDeclaredConstructor().newInstance();
        }

        Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
        String base = gameContext.getPackageResourcePath();
        addAssetPath.invoke(manager, base);
        HostJournal.write(launcherContext, "ASSET_PATH_ADD", "minecraft=" + base);

        File installPack = new File(new File(base).getParentFile(), "split_install_pack.apk");
        if (installPack.isFile()) {
            addAssetPath.invoke(manager, installPack.getAbsolutePath());
            HostJournal.write(launcherContext, "ASSET_PATH_ADD",
                    "installPack=" + installPack.getAbsolutePath());
        }

        String own = launcherContext.getPackageResourcePath();
        addAssetPath.invoke(manager, own);
        HostJournal.write(launcherContext, "ASSET_PATH_ADD", "launcher=" + own);
        return manager;
    }

    private void refreshCacheIfNeeded() throws Exception {
        File marker = new File(runtimeDirectory, ".flarial_parity_native_cache");
        String expected = "package=" + MINECRAFT_PACKAGE + "\n"
                + "versionName=" + gamePackageInfo.versionName + "\n"
                + "versionCode=" + longVersionCode(gamePackageInfo) + "\n"
                + "lastUpdate=" + gamePackageInfo.lastUpdateTime + "\n"
                + "abi=" + ABI + "\n"
                + "sources=" + String.join("|", apkContainers()) + "\n";
        String actual = marker.isFile() ? readText(marker) : "";
        if (!expected.equals(actual)) {
            File[] children = runtimeDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!child.delete()) {
                        HostJournal.write(launcherContext, "CACHE_DELETE_WARN", child.getAbsolutePath());
                    }
                }
            }
            writeText(marker, expected);
            HostJournal.write(launcherContext, "CACHE_REFRESH", "Minecraft fingerprint changed");
        } else {
            HostJournal.write(launcherContext, "CACHE_REUSE", "fingerprint MATCH");
        }
    }

    private void copyFromNativeLibraryDirectory() throws Exception {
        if (gameInfo.nativeLibraryDir == null) return;
        File nativeDirectory = new File(gameInfo.nativeLibraryDir);
        if (!nativeDirectory.isDirectory()) return;
        for (String name : LIBRARIES) {
            File source = new File(nativeDirectory, name);
            if (source.isFile() && source.length() > 0L) {
                File destination = libraryFile(name);
                copyIfDifferent(source, destination);
                sources.put(name, "nativeLibraryDir:" + source.getAbsolutePath());
            }
        }
    }

    private void copyFromApkContainers() throws Exception {
        for (String apkPath : apkContainers()) {
            File apk = new File(apkPath);
            String lower = apk.getName().toLowerCase(Locale.ROOT);
            if (!(lower.equals("base.apk") || lower.contains("arm") || lower.contains("x86")
                    || lower.contains("install_pack"))) {
                continue;
            }
            try (ZipFile zip = new ZipFile(apk)) {
                for (String name : LIBRARIES) {
                    ZipEntry entry = zip.getEntry(ZIP_PREFIX + name);
                    if (entry == null || entry.getSize() <= 0L) continue;
                    File destination = libraryFile(name);
                    if (!destination.isFile() || destination.length() != entry.getSize()) {
                        extract(zip, entry, destination);
                        sources.put(name, apk.getName() + "!" + entry.getName());
                    } else if (!sources.containsKey(name)) {
                        sources.put(name, apk.getName() + "!" + entry.getName() + " (same-size reuse)");
                    }
                }
            }
        }
    }

    private List<String> apkContainers() {
        List<String> paths = new ArrayList<>();
        if (gameInfo.sourceDir != null) paths.add(gameInfo.sourceDir);
        if (gameInfo.splitPublicSourceDirs != null) paths.addAll(Arrays.asList(gameInfo.splitPublicSourceDirs));
        return paths;
    }

    private File libraryFile(String name) {
        return new File(runtimeDirectory, name);
    }

    private static void copyIfDifferent(File source, File destination) throws Exception {
        if (destination.isFile() && destination.length() == source.length()) return;
        File temp = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
            transfer(input, output);
        }
        replace(temp, destination);
    }

    private static void extract(ZipFile zip, ZipEntry entry, File destination) throws Exception {
        File temp = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
            transfer(input, output);
        }
        if (entry.getSize() >= 0L && temp.length() != entry.getSize()) {
            throw new IllegalStateException("Truncated extraction for " + entry.getName());
        }
        replace(temp, destination);
    }

    private static void replace(File temp, File destination) throws Exception {
        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("Cannot replace " + destination);
        }
        if (!temp.renameTo(destination)) Files.move(temp.toPath(), destination.toPath());
        destination.setReadable(true, false);
        destination.setExecutable(true, false);
    }

    private static void transfer(InputStream input, BufferedOutputStream output) throws Exception {
        byte[] buffer = new byte[1024 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        output.flush();
    }

    private void writeManifest() throws Exception {
        StringBuilder text = new StringBuilder(report());
        for (String name : LIBRARIES) {
            File file = libraryFile(name);
            text.append(name).append(" sha256=").append(sha256(file)).append('\n');
        }
        writeText(new File(runtimeDirectory, "runtime-manifest.txt"), text.toString());
    }

    private static String readText(File file) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte part : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", part));
        return value.toString();
    }

    private static String describe(File file) {
        return "path=" + file.getAbsolutePath() + " bytes=" + (file.isFile() ? file.length() : 0L);
    }

    private static long longVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private static PackageInfo packageInfo(PackageManager manager, String packageName)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0));
        }
        return manager.getPackageInfo(packageName, 0);
    }

    private static ApplicationInfo applicationInfo(PackageManager manager, String packageName)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0));
        }
        return manager.getApplicationInfo(packageName, 0);
    }
}
