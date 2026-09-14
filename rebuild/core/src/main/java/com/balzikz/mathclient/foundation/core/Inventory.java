package com.balzikz.mathclient.foundation.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Read-only, streaming inventory. No extraction, loading, or guessed dependency order. */
public final class Inventory {
    private Inventory() {}
    public interface Sink { void item(String kind, Map<String, Object> fields) throws IOException; }
    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    public static String sha256(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return digest(in).hash;
        }
    }
    public static final class Digest {
        public final String hash;
        public final long bytes;
        public final byte[] header;
        Digest(String hash, long bytes, byte[] header) {
            this.hash = hash; this.bytes = bytes; this.header = header;
        }
    }
    public static Digest digest(InputStream in) throws IOException {
        MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        byte[] buffer = new byte[64 * 1024];
        ByteArrayOutputStream header = new ByteArrayOutputStream(64);
        long bytes = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
            if (n == 0) continue;
            hash.update(buffer, 0, n);
            int copy = Math.min(n, 64 - header.size());
            if (copy > 0) header.write(buffer, 0, copy);
            bytes += n;
        }
        return new Digest(hex(hash.digest()), bytes, header.toByteArray());
    }
    public static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(digits[(b & 255) >>> 4]).append(digits[b & 15]);
        return result.toString();
    }
    public static String elfHeader(byte[] h) {
        if (h.length < 20 || h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F')
            return "INVALID_OR_TRUNCATED";
        int bits = h[4] == 2 ? 64 : h[4] == 1 ? 32 : 0;
        if (bits == 0 || (h[5] != 1 && h[5] != 2) || h[6] != 1
                || h.length < (bits == 64 ? 64 : 52)) return "INVALID_OR_TRUNCATED";
        int machine = h[5] == 1 ? ((h[19] & 255) << 8) | (h[18] & 255)
                : ((h[18] & 255) << 8) | (h[19] & 255);
        return "ELF" + bits + (h[5] == 1 ? "_LE" : "_BE") + "_MACHINE_" + machine;
    }
    public static List<File> containers(String base, String[] splits) throws IOException {
        LinkedHashMap<String, File> result = new LinkedHashMap<>();
        if (base != null) add(result, base);
        if (splits != null) for (String split : splits) if (split != null) add(result, split);
        return new ArrayList<>(result.values());
    }
    private static void add(Map<String, File> result, String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        result.put(file.getPath(), file);
    }
    public static void scanApk(File file, Sink sink) throws IOException {
        long beforeSize = file.length(), beforeTime = file.lastModified();
        sink.item("apk", fields("path", file.getAbsolutePath(), "bytes", beforeSize,
                "sha256", sha256(file)));
        int assets = 0, dex = 0, libs = 0;
        try (ZipFile zip = new ZipFile(file)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (ZipEntry entry : entries) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.startsWith("assets/")) assets++;
                if (name.matches("classes[0-9]*\\.dex")) dex++;
                if (!name.matches("lib/[^/]+/[^/]+\\.so")) continue;
                Digest digest;
                try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
                    digest = digest(in);
                }
                if (entry.getSize() >= 0 && entry.getSize() != digest.bytes)
                    throw new IOException("Truncated ZIP entry: " + name);
                sink.item("native_library", fields("container", file.getAbsolutePath(),
                        "entry", name, "abi", name.split("/")[1], "bytes", digest.bytes,
                        "sha256", digest.hash, "elfHeader", elfHeader(digest.header),
                        "zipMethod", entry.getMethod(), "crc32", entry.getCrc()));
                libs++;
            }
        }
        if (file.length() != beforeSize || file.lastModified() != beforeTime)
            throw new IOException("APK changed during inventory: " + file.getName());
        sink.item("apk_summary", fields("path", file.getAbsolutePath(), "nativeLibraries", libs,
                "assetEntries", assets, "dexFiles", dex));
    }
    public static void scanNativeDirectory(File dir, Sink sink) throws IOException {
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".so"));
        if (files == null) throw new IOException("Native directory unavailable: " + dir);
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            Digest digest;
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) { digest = digest(in); }
            sink.item("installed_native_library", fields("path", file.getAbsolutePath(),
                    "bytes", digest.bytes, "sha256", digest.hash, "elfHeader", elfHeader(digest.header)));
        }
    }
    /** JSON strings keep diagnostics one-record-per-line even for embedded control characters. */
    public static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20 || Character.isSurrogate(c))
                        result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else result.append(c);
            }
        }
        return result.append('"').toString();
    }
    public static String json(Map<String, Object> fields) {
        StringJoiner out = new StringJoiner(",", "{", "}");
        fields.forEach((key, value) -> out.add(quote(key) + ":" + jsonValue(value)));
        return out.toString();
    }
    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Iterable<?>) {
            StringJoiner items = new StringJoiner(",", "[", "]");
            for (Object item : (Iterable<?>) value) items.add(jsonValue(item));
            return items.toString();
        }
        return quote(value.toString());
    }
}
