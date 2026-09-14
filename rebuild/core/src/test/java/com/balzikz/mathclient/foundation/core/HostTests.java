package com.balzikz.mathclient.foundation.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Transaction, tampering, APK races and lifecycle tests; only self-generated native bytes. */
public final class HostTests {
    private static int passed;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++; System.out.println("PASS " + message);
    }
    private static void rejects(Checked action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException expected) { rejected = true; }
        check(rejected, message);
    }
    private static void word(ByteBuffer b, int at, long value, boolean wide) {
        if (wide) b.putLong(at, value); else b.putInt(at, (int) value);
    }
    static byte[] elf(boolean wide, boolean little, boolean gnu, boolean hash) {
        ByteBuffer b = ByteBuffer.allocate(2048).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        b.put(new byte[]{127, 'E', 'L', 'F', (byte)(wide ? 2 : 1), (byte)(little ? 1 : 2), 1});
        b.putShort(16, (short)3); b.putShort(18, (short)(wide ? 183 : 40)); b.putInt(20, 1);
        int ph = wide ? 64 : 52, phsize = wide ? 56 : 32, word = wide ? 8 : 4;
        word(b, wide ? 32 : 28, ph, wide);
        b.putShort(wide ? 52 : 40, (short) ph);
        b.putShort(wide ? 54 : 42, (short) phsize); b.putShort(wide ? 56 : 44, (short)2);
        b.putInt(ph, 1); word(b, ph + (wide ? 16 : 8), 0x400000, wide);
        word(b, ph + (wide ? 32 : 16), 2048, wide); word(b, ph + (wide ? 48 : 28), 16384, wide);
        int dp = ph + phsize; b.putInt(dp, 2);
        word(b, dp + (wide ? 8 : 4), 0x100, wide); word(b, dp + (wide ? 16 : 8), 0x400100, wide);
        word(b, dp + (wide ? 32 : 16), 8 * 2 * word, wide);
        String[] names = {"", "libdep.so", "JNI_OnLoad", "android_main", "GameActivity_onCreate", "Java_demo_call", "ANativeActivity_onCreate"};
        int[] offsets = new int[names.length]; int end = 0x300;
        for (int i = 0; i < names.length; i++) {
            offsets[i] = end - 0x300;
            for (byte v : names[i].getBytes(StandardCharsets.UTF_8)) b.put(end++, v);
            b.put(end++, (byte)0);
        }
        long[][] tags = {{1, offsets[1]}, {5, 0x400300}, {10, end - 0x300}, {6, 0x400400},
                {11, wide ? 24 : 16}, {hash ? (gnu ? 0x6ffffef5L : 4) : 21, 0x400600}, {0,0}};
        int dpointer = 0x100;
        for (long[] tag : tags) { word(b, dpointer, tag[0], wide); word(b, dpointer + word, tag[1], wide); dpointer += 2 * word; }
        for (int i = 1; i <= 5; i++) {
            int s = 0x400 + i * (wide ? 24 : 16); b.putInt(s, offsets[i + 1]);
            int binding = i == 4 ? 0 : 1; // local JNI name must not count as export
            b.put(s + (wide ? 4 : 12), (byte)((binding << 4) | 2));
            b.put(s + (wide ? 5 : 13), (byte)(i == 3 ? 2 : 0)); // hidden GameActivity symbol
            b.putShort(s + (wide ? 6 : 14), (short)(i == 2 ? 0 : 1)); // undefined android_main
        }
        if (gnu) {
            b.putInt(0x600, 1); b.putInt(0x604, 1); b.putInt(0x608, 1);
            b.putInt(0x610 + word, 1); // sole bucket starts at symbol 1
            for (int i = 0; i < 5; i++) b.putInt(0x614 + word + i * 4, i == 4 ? 1 : 2);
        } else { b.putInt(0x600, 1); b.putInt(0x604, 6); }
        return b.array();
    }

    private static File apk(Path temp, String name, String[] paths, byte[][] bytes) throws IOException {
        File file = temp.resolve(name).toFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < paths.length; i++) {
                zip.putNextEntry(new ZipEntry(paths[i])); zip.write(bytes[i]); zip.closeEntry();
            }
        }
        return file;
    }
    private static ElfFile.Result lib(String soname, String... needed) {
        ElfFile.Result lib = new ElfFile.Result(); lib.soname = soname; lib.needed.addAll(Arrays.asList(needed)); return lib;
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("math-host-tests-");
        try {
            Map<String, ElfFile.Result> graph = new TreeMap<>();
            graph.put("libgame.so", lib("libgame.so", "libalias.so", "libandroid.so"));
            graph.put("libimplementation.so", lib("libalias.so", "libc.so"));
            NativePlan plan = NativePlan.inspect(graph);
            check(plan.order.equals(Arrays.asList("libimplementation.so", "libgame.so")), "SONAME dependency before consumer");
            check(plan.external.equals(new TreeSet<>(Arrays.asList("libandroid.so", "libc.so"))), "external dependencies stay unverified");
            graph.put("libother.so", lib("libalias.so"));
            rejects(() -> NativePlan.inspect(graph), "ambiguous SONAME rejected");
            graph.remove("libother.so");
            graph.get("libimplementation.so").needed.add("libgame.so");
            plan = NativePlan.inspect(graph);
            check(!plan.cycles.isEmpty() && plan.order.isEmpty(), "cycle never produces a partial load order");

            HostState state = new HostState();
            state.resumed(true); state.surface(true, 1080, 1600);
            check(!state.environmentReady(), "surface alone cannot make host ready");
            state.prepared(false);
            check(state.phase().equals("WAITING_FOR_ASSETS"), "asset proof is required");
            state.prepared(true); check(state.environmentReady(), "all environment prerequisites reach adapter boundary");
            state.resumed(false); check(!state.environmentReady(), "pause revokes environment readiness");
            state.resumed(true); state.surface(true, 0, 0);
            check(!state.environmentReady(), "zero-size surface cannot make host ready");
            state.surface(true, 1080, 1600); check(state.environmentReady(), "replacement live surface can become ready");
            state.surface(false, 1080, 1600); check(!state.environmentReady(), "destroyed surface revokes readiness");
            state.fail(); state.surface(true, 1080, 1600);
            check(state.phase().equals("FAILED"), "surface cannot erase failure");
            state.destroy(); state.prepared(true); state.resumed(true);
            check(state.phase().equals("DESTROYED"), "late callback cannot revive destroyed host");

            byte[] bytes = elf(true, true, false, true);
            File base = apk(temp, "base.apk", new String[]{"classes.dex", "assets/sample"}, new byte[][]{{1}, {2}});
            File split = apk(temp, "unusual-feature.apk", new String[]{"lib/arm64-v8a/libgame.so"}, new byte[][]{bytes});
            List<File> apks = Arrays.asList(base, split);
            List<String> abi = Arrays.asList("x86_64", "arm64-v8a");
            File root = temp.resolve("runtime").toFile();
            List<String> records = new ArrayList<>();
            Inventory.Sink sink = (kind, fields) -> records.add(kind);
            RuntimeImage.Result first = RuntimeImage.prepare(apks, abi, root, "package-v1", "libgame.so", sink);
            check(first.abi.equals("arm64-v8a") && first.libraries.size() == 1, "runtime discovers required library in arbitrary split");
            check(!first.reused && first.directory.isDirectory(), "first complete image published");
            check(!Arrays.stream(root.list()).anyMatch(n -> n.startsWith(".partial-")), "successful transaction leaves no staging image");
            RuntimeImage.Result second = RuntimeImage.prepare(apks, abi, root, "package-v1", "libgame.so", sink);
            check(second.reused && first.id.equals(second.id), "unchanged runtime reused after SHA validation");
            RuntimeImage.Result updated = RuntimeImage.prepare(apks, abi, root, "package-v2", "libgame.so", sink);
            check(!updated.id.equals(first.id), "package identity changes runtime key");
            rejects(() -> RuntimeImage.prepare(apks, Collections.singletonList("x86_64"), root, "v1", "libgame.so", sink), "incompatible process ABI rejected");

            File cached = new File(first.directory, "libgame.so");
            cached.setWritable(true); byte[] corrupt = bytes.clone(); corrupt[2000] ^= 1;
            Files.write(cached.toPath(), corrupt);
            rejects(() -> RuntimeImage.prepare(apks, abi, root, "package-v1", "libgame.so", sink), "same-size cached corruption rejected");

            File duplicate = apk(temp, "duplicate.apk", new String[]{"lib/arm64-v8a/libgame.so"}, new byte[][]{bytes});
            RuntimeImage.Result identical = RuntimeImage.prepare(Arrays.asList(split, duplicate), abi,
                    temp.resolve("duplicates").toFile(), "v1", "libgame.so", sink);
            check(identical.libraries.size() == 1, "identical duplicate library deduplicated");
            File conflict = apk(temp, "conflict.apk", new String[]{"lib/arm64-v8a/libgame.so"}, new byte[][]{corrupt});
            rejects(() -> RuntimeImage.prepare(Arrays.asList(split, conflict), abi, temp.resolve("conflicts").toFile(),
                    "v1", "libgame.so", sink), "conflicting split library rejected");

            File traversal = apk(temp, "traversal.apk",
                    new String[]{"lib/arm64-v8a/libgame.so", "lib/arm64-v8a/../escape.so"}, new byte[][]{bytes, bytes});
            rejects(() -> RuntimeImage.prepare(Collections.singletonList(traversal), abi, temp.resolve("traversal").toFile(),
                    "v1", "libgame.so", sink), "native ZIP traversal rejected");
            check(!temp.resolve("escape.so").toFile().exists(), "no native path escapes snapshot");

            File rollback = temp.resolve("rollback").toFile();
            Inventory.Sink brokenSink = (kind, fields) -> {
                if (kind.equals("host_native_library")) throw new IOException("injected journal failure");
            };
            rejects(() -> RuntimeImage.prepare(apks, abi, rollback, "v1", "libgame.so", brokenSink), "failure during staging reported");
            check(Arrays.stream(rollback.list()).noneMatch(n -> !n.equals("prepare.lock")), "failed staging leaves no ready or partial image");

            File racedApk = apk(temp, "raced.apk", new String[]{"lib/arm64-v8a/libgame.so"}, new byte[][]{bytes});
            File racedRoot = temp.resolve("raced").toFile();
            Inventory.Sink raceSink = (kind, fields) -> {
                if (kind.equals("host_native_library")) Files.write(racedApk.toPath(), new byte[]{1,2,3});
            };
            rejects(() -> RuntimeImage.prepare(Collections.singletonList(racedApk), abi, racedRoot,
                    "v1", "libgame.so", raceSink), "APK mutation during preparation rejected");
            check(Arrays.stream(racedRoot.list()).noneMatch(n -> !n.equals("prepare.lock")), "APK race cannot publish runtime");

            File invalidElf = apk(temp, "invalid-elf.apk", new String[]{"lib/arm64-v8a/libgame.so"},
                    new byte[][]{Arrays.copyOf(bytes, 128)});
            File invalidRoot = temp.resolve("invalid").toFile();
            rejects(() -> RuntimeImage.prepare(Collections.singletonList(invalidElf), abi, invalidRoot,
                    "v1", "libgame.so", sink), "invalid dynamic ELF rejected after extraction");
            check(Arrays.stream(invalidRoot.list()).noneMatch(n -> !n.equals("prepare.lock")), "ELF error rolls back staged library");

            File interrupted = temp.resolve("interrupted").toFile();
            Thread.currentThread().interrupt();
            try { rejects(() -> RuntimeImage.prepare(apks, abi, interrupted, "v1", "libgame.so", sink), "cancellation aborts preparation"); }
            finally { Thread.interrupted(); }

            File stale = temp.resolve("stale").toFile(); stale.mkdirs();
            File partial = new File(stale, ".partial-" + UUID.randomUUID()); partial.mkdir();
            Files.write(new File(partial, "libunfinished.so").toPath(), new byte[]{1});
            RuntimeImage.prepare(apks, abi, stale, "v1", "libgame.so", sink);
            check(!partial.exists(), "interrupted owned staging is cleaned on retry");

            File symlinkRoot = temp.resolve("symlink").toFile(); symlinkRoot.mkdirs();
            Path outside = temp.resolve("outside.so"); Files.write(outside, new byte[]{1});
            Path runtimeLink = symlinkRoot.toPath().resolve("prepare.lock");
            Files.createSymbolicLink(runtimeLink, outside);
            rejects(() -> RuntimeImage.prepare(apks, abi, symlinkRoot, "v1", "libgame.so", sink), "symlink in runtime root rejected");
            check(Files.readAllBytes(outside)[0] == 1, "outside symlink target unchanged");
            System.out.println("ALL " + passed + " HOST TESTS PASSED");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);
            }
        }
    }
}
