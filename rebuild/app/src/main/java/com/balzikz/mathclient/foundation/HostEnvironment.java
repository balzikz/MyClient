package com.balzikz.mathclient.foundation;

import android.content.Context;
import android.content.pm.*;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;
import com.balzikz.mathclient.foundation.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Files/resources for a future adapter, prepared only inside the host process. */
final class HostEnvironment {
    final RuntimeImage.Result runtime;
    final Resources gameResources;
    final int assetSamples;
    private HostEnvironment(RuntimeImage.Result runtime, Resources resources, int samples) {
        this.runtime = runtime; this.gameResources = resources; this.assetSamples = samples;
    }
    private static PackageInfo installed(Context context, String target) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(target, PackageManager.GET_SIGNING_CERTIFICATES);
    }
    private static String identity(PackageInfo info) throws IOException {
        if (info.applicationInfo == null || info.signingInfo == null)
            throw new IOException("Installed package identity unavailable");
        List<String> signers = new ArrayList<>();
        for (Signature signer : info.signingInfo.getApkContentsSigners())
            signers.add(Inventory.digest(new ByteArrayInputStream(signer.toByteArray())).hash);
        if (signers.isEmpty()) throw new IOException("Installed package has no signing certificate");
        Collections.sort(signers);
        return Inventory.json(Inventory.fields("package", info.packageName, "versionName", info.versionName,
                "versionCode", info.getLongVersionCode(), "lastUpdateTime", info.lastUpdateTime,
                "signers", signers, "source", info.applicationInfo.sourceDir,
                "splits", info.applicationInfo.splitSourceDirs == null ? Collections.emptyList()
                        : Arrays.asList(info.applicationInfo.splitSourceDirs)));
    }
    static HostEnvironment prepare(Context context, SessionLog log, boolean selfTest) throws Exception {
        if (!android.app.Application.getProcessName().equals(context.getPackageName() + ":game_host"))
            throw new IOException("Runtime preparation must run in the game_host process");
        if (!Process.is64Bit()) throw new IOException("Host requires a 64-bit process");
        String target = selfTest && BuildConfig.DEBUG ? context.getPackageName() : RuntimeInventory.PACKAGE;
        PackageInfo before = installed(context, target);
        String identity = identity(before);
        List<File> apks = Inventory.containers(before.applicationInfo.sourceDir, before.applicationInfo.splitSourceDirs);
        String suffix = System.currentTimeMillis() + "-" + System.nanoTime();
        File report = new File(log.directory, "host-" + suffix + ".jsonl");
        if (!report.createNewFile()) throw new IOException("Host report already exists");
        try (FileOutputStream stream = new FileOutputStream(report);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
            Inventory.Sink sink = (kind, fields) -> {
                Map<String, Object> row = Inventory.fields("kind", kind); row.putAll(fields);
                out.write(Inventory.json(row)); out.newLine(); out.flush();
            };
            try {
                sink.item("host_runtime", Inventory.fields("schema", 1, "identity", identity, "selfTest", selfTest,
                        "foundationVersion", BuildConfig.VERSION_NAME, "minecraftLoaded", false));
                log.event("HOST_PREPARE_BEGIN", "package=" + target + "; selfTest=" + selfTest);
                RuntimeImage.Result runtime = RuntimeImage.prepare(apks, Arrays.asList(Build.SUPPORTED_64_BIT_ABIS),
                        new File(context.getNoBackupFilesDir(), "host-runtime"), identity,
                        selfTest && BuildConfig.DEBUG ? "libmathprobe.so" : "libminecraftpe.so", sink);
                // Public API resolves the game's base/split Resources without creating its Application,
                // injecting assets through hidden APIs, or initializing game Java classes.
                Resources resources = context.getPackageManager().getResourcesForApplication(before.applicationInfo);
                int samples = proveAssets(apks, resources, sink);
                if (!identity.equals(identity(installed(context, target))))
                    throw new IOException("Installed package changed; repeat host preparation");
                sink.item("host_environment_complete", Inventory.fields("prepared", true, "assetSamples", samples,
                        "runtimeId", runtime.id, "abi", runtime.abi, "reused", runtime.reused,
                        "hostAdapter", "NOT_IMPLEMENTED", "minecraftLoaded", false, "gameFrame", false));
                stream.getFD().sync();
                log.event("HOST_RUNTIME_READY", "id=" + runtime.id + "; reused=" + runtime.reused
                        + "; assetSamples=" + samples + "; minecraftLoaded=false");
                log.writeText("host-result-" + suffix + ".txt", "Файлы хоста подготовлены.\nABI: " + runtime.abi
                        + "\nБиблиотек: " + runtime.libraries.size() + "\nОбразцы assets проверены: " + samples
                        + "\nПовторное использование: " + runtime.reused
                        + "\nВнешние зависимости не проверены: " + String.join(", ", runtime.plan.external)
                        + "\nИгровой адаптер ещё не подключён. Minecraft не загружен.");
                return new HostEnvironment(runtime, resources, samples);
            } catch (Exception error) {
                sink.item("host_environment_complete", Inventory.fields("prepared", false, "error", error.toString(),
                        "minecraftLoaded", false, "gameFrame", false));
                stream.getFD().sync(); throw error;
            }
        }
    }
    private static final int MAX_ASSET = 1024 * 1024;
    private static final class Sample {
        final File apk; final String entry; final long bytes;
        Sample(File apk, String entry, long bytes) { this.apk = apk; this.entry = entry; this.bytes = bytes; }
    }
    private static int proveAssets(List<File> apks, Resources resources, Inventory.Sink sink) throws IOException {
        Map<String, Sample> unique = new TreeMap<>();
        Set<String> duplicates = new HashSet<>();
        Set<File> withAssets = new LinkedHashSet<>();
        int count = 0;
        for (File apk : apks) try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
                if (++count > 300_000) throw new IOException("Asset entry count exceeds limit");
                ZipEntry entry = entries.nextElement(); String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("assets/")) continue;
                withAssets.add(apk);
                if (unique.containsKey(name)) duplicates.add(name);
                // Track all names to avoid assuming the precedence of a duplicate split asset.
                unique.put(name, new Sample(apk, name, entry.getSize()));
            }
        }
        for (String name : duplicates) unique.remove(name);
        Map<File, Sample> selected = new LinkedHashMap<>();
        for (Sample sample : unique.values()) {
            if (selected.containsKey(sample.apk)) continue;
            if (sample.bytes > 0 && sample.bytes <= MAX_ASSET) selected.put(sample.apk, sample);
        }
        if (withAssets.isEmpty() || !selected.keySet().containsAll(withAssets))
            throw new IOException("No unique bounded asset sample for each APK containing assets");
        for (Sample sample : selected.values()) {
            Inventory.Digest expected, actual;
            try (ZipFile zip = new ZipFile(sample.apk); InputStream raw = zip.getInputStream(zip.getEntry(sample.entry));
                 InputStream asset = resources.getAssets().open(sample.entry.substring("assets/".length()))) {
                expected = assetDigest(raw); actual = assetDigest(asset);
            }
            if (expected.bytes != actual.bytes || !expected.hash.equals(actual.hash))
                throw new IOException("AssetManager sample differs from installed APK: " + sample.entry);
            sink.item("host_asset_sample", Inventory.fields("apk", sample.apk.getPath(), "entry", sample.entry,
                    "sha256", actual.hash, "bytes", actual.bytes, "matched", true,
                    "scope", "ONE_UNIQUE_SAMPLE_PER_ASSET_APK"));
        }
        return selected.size();
    }
    private static Inventory.Digest assetDigest(InputStream in) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
            if (data.size() + n > MAX_ASSET) throw new IOException("Asset sample exceeds limit");
            data.write(buffer, 0, n);
        }
        return Inventory.digest(new ByteArrayInputStream(data.toByteArray()));
    }
}
