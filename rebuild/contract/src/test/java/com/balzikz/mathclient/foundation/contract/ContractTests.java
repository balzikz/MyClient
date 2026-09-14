package com.balzikz.mathclient.foundation.contract;

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.balzikz.mathclient.foundation.core.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Self-generated ELF and DEX fixtures. No Minecraft/Flarial code or binaries in tests. */
public final class ContractTests {
    private static int passed;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean ok, String text) {
        if (!ok) throw new AssertionError(text);
        passed++; System.out.println("PASS " + text);
    }
    private static void rejects(Checked action, String text) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException expected) { rejected = true; }
        check(rejected, text);
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
    private static byte[] dex(Path directory, String name, String type, String parent, boolean nativeMethod) throws IOException {
        List<ImmutableMethod> methods = new ArrayList<>();
        if (nativeMethod) methods.add(new ImmutableMethod(type, "nativeBoot",
                Arrays.asList(new ImmutableMethodParameter("[I", null, null),
                        new ImmutableMethodParameter("Landroid/view/Surface;", null, null), new ImmutableMethodParameter("J", null, null)),
                "J", 0x109, null, null, null));
        ImmutableClassDef cls = new ImmutableClassDef(type, 1, parent, Collections.emptyList(), null,
                Collections.emptyList(), Collections.emptyList(), methods);
        Path output = directory.resolve(name);
        DexPool.writeTo(output.toString(), new ImmutableDexFile(Opcodes.forApi(28), Collections.singletonList(cls)));
        return Files.readAllBytes(output);
    }
    private static File apk(Path directory, String name, String[] entries, byte[][] contents) throws IOException {
        File file = directory.resolve(name).toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < entries.length; i++) { out.putNextEntry(new ZipEntry(entries[i])); out.write(contents[i]); out.closeEntry(); }
        }
        return file;
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("math-contract-tests-");
        try {
            File file = temp.resolve("fixture.so").toFile();
            for (boolean wide : new boolean[]{false, true}) for (boolean little : new boolean[]{false, true}) {
                Files.write(file.toPath(), elf(wide, little, false, true));
                ElfFile.Result r = ElfFile.inspect(file);
                check(r.bits == (wide ? 64 : 32) && r.needed.equals(Collections.singletonList("libdep.so")), "ELF bits/endian/DT_NEEDED " + wide + "/" + little);
                check(r.exports.equals(new TreeSet<>(Arrays.asList("JNI_OnLoad", "ANativeActivity_onCreate"))), "undefined, hidden and local functions excluded " + wide + "/" + little);
            }
            Files.write(file.toPath(), elf(true, true, true, true));
            ElfFile.Result gnu = ElfFile.inspect(file);
            check(gnu.symbolsComplete && gnu.symbolCount == 6 && gnu.symbolSource.equals("DT_GNU_HASH"), "GNU hash without section headers");
            Files.write(file.toPath(), elf(true, true, false, false));
            check(!ElfFile.inspect(file).symbolsComplete, "unavailable symbol count is UNKNOWN, not absent entrypoint");
            byte[] invalid = elf(true, true, false, true);
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putLong(0x100 + 2 * 16 + 8, Long.MAX_VALUE);
            Files.write(file.toPath(), invalid);
            rejects(() -> ElfFile.inspect(file), "oversized dynamic string range rejected");
            Files.write(file.toPath(), new byte[4]);
            rejects(() -> ElfFile.inspect(file), "truncated ELF rejected");
            check(Inventory.json(Inventory.fields("items", Arrays.asList("a", "b"))).equals("{\"items\":[\"a\",\"b\"]}"), "report collections are JSON arrays");
            String game = "Lcom/google/androidgamesdk/GameActivity;";
            byte[] mainDex = dex(temp, "main.dex", DexContracts.MAIN, game, true);
            byte[] parentDex = dex(temp, "parent.dex", game, "Landroid/app/Activity;", false);
            List<Map<String,Object>> records = new ArrayList<>();
            Inventory.Sink sink = (kind, fields) -> { fields.put("kind", kind); records.add(fields); };
            DexContracts.Index index = new DexContracts.Index();
            DexContracts.scan(mainDex, "classes.dex", Collections.singleton(DexContracts.MAIN), index, sink);
            DexContracts.scan(parentDex, "classes2.dex", Collections.singleton(DexContracts.MAIN), index, sink);
            check(index.hierarchy(DexContracts.MAIN).equals(Arrays.asList(DexContracts.MAIN, game, "Landroid/app/Activity;")), "hierarchy resolved across DEX files without loading Android classes");
            check(index.nativeMethods.contains(DexContracts.MAIN + "->nativeBoot([ILandroid/view/Surface;J)J"), "exact JNI descriptor includes arrays, references and wide types");
            check(records.stream().anyMatch(r -> "java_method".equals(r.get("kind")) && Boolean.TRUE.equals(r.get("static"))), "static native method preserved");
            DexContracts.scan(mainDex, "duplicate.dex", Collections.singleton(DexContracts.MAIN), index, sink);
            check(index.duplicateClasses == 1, "duplicate DEX class reported");
            rejects(() -> DexContracts.scan(new byte[112], "invalid.dex", Collections.emptySet(), new DexContracts.Index(), sink), "invalid DEX rejected");
            rejects(() -> ContractScanner.readDex(new ByteArrayInputStream(mainDex), mainDex.length + 1), "truncated DEX stream rejected");
            rejects(() -> ContractScanner.readDex(new ByteArrayInputStream(mainDex), 70L * 1024 * 1024), "DEX memory bound checked before allocation");
            File base = apk(temp, "base.apk", new String[]{"classes.dex"}, new byte[][]{mainDex});
            byte[] nativeBytes = elf(true, true, true, true);
            File split = apk(temp, "unusual-feature.apk", new String[]{"classes2.dex", "lib/arm64-v8a/libminecraftpe.so"}, new byte[][]{parentDex, nativeBytes});
            File cache = temp.resolve("scratch").toFile();
            records.clear();
            ContractScanner.Summary result = ContractScanner.scan(Arrays.asList(base, split), Collections.singletonList("arm64-v8a"), null,
                    cache, Collections.singleton(DexContracts.MAIN), sink, ignored -> {});
            check(result.complete() && result.nativeLibraries == 1 && result.dex.dexFiles == 2, "contract found in arbitrary split APK");
            check(cache.list().length == 0, "temporary ELF removed after scan");
            check(records.stream().anyMatch(r -> "EXTERNAL_NOT_VERIFIED".equals(r.get("resolution"))), "external dependency is not falsely marked resolved");
            File installed = temp.resolve("installed").toFile(); installed.mkdir();
            byte[] different = nativeBytes.clone(); different[2000] = 1;
            Files.write(new File(installed, "libminecraftpe.so").toPath(), different);
            records.clear();
            ContractScanner.scan(Arrays.asList(base, split), Collections.singletonList("arm64-v8a"), installed,
                    cache, Collections.singleton(DexContracts.MAIN), sink, ignored -> {});
            check(records.stream().anyMatch(r -> "TEMPORARY_APK_ENTRY".equals(r.get("sourceKind"))), "same-size different installed ELF is not reused");
            Files.write(new File(installed, "libminecraftpe.so").toPath(), nativeBytes);
            records.clear();
            ContractScanner.scan(Arrays.asList(base, split), Collections.singletonList("arm64-v8a"), installed,
                    cache, Collections.singleton(DexContracts.MAIN), sink, ignored -> {});
            check(records.stream().anyMatch(r -> "MATCHED_INSTALLED_FILE".equals(r.get("sourceKind"))), "identical installed ELF used without extraction");
            result = ContractScanner.scan(Arrays.asList(base, split), Collections.singletonList("x86_64"), null,
                    cache, Collections.singleton(DexContracts.MAIN), sink, ignored -> {});
            check(!result.complete() && result.blockers.contains("NO_MATCHING_PROCESS_ABI"), "unsupported process ABI cannot pass");
            File broken = apk(temp, "broken.apk", new String[]{"lib/arm64-v8a/libminecraftpe.so"}, new byte[][]{new byte[128]});
            result = ContractScanner.scan(Arrays.asList(base, broken), Collections.singletonList("arm64-v8a"), null,
                    cache, Collections.singleton(DexContracts.MAIN), sink, ignored -> {});
            check(!result.complete() && result.errors == 1 && cache.list().length == 0, "invalid ELF produces partial report and removes temporary copy");
            Thread.currentThread().interrupt();
            try { rejects(() -> DexContracts.scan(mainDex, "cancelled.dex", Collections.emptySet(), new DexContracts.Index(), sink), "contract cancellation respected"); }
            finally { Thread.interrupted(); }
            System.out.println("ALL " + passed + " CONTRACT TESTS PASSED");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);
            }
        }
    }
}
