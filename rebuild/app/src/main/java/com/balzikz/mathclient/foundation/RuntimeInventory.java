package com.balzikz.mathclient.foundation;

import android.content.*;
import android.content.pm.*;
import android.os.Build;
import com.balzikz.mathclient.foundation.core.Inventory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class RuntimeInventory {
    static final String PACKAGE = "com.mojang.minecraftpe";
    static void collect(Context context, SessionLog log) throws Exception {
        log.event("INVENTORY_BEGIN", "Read-only; no runtime code is loaded");
        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        try { info = pm.getPackageInfo(PACKAGE, PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_SERVICES | PackageManager.GET_META_DATA | PackageManager.GET_SIGNING_CERTIFICATES); }
        catch (PackageManager.NameNotFoundException missing) {
            log.event("MINECRAFT_NOT_FOUND", PACKAGE + " not installed or not visible; native probe still available");
            return;
        }
        ApplicationInfo app = info.applicationInfo;
        if (app == null) throw new IOException("Minecraft ApplicationInfo unavailable");
        String name = "runtime-" + System.currentTimeMillis() + "-" + System.nanoTime() + ".jsonl";
        int[] warnings = {0};
        try (FileOutputStream stream = new FileOutputStream(new File(log.directory, name));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
            Inventory.Sink sink = (kind, fields) -> {
                LinkedHashMap<String, Object> record = new LinkedHashMap<>();
                record.put("kind", kind); record.putAll(fields);
                out.write(Inventory.json(record)); out.newLine(); out.flush();
            };
            sink.item("runtime", Inventory.fields("schema", 1, "package", PACKAGE,
                    "versionName", info.versionName, "versionCode", info.getLongVersionCode(),
                    "lastUpdateTime", info.lastUpdateTime, "sdk", Build.VERSION.SDK_INT,
                    "deviceAbis", Arrays.toString(Build.SUPPORTED_ABIS), "nativeLibraryDir", app.nativeLibraryDir,
                    "sourceDir", app.sourceDir, "splitSourceDirs", Arrays.toString(app.splitSourceDirs),
                    "hostCompatibility", "UNKNOWN_NOT_IMPLEMENTED", "minecraftLoaded", false));
            if (info.signingInfo != null) {
                for (Signature signature : info.signingInfo.getApkContentsSigners()) {
                    sink.item("apk_signer", Inventory.fields("sha256", Inventory.digest(
                            new ByteArrayInputStream(signature.toByteArray())).hash));
                }
            }
            if (info.activities != null) for (ActivityInfo activity : info.activities) {
                sink.item("activity", Inventory.fields("name", activity.name, "process", activity.processName,
                        "nativeLibrary", activity.metaData == null ? null : activity.metaData.getString("android.app.lib_name"),
                        "launchMode", activity.launchMode, "configChanges", activity.configChanges));
            }
            if (info.services != null) for (ServiceInfo service : info.services)
                sink.item("service", Inventory.fields("name", service.name, "process", service.processName));
            List<File> containers = Inventory.containers(app.sourceDir, app.splitSourceDirs);
            if (containers.isEmpty()) throw new IOException("No APK containers reported");
            for (File apk : containers) {
                log.event("INVENTORY_APK", apk.getName());
                try { Inventory.scanApk(apk, sink); }
                catch (IOException failure) {
                    warnings[0]++;
                    sink.item("inventory_error", Inventory.fields("path", apk.getPath(), "error", failure.toString()));
                    log.exception("INVENTORY_APK_ERROR", failure);
                }
            }
            if (app.nativeLibraryDir != null) {
                try { Inventory.scanNativeDirectory(new File(app.nativeLibraryDir), sink); }
                catch (IOException failure) {
                    // Modern APKs can keep libraries inside ZIPs; this is not a launch verdict.
                    warnings[0]++;
                    sink.item("native_directory_unavailable", Inventory.fields("error", failure.toString()));
                }
            }
            PackageInfo after = pm.getPackageInfo(PACKAGE, 0);
            if (after.lastUpdateTime != info.lastUpdateTime || after.getLongVersionCode() != info.getLongVersionCode()) {
                warnings[0]++;
                sink.item("runtime_changed", Inventory.fields("action", "Repeat inventory; Minecraft updated during scan"));
            }
            sink.item("inventory_complete", Inventory.fields("warnings", warnings[0],
                    "elfDynamicTablesInspected", false, "jniContractVerified", false,
                    "assetsMerged", false, "minecraftLoaded", false));
            out.flush(); stream.getFD().sync();
        }
        log.event("INVENTORY_COMPLETE", "Minecraft " + info.versionName + " (" + info.getLongVersionCode()
                + "); warnings=" + warnings[0] + "; " + name + "; not a launch compatibility verdict");
    }
}
