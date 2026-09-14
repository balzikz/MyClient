package com.balzikz.mathclient.foundation;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import com.balzikz.mathclient.foundation.contract.*;
import com.balzikz.mathclient.foundation.core.Inventory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class RuntimeContract {
    private RuntimeContract() {}
    static void collect(Context context, SessionLog log, ContractScanner.Progress progress, boolean selfTest) throws Exception {
        String target = selfTest && BuildConfig.DEBUG ? context.getPackageName() : RuntimeInventory.PACKAGE;
        log.event("CONTRACT_BEGIN", "package=" + target + "; metadata only; minecraft_loaded=false");
        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        try { info = pm.getPackageInfo(target, PackageManager.GET_ACTIVITIES
                | PackageManager.GET_META_DATA | PackageManager.GET_SIGNING_CERTIFICATES); }
        catch (PackageManager.NameNotFoundException missing) {
            throw new IOException("Minecraft не найден среди установленных приложений", missing);
        }
        ApplicationInfo app = info.applicationInfo;
        if (app == null) throw new IOException("Package ApplicationInfo unavailable");
        String suffix = System.currentTimeMillis() + "-" + System.nanoTime();
        File output = new File(log.directory, "contract-" + suffix + ".jsonl");
        if (!output.createNewFile()) throw new IOException("Contract report already exists");
        File scratch = new File(context.getCacheDir(), "contract-scratch");
        // Only temporary ELF files owned by this scanner; never sessions or runtime files.
        File[] stale = scratch.listFiles(f -> f.isFile() && f.getName().matches("elf-[0-9]+\\.tmp"));
        if (stale != null) for (File f : stale) if (!f.delete()) throw new IOException("Cannot remove interrupted scan temporary file");
        try (FileOutputStream stream = new FileOutputStream(output);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
            Inventory.Sink sink = (kind, fields) -> {
                Map<String, Object> record = Inventory.fields("kind", kind); record.putAll(fields);
                out.write(Inventory.json(record)); out.newLine();
            };
            sink.item("contract_runtime", Inventory.fields("schema", 1, "package", target,
                    "versionName", info.versionName, "versionCode", info.getLongVersionCode(),
                    "lastUpdateTime", info.lastUpdateTime, "sdk", Build.VERSION.SDK_INT,
                    "applicationClass", app.className, "nativeLibraryDir", app.nativeLibraryDir,
                    "foundationVersion", BuildConfig.VERSION_NAME, "selfTest", selfTest, "minecraftLoaded", false));
            if (info.signingInfo != null) for (Signature signature : info.signingInfo.getApkContentsSigners())
                sink.item("apk_signer", Inventory.fields("sha256", Inventory.digest(new ByteArrayInputStream(signature.toByteArray())).hash));
            Set<String> roots = new LinkedHashSet<>(); roots.add(DexContracts.MAIN);
            if (app.className != null) roots.add(descriptor(app.className));
            if (info.activities != null) for (ActivityInfo activity : info.activities) {
                sink.item("manifest_activity", Inventory.fields("name", activity.name,
                        "process", activity.processName, "nativeLibrary", activity.metaData == null ? null : activity.metaData.getString("android.app.lib_name"),
                        "nativeFunction", activity.metaData == null ? null : activity.metaData.getString("android.app.func_name")));
                if (activity.name.endsWith(".MainActivity")) roots.add(descriptor(activity.name));
            }
            ContractScanner.Summary result = ContractScanner.scan(Inventory.containers(app.sourceDir, app.splitSourceDirs),
                    Arrays.asList(Build.SUPPORTED_64_BIT_ABIS), app.nativeLibraryDir == null ? null : new File(app.nativeLibraryDir),
                    scratch, roots, sink, message -> {
                        try { out.flush(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                        log.event("CONTRACT_PROGRESS", message); progress.update(message);
                    });
            PackageInfo after = pm.getPackageInfo(target, 0);
            if (after.lastUpdateTime != info.lastUpdateTime || after.getLongVersionCode() != info.getLongVersionCode()
                    || after.applicationInfo == null || !Objects.equals(after.applicationInfo.sourceDir, app.sourceDir)
                    || !Arrays.equals(after.applicationInfo.splitSourceDirs, app.splitSourceDirs)) {
                result.blockers.add("PACKAGE_CHANGED_DURING_SCAN");
                sink.item("runtime_changed", Inventory.fields("action", "Repeat contract scan"));
            }
            sink.item("contract_complete", Inventory.fields("metadataComplete", result.complete(),
                    "blockers", result.blockers, "minecraftLoaded", false));
            out.flush(); stream.getFD().sync();
            String text = (selfTest ? "Самопроверка MATH " : "Minecraft ") + info.versionName + "\n" + result.text();
            log.writeText("contract-result-" + suffix + ".txt", text);
            log.event("CONTRACT_COMPLETE", "metadataComplete=" + result.complete() + "; dex=" + result.dex.dexFiles
                    + "; libraries=" + result.nativeLibraries + "; errors=" + result.errors + "; selfTest=" + selfTest);
        }
    }
    private static String descriptor(String name) { return "L" + name.replace('.', '/') + ";"; }
    static String latestSummary(SessionLog log) {
        File[] files = log.directory.listFiles(f -> f.getName().startsWith("contract-result-") && f.getName().endsWith(".txt"));
        if (files == null || files.length == 0) return "Интерфейс запуска Minecraft ещё не прочитан.";
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        try (InputStream in = new FileInputStream(files[0])) {
            byte[] bytes = new byte[16384]; int size = in.read(bytes);
            return size < 0 ? "Пустой результат чтения" : new String(bytes, 0, size, StandardCharsets.UTF_8);
        } catch (IOException error) { return "Не удалось прочитать результат: " + error.getMessage(); }
    }
}
