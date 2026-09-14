package com.balzikz.mathclient.foundation.core;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** App-private, content-addressed native snapshot. Never executes any installed-package code. */
public final class RuntimeImage {
    private static final long MAX_LIBRARY = 600L * 1024 * 1024;
    private static final long MAX_TOTAL = 1024L * 1024 * 1024;
    private static final int MAX_LIBRARIES = 256;
    private RuntimeImage() {}
    public static final class Result {
        public final String id, abi;
        public final File directory;
        public final boolean reused;
        public final Map<String, ElfFile.Result> libraries;
        public final NativePlan plan;
        Result(String id, String abi, File dir, boolean reused, Map<String, ElfFile.Result> libraries,
               NativePlan plan) {
            this.id = id; this.abi = abi; this.directory = dir; this.reused = reused;
            this.libraries = Collections.unmodifiableMap(libraries); this.plan = plan;
        }
    }
    private static final class Entry {
        final File apk;
        final String path, name, hash;
        final long bytes;
        Entry(File apk, String path, String name, Inventory.Digest digest) {
            this.apk = apk; this.path = path; this.name = name; this.hash = digest.hash; this.bytes = digest.bytes;
        }
    }
    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    private static File child(File parent, String name) throws IOException {
        File path = new File(parent, name).getAbsoluteFile();
        if (!path.getCanonicalFile().equals(path) || !path.getParentFile().equals(parent.getCanonicalFile()))
            throw new IOException("Runtime path is not a direct, non-symlink child");
        return path;
    }
    private static void removePartial(File dir) throws IOException {
        if (!dir.getName().matches("[.]partial-[a-f0-9-]{36}")) throw new IOException("Not a staging directory");
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("Cannot inspect staging directory");
        for (File file : files) {
            child(dir, file.getName());
            if (!file.isFile() || !file.delete()) throw new IOException("Cannot remove staging file");
        }
        if (!dir.delete()) throw new IOException("Cannot remove staging directory");
    }
    private static FileLock lock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another host is preparing runtime");
            return lock;
        } catch (OverlappingFileLockException error) {
            throw new IOException("Another host is preparing runtime", error);
        }
    }
    public static Result prepare(List<File> apks, List<String> preferredAbis, File root, String identity,
                                 String requiredLibrary, Inventory.Sink sink) throws IOException {
        if (apks.isEmpty() || apks.size() > 256) throw new IOException("Invalid APK container count");
        if (!requiredLibrary.matches("[A-Za-z0-9_+.-]+[.]so")) throw new IOException("Invalid required library");
        // The trusted Android data directory may itself use /data/user/0 symlinks.
        root = root.getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create private runtime directory");
        try (RandomAccessFile file = new RandomAccessFile(child(root, "prepare.lock"), "rw");
             FileChannel channel = file.getChannel(); FileLock held = lock(channel)) {
            File[] stale = root.listFiles(f -> f.getName().matches("[.]partial-[a-f0-9-]{36}"));
            if (stale == null) throw new IOException("Cannot list private runtime directory");
            for (File partial : stale) { child(root, partial.getName()); removePartial(partial); }
            return build(apks, preferredAbis, root, identity, requiredLibrary, sink);
        }
    }
    private static Result build(List<File> apks, List<String> preferredAbis, File root, String identity,
                                String requiredLibrary, Inventory.Sink sink) throws IOException {
        Map<File, String> sources = new LinkedHashMap<>();
        Set<String> supported = new TreeSet<>();
        for (File apk : apks) {
            cancelled();
            if (!apk.isFile()) throw new IOException("APK unavailable: " + apk);
            String hash = Inventory.sha256(apk);
            if (sources.put(apk.getCanonicalFile(), hash) != null) throw new IOException("Duplicate APK container");
            sink.item("host_apk", Inventory.fields("path", apk.getCanonicalPath(), "sha256", hash, "bytes", apk.length()));
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    cancelled(); String name = entries.nextElement().getName();
                    if (name.matches("lib/[^/]+/" + java.util.regex.Pattern.quote(requiredLibrary)))
                        supported.add(name.split("/")[1]);
                }
            }
        }
        String abi = null;
        for (String candidate : preferredAbis) if (supported.contains(candidate)) { abi = candidate; break; }
        if (abi == null || !(abi.equals("arm64-v8a") || abi.equals("x86_64")))
            throw new IOException("No supported 64-bit process ABI containing " + requiredLibrary);
        Map<String, Entry> entries = new TreeMap<>();
        long total = 0;
        for (File apk : sources.keySet()) try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                cancelled(); ZipEntry entry = all.nextElement(); String path = entry.getName();
                if (entry.isDirectory() || !path.startsWith("lib/" + abi + "/") || !path.endsWith(".so")) continue;
                String name = path.substring(("lib/" + abi + "/").length());
                if (!name.matches("[A-Za-z0-9_+.-]+[.]so") || name.startsWith("."))
                    throw new IOException("Unsafe native entry: " + path);
                if (entry.getSize() < 52 || entry.getSize() > MAX_LIBRARY) throw new IOException("Native entry size outside limit");
                Inventory.Digest digest;
                try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
                    digest = Inventory.digest(new LimitedInputStream(in, entry.getSize()));
                }
                if (digest.bytes != entry.getSize()) throw new IOException("Truncated native entry");
                String expectedHeader = abi.equals("arm64-v8a") ? "ELF64_LE_MACHINE_183" : "ELF64_LE_MACHINE_62";
                if (!Inventory.elfHeader(digest.header).equals(expectedHeader)) throw new IOException("Native entry ABI mismatch: " + path);
                Entry previous = entries.putIfAbsent(name, new Entry(apk, path, name, digest));
                if (previous != null && (!previous.hash.equals(digest.hash) || previous.bytes != digest.bytes))
                    throw new IOException("Conflicting native libraries across APKs: " + name);
                if (previous == null) total += digest.bytes;
                if (entries.size() > MAX_LIBRARIES || total > MAX_TOTAL) throw new IOException("Runtime exceeds size/count limit");
            }
        }
        StringBuilder key = new StringBuilder(identity).append('\n').append(abi).append('\n').append(requiredLibrary).append('\n');
        sources.forEach((file, hash) -> key.append(file.getPath()).append('\n').append(hash).append('\n'));
        String id = Inventory.digest(new ByteArrayInputStream(key.toString().getBytes(StandardCharsets.UTF_8))).hash;
        File ready = child(root, id);
        boolean reused = ready.exists();
        File staging = reused ? null : child(root, ".partial-" + UUID.randomUUID());
        File directory = reused ? ready : staging;
        if (reused && !directory.isDirectory()) throw new IOException("Invalid cached runtime");
        if (!reused) {
            long available = root.getUsableSpace();
            if (available > 0 && available < total + 16L * 1024 * 1024) throw new IOException("Not enough space for private runtime");
            if (!directory.mkdir()) throw new IOException("Cannot create runtime staging directory");
        }
        try {
            Map<String, ElfFile.Result> libraries = new TreeMap<>();
            for (Entry entry : entries.values()) {
                cancelled(); File destination = child(directory, entry.name);
                if (!reused) copy(entry, destination);
                if (!destination.isFile() || destination.length() != entry.bytes || !Inventory.sha256(destination).equals(entry.hash))
                    throw new IOException("Runtime SHA mismatch: " + entry.name);
                ElfFile.Result elf = ElfFile.inspect(destination);
                libraries.put(entry.name, elf);
                sink.item("host_native_library", Inventory.fields("name", entry.name, "source", entry.apk.getPath(),
                        "entry", entry.path, "sha256", entry.hash, "bytes", entry.bytes, "needed", elf.needed,
                        "soname", elf.soname, "symbolsComplete", elf.symbolsComplete, "reused", reused, "loaded", false));
            }
            if (!libraries.containsKey(requiredLibrary)) throw new IOException("Required native library missing");
            String[] actualFiles = directory.list();
            if (actualFiles == null || !new TreeSet<>(Arrays.asList(actualFiles)).equals(entries.keySet()))
                throw new IOException("Unexpected files in runtime directory");
            NativePlan plan = NativePlan.inspect(libraries);
            sink.item("host_dependency_plan", Inventory.fields("packagedOrder", plan.order, "cycleNodes", plan.cycles,
                    "externalNotVerified", plan.external, "executable", false, "jniLoadOwner", "ADAPTER_REQUIRED"));
            verifySources(sources);
            if (!reused) {
                // The complete directory becomes visible at once; partial images are never reused.
                Files.move(staging.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
                staging = null;
            }
            sink.item("host_runtime_prepared", Inventory.fields("id", id, "abi", abi, "libraries", entries.size(),
                    "bytes", total, "reused", reused, "minecraftLoaded", false));
            return new Result(id, abi, ready, reused, libraries, plan);
        } finally {
            if (staging != null && staging.exists()) removePartial(staging);
        }
    }
    private static void verifySources(Map<File, String> sources) throws IOException {
        for (Map.Entry<File, String> source : sources.entrySet())
            if (!Inventory.sha256(source.getKey()).equals(source.getValue()))
                throw new IOException("APK changed during runtime preparation");
    }
    private static void copy(Entry entry, File destination) throws IOException {
        if (!destination.createNewFile()) throw new IOException("Runtime file already exists");
        try (ZipFile zip = new ZipFile(entry.apk);
             InputStream in = new BufferedInputStream(zip.getInputStream(zip.getEntry(entry.path)));
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] bytes = new byte[65536]; long total = 0; int n;
            while ((n = in.read(bytes)) != -1) {
                cancelled(); total += n;
                if (total > entry.bytes) throw new IOException("Native entry grew during extraction");
                out.write(bytes, 0, n);
            }
            if (total != entry.bytes) throw new IOException("Native entry shrank during extraction");
            out.getFD().sync();
        }
        if (!destination.setReadOnly()) throw new IOException("Cannot seal runtime library");
    }
    /** Bound even a corrupt ZIP stream before digesting it. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;
        LimitedInputStream(InputStream in, long limit) { super(in); this.limit = limit; }
        @Override public int read() throws IOException {
            int b = in.read(); if (b >= 0 && ++count > limit) throw new IOException("ZIP stream exceeds declared size"); return b;
        }
        @Override public int read(byte[] bytes, int off, int len) throws IOException {
            int n = in.read(bytes, off, (int)Math.min(len, Math.max(1, limit - count + 1)));
            if (n > 0 && (count += n) > limit) throw new IOException("ZIP stream exceeds declared size"); return n;
        }
    }
}
