package com.balzikz.mathclient.foundation.contract;

import com.balzikz.mathclient.foundation.core.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Exact installed-file contract inventory. No target code is executed or included in exports. */
public final class ContractScanner {
    private ContractScanner() {}
    public interface Progress { void update(String message); }
    public static final class Summary {
        public String abi;
        public int errors, nativeLibraries;
        public final DexContracts.Index dex = new DexContracts.Index();
        public final Map<String, ElfFile.Result> libraries = new LinkedHashMap<>();
        public final List<String> blockers = new ArrayList<>();
        public boolean complete() { return blockers.isEmpty(); }
        public String text() {
            return "Чтение контракта: " + (complete() ? "завершено" : "неполное")
                    + "\nABI: " + (abi == null ? "не найден" : abi)
                    + "\nDEX: " + dex.dexFiles + "; JNI-методов: " + dex.nativeMethods.size()
                    + "; библиотек: " + nativeLibraries
                    + (blockers.isEmpty() ? "" : "\nТребует внимания: " + String.join(", ", blockers))
                    + "\nИгровой адаптер ещё не реализован.";
        }
    }
    private static boolean library(String name) { return name.matches("lib/[^/]+/[^/]+\\.so"); }
    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    static byte[] readDex(InputStream in, long size) throws IOException {
        if (size < 112 || size > 64L * 1024 * 1024) throw new IOException("DEX size outside 112 B–64 MiB limit: " + size);
        byte[] bytes = new byte[(int) size];
        int position = 0;
        while (position < bytes.length) {
            cancelled(); int n = in.read(bytes, position, Math.min(65536, bytes.length - position));
            if (n < 0) throw new EOFException("Truncated DEX entry");
            position += n;
        }
        if (in.read() != -1) throw new IOException("DEX entry larger than declared size");
        return bytes;
    }
    private static void copyElf(InputStream in, File file, long size) throws IOException {
        if (size < 52 || size > 600L * 1024 * 1024) throw new IOException("ELF exceeds 600 MiB limit");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            byte[] buffer = new byte[65536]; long total = 0; int n;
            while ((n = in.read(buffer)) != -1) {
                cancelled(); total += n;
                if (total > size) throw new IOException("ELF entry exceeds declared size");
                out.write(buffer, 0, n);
            }
            if (total != size) throw new EOFException("Truncated ELF entry");
        }
    }
    public static Summary scan(List<File> apks, List<String> preferredAbis, File nativeDirectory,
                               File scratch, Set<String> roots, Inventory.Sink sink, Progress progress) throws IOException {
        Summary result = new Summary();
        if (!scratch.isDirectory() && !scratch.mkdirs()) throw new IOException("Cannot create contract scratch directory");
        Set<String> abis = new LinkedHashSet<>();
        for (File apk : apks) try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                cancelled(); String name = entries.nextElement().getName();
                if (library(name)) abis.add(name.split("/")[1]);
            }
        }
        for (String abi : preferredAbis) if (abis.contains(abi)) { result.abi = abi; break; }
        sink.item("contract_abis", Inventory.fields("available", abis, "processPreferences", preferredAbis, "selected", result.abi));
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (File apk : apks) {
            cancelled(); progress.update("APK: " + apk.getName());
            long size = apk.length(), time = apk.lastModified();
            sink.item("contract_apk", Inventory.fields("path", apk.getAbsolutePath(), "bytes", size, "sha256", Inventory.sha256(apk)));
            try (ZipFile zip = new ZipFile(apk)) {
                List<? extends ZipEntry> entries = Collections.list(zip.entries());
                entries.sort(Comparator.comparing(ZipEntry::getName));
                for (ZipEntry entry : entries) {
                    cancelled(); String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    String source = apk.getName() + "!/" + name;
                    if (name.matches("classes[0-9]*\\.dex")) {
                        progress.update("Java/JNI: " + source);
                        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
                            DexContracts.scan(readDex(in, entry.getSize()), source, roots, result.dex, sink);
                        } catch (InterruptedIOException stop) { throw stop; }
                        catch (IOException error) { failure(result, sink, source, error); }
                    } else if (library(name) && name.split("/")[1].equals(result.abi)) {
                        progress.update("ELF: " + name.substring(name.lastIndexOf('/') + 1));
                        try { inspectLibrary(zip, entry, source, nativeDirectory, scratch, result, fingerprints, sink); }
                        catch (InterruptedIOException stop) { throw stop; }
                        catch (IOException error) { failure(result, sink, source, error); }
                    }
                }
            }
            if (size != apk.length() || time != apk.lastModified()) {
                failure(result, sink, apk.getPath(), new IOException("APK changed during scan"));
            }
        }
        if (result.abi == null) result.blockers.add("NO_MATCHING_PROCESS_ABI");
        if (result.errors > 0) result.blockers.add("FILE_READ_ERRORS");
        if (!result.dex.parents.containsKey(DexContracts.MAIN)) result.blockers.add("MAIN_ACTIVITY_NOT_FOUND");
        if (result.dex.duplicateClasses > 0) result.blockers.add("DUPLICATE_JAVA_CLASSES");
        if (!result.libraries.containsKey("libminecraftpe.so")) result.blockers.add("MINECRAFT_ELF_NOT_READ");
        for (Map.Entry<String, ElfFile.Result> lib : result.libraries.entrySet()) {
            if (!lib.getValue().symbolsComplete) result.blockers.add("SYMBOLS_INCOMPLETE:" + lib.getKey());
            for (String dependency : lib.getValue().needed)
                sink.item("native_dependency", Inventory.fields("library", lib.getKey(), "needed", dependency,
                        "resolution", result.libraries.containsKey(dependency) ? "PACKAGED" : "EXTERNAL_NOT_VERIFIED"));
        }
        for (String root : roots) sink.item("java_hierarchy", Inventory.fields("root", root,
                "chain", result.dex.hierarchy(root), "definitionFound", result.dex.parents.containsKey(root)));
        sink.item("contract_summary", Inventory.fields("schema", 1, "metadataComplete", result.complete(),
                "blockers", result.blockers, "errors", result.errors, "dexFiles", result.dex.dexFiles,
                "javaClasses", result.dex.classes, "nativeMethods", result.dex.nativeMethods.size(),
                "nativeLibraries", result.nativeLibraries, "abi", result.abi,
                "hostAdapter", "NOT_IMPLEMENTED", "jniRegistrationVerified", false, "minecraftLoaded", false));
        return result;
    }
    private static void failure(Summary result, Inventory.Sink sink, String source, IOException error) throws IOException {
        result.errors++;
        sink.item("contract_error", Inventory.fields("source", source, "error", error.toString()));
    }
    private static void inspectLibrary(ZipFile zip, ZipEntry entry, String source, File nativeDirectory, File scratch,
                                       Summary result, Map<String, String> fingerprints, Inventory.Sink sink) throws IOException {
        String basename = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
        Inventory.Digest digest;
        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) { digest = Inventory.digest(in); }
        if (digest.bytes != entry.getSize()) throw new IOException("ELF entry size mismatch");
        String previous = fingerprints.putIfAbsent(basename, digest.hash);
        if (previous != null) {
            sink.item("duplicate_native_library", Inventory.fields("source", source, "name", basename, "identical", previous.equals(digest.hash)));
            if (!previous.equals(digest.hash)) throw new IOException("Conflicting native library in multiple APKs: " + basename);
            return;
        }
        File installed = nativeDirectory == null ? null : new File(nativeDirectory, basename);
        boolean installedMatches = installed != null && installed.isFile() && installed.canRead()
                && installed.length() == digest.bytes && Inventory.sha256(installed).equals(digest.hash);
        File temporary = null, target = installed;
        try {
            if (!installedMatches) {
                temporary = File.createTempFile("elf-", ".tmp", scratch); target = temporary;
                try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) { copyElf(in, target, entry.getSize()); }
                if (!Inventory.sha256(target).equals(digest.hash)) throw new IOException("ELF changed during extraction");
            }
            ElfFile.Result elf = ElfFile.inspect(target);
            int expectedMachine = "arm64-v8a".equals(result.abi) ? 183 : "x86_64".equals(result.abi) ? 62
                    : "armeabi-v7a".equals(result.abi) ? 40 : "x86".equals(result.abi) ? 3 : -1;
            boolean expected64 = "arm64-v8a".equals(result.abi) || "x86_64".equals(result.abi);
            if (expectedMachine != elf.machine || elf.bits != (expected64 ? 64 : 32))
                throw new IOException("ELF class/machine does not match APK ABI");
            result.libraries.put(basename, elf); result.nativeLibraries++;
            sink.item("elf_contract", Inventory.fields("source", source, "name", basename, "sha256", digest.hash,
                    "bytes", digest.bytes, "bits", elf.bits, "machine", elf.machine, "soname", elf.soname,
                    "needed", elf.needed, "runpath", elf.runpath, "minLoadAlignment", elf.minLoadAlignment,
                    "symbolTable", elf.symbolSource, "dynamicSymbolCount", elf.symbolCount,
                    "symbolsComplete", elf.symbolsComplete, "exports", elf.exports,
                    "sourceKind", installedMatches ? "MATCHED_INSTALLED_FILE" : "TEMPORARY_APK_ENTRY"));
        } finally {
            if (temporary != null && !temporary.delete()) throw new IOException("Cannot remove temporary ELF copy");
        }
    }
}
