package com.balzikz.mathclient.foundation.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** No Android SDK, Gradle download, or third-party test framework needed. */
public final class CoreTests {
    private static int passed;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++; System.out.println("PASS " + message);
    }
    private interface Throws { void run() throws Exception; }
    private static void rejects(Throws action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException expected) { rejected = true; }
        check(rejected, message);
    }
    private static byte[] elf(int machine) {
        byte[] data = new byte[128];
        data[0] = 0x7f; data[1] = 'E'; data[2] = 'L'; data[3] = 'F';
        data[4] = 2; data[5] = 1; data[6] = 1;
        data[18] = (byte) machine; data[19] = (byte) (machine >> 8);
        return data;
    }
    private static void apk(File file, String[] names, byte[][] contents) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i])); zip.write(contents[i]); zip.closeEntry();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("math-core-tests-");
        try {
            byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
            check(Inventory.digest(new ByteArrayInputStream(abc)).hash.equals(
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "SHA-256 known vector");
            check(Inventory.elfHeader(elf(183)).equals("ELF64_LE_MACHINE_183"), "arm64 header");
            check(Inventory.elfHeader(elf(62)).equals("ELF64_LE_MACHINE_62"), "x86_64 header");
            check(Inventory.elfHeader(new byte[4]).equals("INVALID_OR_TRUNCATED"), "truncated header rejected");
            check(Inventory.elfHeader(Arrays.copyOf(elf(183), 20)).equals("INVALID_OR_TRUNCATED"), "ELF64 needs complete header");
            byte[] bad = elf(183); bad[5] = 0;
            check(Inventory.elfHeader(bad).equals("INVALID_OR_TRUNCATED"), "invalid ELF endianness rejected");
            byte[] big = elf(183); big[5] = 2; big[18] = 0; big[19] = (byte) 183;
            check(Inventory.elfHeader(big).equals("ELF64_BE_MACHINE_183"), "big-endian header");
            File base = tmp.resolve("base.apk").toFile(), split = tmp.resolve("arbitrary-name.apk").toFile();
            apk(base, new String[]{"classes.dex", "assets/a", "lib/x86_64/libprobe.so"},
                    new byte[][]{abc, abc, elf(62)});
            apk(split, new String[]{"lib/arm64-v8a/libminecraftpe.so", "lib/arm64-v8a/libnew-dependency.so"},
                    new byte[][]{elf(183), elf(183)});
            List<File> containers = Inventory.containers(base.getPath(), new String[]{split.getPath(), base.getPath()});
            check(containers.equals(Arrays.asList(base, split)), "all splits kept in order and deduplicated");
            List<Map<String, Object>> records = new ArrayList<>();
            Inventory.Sink sink = (kind, fields) -> { fields.put("kind", kind); records.add(fields); };
            for (File file : containers) Inventory.scanApk(file, sink);
            check(records.stream().filter(r -> r.get("kind").equals("native_library")).count() == 3,
                    "unknown native dependency discovered without whitelist");
            check(records.stream().anyMatch(r -> "lib/arm64-v8a/libminecraftpe.so".equals(r.get("entry"))),
                    "Minecraft library found in arbitrary split filename");
            check(records.stream().anyMatch(r -> Integer.valueOf(1).equals(r.get("assetEntries"))), "asset entries counted");
            check(records.stream().anyMatch(r -> Integer.valueOf(1).equals(r.get("dexFiles"))), "DEX entries counted");
            File first = tmp.resolve("first.so").toFile(), second = tmp.resolve("second.so").toFile();
            Files.write(first.toPath(), abc); Files.write(second.toPath(), new byte[]{'x', 'y', 'z'});
            check(first.length() == second.length() && !Inventory.sha256(first).equals(Inventory.sha256(second)),
                    "same-size different files have different fingerprints");
            rejects(() -> Inventory.scanApk(first, sink), "invalid ZIP fails visibly");
            rejects(() -> Inventory.scanApk(tmp.resolve("missing.apk").toFile(), sink), "missing APK fails visibly");
            Thread.currentThread().interrupt();
            try { rejects(() -> Inventory.digest(new ByteArrayInputStream(abc)), "stream cancellation respected"); }
            finally { Thread.interrupted(); }
            File root = tmp.resolve("sessions").toFile(); root.mkdirs();
            rejects(() -> SessionFiles.directory(root, "../../escape"), "session traversal rejected");
            rejects(() -> SessionFiles.directory(root, "/tmp/escape"), "absolute session path rejected");
            String id = "1789300000000-abcdef012345";
            File dir = SessionFiles.directory(root, id); dir.mkdirs();
            Files.writeString(new File(dir, "events-1.jsonl").toPath(), "{\"event\":\"PROBE_SWAP_OK\"}\n");
            Files.write(new File(dir, "native-exit.pb").toPath(), abc);
            Files.write(new File(dir, "libminecraftpe.so").toPath(), abc);
            check(SessionFiles.files(dir).size() == 2, "game binaries excluded from session exports");
            File archive = tmp.resolve("session.zip").toFile(); SessionFiles.zip(dir, archive);
            try (ZipFile zip = new ZipFile(archive)) {
                check(zip.size() == 2 && zip.getEntry("native-exit.pb") != null, "ZIP contains logs and native trace");
                check(zip.getEntry("libminecraftpe.so") == null, "ZIP does not contain Minecraft code");
            }
            check(SessionFiles.readText(dir, 1000).contains("PROBE_SWAP_OK"), "text export includes whole log");
            rejects(() -> SessionFiles.readText(dir, 5), "copy size limit fails instead of silent truncation");
            rejects(() -> SessionFiles.directory(root, id + "/child"), "nested session path rejected");
            check(Inventory.quote("a\n\"\\\t").equals("\"a\\n\\\"\\\\\\t\""), "JSON control characters escaped");
            check(Inventory.json(Inventory.fields("ok", true, "size", 1, "missing", null))
                    .equals("{\"ok\":true,\"size\":1,\"missing\":null}"), "JSON typed values preserved");
            check(Inventory.quote("\ud83d\ude00").equals("\"\\ud83d\\ude00\""), "surrogate pair encoded safely");
            Path outside = tmp.resolve("outside.txt"); Files.writeString(outside, "outside");
            Files.createSymbolicLink(new File(dir, "escape.txt").toPath(), outside);
            rejects(() -> SessionFiles.files(dir), "symlink outside session rejected");
            System.out.println("ALL " + passed + " TESTS PASSED");
            HostTests.main(args);
        } finally {
            // Only this explicitly-created fixture directory is removed.
            try (java.util.stream.Stream<Path> paths = Files.walk(tmp)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);
            }
        }
    }
}
