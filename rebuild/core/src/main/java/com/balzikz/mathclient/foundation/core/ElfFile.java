package com.balzikz.mathclient.foundation.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads ELF metadata without loading code. All offsets are checked against the file. */
public final class ElfFile {
    private ElfFile() {}
    private static final long MAX_SYMBOLS = 2_000_000;
    public static final class Result {
        public int bits, machine;
        public long minLoadAlignment = Long.MAX_VALUE, symbolCount;
        public String soname, runpath, symbolSource = "UNAVAILABLE";
        public boolean symbolsComplete;
        public final List<String> needed = new ArrayList<>();
        public final Set<String> exports = new TreeSet<>();
    }
    private static final class Segment {
        long offset, address, size;
        Segment(long o, long a, long s) { offset = o; address = a; size = s; }
    }
    /** Two readers are used so symbol and string-table scans have separate bounded caches. */
    private static final class Reader implements Closeable {
        final RandomAccessFile file;
        final long length;
        final byte[] cache = new byte[65536];
        long start = -1;
        int count;
        boolean little;
        Reader(File path) throws IOException { file = new RandomAccessFile(path, "r"); length = file.length(); }
        void range(long offset, long size) throws IOException {
            if (offset < 0 || size < 0 || offset > length || size > length - offset)
                throw new IOException("ELF range outside file: " + offset + "+" + size);
        }
        int byteAt(long offset) throws IOException {
            range(offset, 1);
            if (start < 0 || offset < start || offset >= start + count) {
                start = offset; file.seek(start); count = file.read(cache);
                if (count <= 0) throw new EOFException("Truncated ELF");
            }
            return cache[(int) (offset - start)] & 255;
        }
        long number(long offset, int bytes) throws IOException {
            range(offset, bytes);
            long value = 0;
            for (int i = bytes - 1; i >= 0; i--)
                value = (value << 8) | byteAt(offset + (little ? i : bytes - 1 - i));
            if (value < 0) throw new IOException("ELF unsigned value exceeds supported file range");
            return value;
        }
        String string(long table, long size, long index) throws IOException {
            if (index < 0 || index >= size) throw new IOException("ELF string index outside table");
            range(table, size);
            ByteArrayOutputStream value = new ByteArrayOutputStream();
            for (long i = index; i < size && value.size() < 32768; i++) {
                int b = byteAt(table + i);
                if (b == 0) return new String(value.toByteArray(), StandardCharsets.UTF_8);
                value.write(b);
            }
            throw new IOException("ELF unterminated or oversized symbol name");
        }
        @Override public void close() throws IOException { file.close(); }
    }
    private static long mapped(List<Segment> loads, long address, long size) throws IOException {
        for (Segment segment : loads) {
            long delta = address - segment.address;
            if (delta >= 0 && delta <= segment.size && size <= segment.size - delta)
                return segment.offset + delta;
        }
        throw new IOException("ELF virtual address is not file-backed: " + address);
    }
    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    public static Result inspect(File path) throws IOException {
        try (Reader r = new Reader(path); Reader strings = new Reader(path)) {
            if (r.byteAt(0) != 127 || r.byteAt(1) != 'E' || r.byteAt(2) != 'L' || r.byteAt(3) != 'F')
                throw new IOException("Not an ELF file");
            int cls = r.byteAt(4), data = r.byteAt(5);
            if ((cls != 1 && cls != 2) || (data != 1 && data != 2) || r.byteAt(6) != 1)
                throw new IOException("Unsupported ELF class, byte order or version");
            r.little = strings.little = data == 1;
            boolean wide = cls == 2;
            int word = wide ? 8 : 4, symSize = wide ? 24 : 16;
            r.range(0, wide ? 64 : 52);
            Result result = new Result(); result.bits = wide ? 64 : 32;
            result.machine = (int) r.number(18, 2);
            long phoff = r.number(wide ? 32 : 28, word), shoff = r.number(wide ? 40 : 32, word);
            long phsize = r.number(wide ? 54 : 42, 2), phcount = r.number(wide ? 56 : 44, 2);
            long shsize = r.number(wide ? 58 : 46, 2), shcount = r.number(wide ? 60 : 48, 2);
            if (phcount == 65535 || (shoff != 0 && shcount == 0))
                throw new IOException("Extended ELF header counts are not supported");
            if (phcount > 4096 || (phcount > 0 && phsize < (wide ? 56 : 32)))
                throw new IOException("Invalid ELF program headers");
            r.range(phoff, phsize * phcount);
            List<Segment> loads = new ArrayList<>();
            long dynamic = -1, dynamicSize = 0;
            for (int i = 0; i < phcount; i++) {
                long p = phoff + i * phsize, type = r.number(p, 4);
                long offset = r.number(p + (wide ? 8 : 4), word);
                long address = r.number(p + (wide ? 16 : 8), word);
                long size = r.number(p + (wide ? 32 : 16), word);
                if (type == 1 || type == 2) r.range(offset, size);
                if (type == 1) {
                    loads.add(new Segment(offset, address, size));
                    result.minLoadAlignment = Math.min(result.minLoadAlignment, r.number(p + (wide ? 48 : 28), word));
                }
                if (type == 2) {
                    if (dynamic >= 0) throw new IOException("Multiple ELF dynamic segments");
                    dynamic = offset; dynamicSize = size;
                }
            }
            if (dynamic < 0) throw new IOException("ELF has no dynamic segment");
            Map<Long, Long> tags = new HashMap<>(); List<Long> needed = new ArrayList<>();
            boolean terminated = false;
            if (dynamicSize / (2 * word) > 65536) throw new IOException("Oversized dynamic table");
            for (long p = dynamic; p + 2L * word <= dynamic + dynamicSize; p += 2L * word) {
                long tag = r.number(p, word), value = r.number(p + word, word);
                if (tag == 0) { terminated = true; break; }
                if (tag == 1) needed.add(value); else tags.put(tag, value);
            }
            if (!terminated || !tags.containsKey(5L) || !tags.containsKey(10L))
                throw new IOException("Incomplete ELF dynamic string table");
            long strSize = tags.get(10L), str = mapped(loads, tags.get(5L), strSize);
            for (long index : needed) result.needed.add(strings.string(str, strSize, index));
            if (tags.containsKey(14L)) result.soname = strings.string(str, strSize, tags.get(14L));
            if (tags.containsKey(29L)) result.runpath = strings.string(str, strSize, tags.get(29L));
            else if (tags.containsKey(15L)) result.runpath = strings.string(str, strSize, tags.get(15L));
            long sym = -1, count = -1;
            if (tags.containsKey(6L)) {
                if (tags.getOrDefault(11L, (long) symSize) != symSize) throw new IOException("Invalid DT_SYMENT");
                sym = mapped(loads, tags.get(6L), symSize);
            }
            if (shcount > 0) {
                if (shsize < (wide ? 64 : 40)) throw new IOException("Invalid ELF section headers");
                r.range(shoff, shsize * shcount);
                for (int i = 0; i < shcount; i++) {
                    long p = shoff + i * shsize;
                    if (r.number(p + 4, 4) != 11) continue; // SHT_DYNSYM, never SHT_SYMTAB
                    long offset = r.number(p + (wide ? 24 : 16), word);
                    if (offset != sym) continue;
                    long bytes = r.number(p + (wide ? 32 : 20), word);
                    if (r.number(p + (wide ? 56 : 36), word) != symSize || bytes % symSize != 0)
                        throw new IOException("Invalid dynamic symbol section");
                    r.range(offset, bytes); count = bytes / symSize; result.symbolSource = "SHT_DYNSYM"; break;
                }
            }
            if (sym >= 0 && count < 0 && tags.containsKey(4L)) {
                long hash = mapped(loads, tags.get(4L), 8);
                count = r.number(hash + 4, 4); result.symbolSource = "DT_HASH";
            }
            if (sym >= 0 && count < 0 && tags.containsKey(0x6ffffef5L)) {
                long hash = mapped(loads, tags.get(0x6ffffef5L), 16);
                long buckets = r.number(hash, 4), first = r.number(hash + 4, 4), bloom = r.number(hash + 8, 4);
                if (buckets == 0 || buckets > MAX_SYMBOLS || bloom == 0 || bloom > MAX_SYMBOLS || first > MAX_SYMBOLS)
                    throw new IOException("Invalid GNU hash table");
                long bucketAddress = tags.get(0x6ffffef5L) + 16 + bloom * word;
                long bucketTable = mapped(loads, bucketAddress, buckets * 4), last = 0;
                for (long i = 0; i < buckets; i++) {
                    if ((i & 4095) == 0) cancelled();
                    long bucket = r.number(bucketTable + i * 4, 4);
                    if (bucket != 0 && (bucket < first || bucket >= MAX_SYMBOLS)) throw new IOException("Invalid GNU hash bucket");
                    last = Math.max(last, bucket);
                }
                count = first;
                if (last != 0) {
                    long chains = bucketAddress + buckets * 4;
                    for (long i = last; ; i++) {
                        if (i >= MAX_SYMBOLS) throw new IOException("Unterminated GNU hash chain");
                        if ((i & 4095) == 0) cancelled();
                        long entry = mapped(loads, chains + (i - first) * 4, 4);
                        if ((r.number(entry, 4) & 1) != 0) { count = i + 1; break; }
                    }
                }
                result.symbolSource = "DT_GNU_HASH";
            }
            if (count < 0 || sym < 0) return result; // Absence of a readable table is not absence of exports.
            if (count > MAX_SYMBOLS) throw new IOException("ELF symbol count exceeds limit");
            r.range(sym, count * symSize);
            for (long i = 0; i < count; i++) {
                if ((i & 4095) == 0) cancelled();
                long p = sym + i * symSize;
                int info = r.byteAt(p + (wide ? 4 : 12)), other = r.byteAt(p + (wide ? 5 : 13));
                long section = r.number(p + (wide ? 6 : 14), 2);
                int binding = info >>> 4, visibility = other & 3, type = info & 15;
                if (section == 0 || (binding != 1 && binding != 2) || (visibility != 0 && visibility != 3)
                        || (type != 0 && type != 2 && type != 10)) continue;
                String name = strings.string(str, strSize, r.number(p, 4));
                if (name.equals("JNI_OnLoad") || name.equals("JNI_OnUnload") || name.equals("android_main")
                        || name.startsWith("Java_") || name.startsWith("ANativeActivity_") || name.startsWith("GameActivity_"))
                    result.exports.add(name);
            }
            result.symbolCount = count; result.symbolsComplete = true;
            return result;
        }
    }
}
