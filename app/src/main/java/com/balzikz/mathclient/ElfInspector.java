package com.balzikz.mathclient;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ElfInspector {

    private static final String TARGET_PACKAGE = "com.mojang.minecraftpe";
    private static final String ELF_ENTRY = "lib/arm64-v8a/libminecraftpe.so";
    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final int PT_NOTE = 4;
    private static final long DT_NULL = 0;
    private static final long DT_NEEDED = 1;
    private static final long DT_STRTAB = 5;
    private static final long DT_STRSZ = 10;
    private static final int EM_AARCH64 = 183;
    private static final int MAX_DYNSTR = 32 * 1024 * 1024;

    private ElfInspector() {
    }

    public static String create(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(TARGET_PACKAGE, 0);
            ZipEntryAccess entry = new ZipEntryAccess(info.sourceDir, ELF_ENTRY);
            return inspect(entry);
        } catch (Exception error) {
            return "ELF inspection failed: "
                    + error.getClass().getSimpleName()
                    + ": "
                    + error.getMessage();
        }
    }

    private static String inspect(ZipEntryAccess entry) throws Exception {
        byte[] headerBytes = entry.read(0, 64);
        ByteBuffer header = littleEndian(headerBytes);

        if ((header.get(0) & 0xff) != 0x7f
                || header.get(1) != 'E'
                || header.get(2) != 'L'
                || header.get(3) != 'F') {
            throw new IllegalStateException("libminecraftpe.so is not ELF");
        }

        int elfClass = header.get(4) & 0xff;
        int dataEncoding = header.get(5) & 0xff;
        if (elfClass != 2 || dataEncoding != 1) {
            throw new IllegalStateException("Only ELF64 little-endian is supported");
        }

        int machine = unsignedShort(header.getShort(18));
        long entryPoint = header.getLong(24);
        long programOffset = header.getLong(32);
        int programEntrySize = unsignedShort(header.getShort(54));
        int programCount = unsignedShort(header.getShort(56));

        int tableSize = Math.multiplyExact(programEntrySize, programCount);
        byte[] programBytes = entry.read(programOffset, tableSize);
        List<Segment> segments = parseSegments(programBytes, programEntrySize, programCount);

        Segment dynamic = firstSegment(segments, PT_DYNAMIC);
        if (dynamic == null) {
            throw new IllegalStateException("PT_DYNAMIC not found");
        }

        DynamicInfo dynamicInfo = parseDynamic(entry, dynamic);
        Segment stringSegment = segmentForAddress(segments, dynamicInfo.stringAddress);
        if (stringSegment == null) {
            throw new IllegalStateException("Dynamic string table is outside PT_LOAD");
        }

        long stringOffset = stringSegment.offset
                + (dynamicInfo.stringAddress - stringSegment.virtualAddress);
        int stringSize = Math.toIntExact(Math.min(dynamicInfo.stringSize, MAX_DYNSTR));
        byte[] dynamicStrings = entry.read(stringOffset, stringSize);

        List<String> needed = new ArrayList<>();
        for (Long nameOffset : dynamicInfo.neededOffsets) {
            needed.add(cString(dynamicStrings, Math.toIntExact(nameOffset)));
        }

        Set<String> graphicsSymbols = detectGraphicsSymbols(dynamicStrings);
        String buildId = findBuildId(entry, segments);
        String graphicsEvidence = graphicsEvidence(needed, graphicsSymbols);

        StringBuilder report = new StringBuilder();
        report.append("ELF file: libminecraftpe.so\n");
        report.append("ELF size: ").append(formatBytes(entry.size())).append('\n');
        report.append("Class: ELF64\n");
        report.append("Endianness: little-endian\n");
        report.append("Machine: ")
                .append(machine == EM_AARCH64 ? "AArch64" : machine)
                .append(" (").append(machine).append(")\n");
        report.append("Entry point: 0x")
                .append(Long.toHexString(entryPoint)).append('\n');
        report.append("Program headers: ").append(programCount).append('\n');
        report.append("GNU Build ID: ").append(buildId).append('\n');
        report.append("SHA-256: ").append(entry.sha256()).append('\n');
        report.append("Dynamic string table: ")
                .append(formatBytes(dynamicInfo.stringSize)).append('\n');

        report.append("DT_NEEDED libraries: ").append(needed.size()).append('\n');
        for (String library : needed) {
            report.append("  - ").append(library).append('\n');
        }

        report.append("Graphics symbols found: ").append(graphicsSymbols.size()).append('\n');
        for (String symbol : graphicsSymbols) {
            report.append("  - ").append(symbol).append('\n');
        }
        report.append("Static graphics evidence: ").append(graphicsEvidence).append('\n');
        report.append("Active runtime API: requires in-process observation");
        return report.toString();
    }

    private static List<Segment> parseSegments(byte[] bytes, int size, int count) {
        List<Segment> segments = new ArrayList<>();
        ByteBuffer buffer = littleEndian(bytes);
        for (int index = 0; index < count; index++) {
            int base = index * size;
            int type = buffer.getInt(base);
            long offset = buffer.getLong(base + 8);
            long virtualAddress = buffer.getLong(base + 16);
            long fileSize = buffer.getLong(base + 32);
            long memorySize = buffer.getLong(base + 40);
            segments.add(new Segment(type, offset, virtualAddress, fileSize, memorySize));
        }
        return segments;
    }

    private static DynamicInfo parseDynamic(ZipEntryAccess entry, Segment dynamic) throws Exception {
        int length = Math.toIntExact(dynamic.fileSize);
        ByteBuffer buffer = littleEndian(entry.read(dynamic.offset, length));
        List<Long> needed = new ArrayList<>();
        long stringAddress = -1;
        long stringSize = -1;

        for (int offset = 0; offset + 16 <= length; offset += 16) {
            long tag = buffer.getLong(offset);
            long value = buffer.getLong(offset + 8);
            if (tag == DT_NULL) {
                break;
            }
            if (tag == DT_NEEDED) {
                needed.add(value);
            } else if (tag == DT_STRTAB) {
                stringAddress = value;
            } else if (tag == DT_STRSZ) {
                stringSize = value;
            }
        }

        if (stringAddress < 0 || stringSize <= 0) {
            throw new IllegalStateException("Incomplete dynamic string table metadata");
        }
        return new DynamicInfo(stringAddress, stringSize, needed);
    }

    private static String findBuildId(ZipEntryAccess entry, List<Segment> segments) throws Exception {
        for (Segment segment : segments) {
            if (segment.type != PT_NOTE || segment.fileSize <= 0 || segment.fileSize > 1024 * 1024) {
                continue;
            }

            byte[] bytes = entry.read(segment.offset, Math.toIntExact(segment.fileSize));
            ByteBuffer buffer = littleEndian(bytes);
            int offset = 0;
            while (offset + 12 <= bytes.length) {
                int nameSize = buffer.getInt(offset);
                int descSize = buffer.getInt(offset + 4);
                int type = buffer.getInt(offset + 8);
                offset += 12;

                if (nameSize < 0 || descSize < 0 || offset + nameSize > bytes.length) {
                    break;
                }
                String name = new String(bytes, offset, Math.max(0, nameSize - 1), StandardCharsets.US_ASCII);
                offset += align4(nameSize);
                if (offset + descSize > bytes.length) {
                    break;
                }

                if (type == 3 && "GNU".equals(name)) {
                    return hex(bytes, offset, descSize);
                }
                offset += align4(descSize);
            }
        }
        return "not found";
    }

    private static Set<String> detectGraphicsSymbols(byte[] strings) {
        String table = new String(strings, StandardCharsets.ISO_8859_1);
        String[] candidates = {
                "eglSwapBuffers",
                "eglGetProcAddress",
                "eglCreateContext",
                "glDrawElements",
                "glDrawArrays",
                "vkQueuePresentKHR",
                "vkCreateInstance",
                "vkCreateDevice",
                "vkGetInstanceProcAddr",
                "vkCmdDraw",
                "ANativeWindow_fromSurface"
        };

        Set<String> found = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (table.contains(candidate)) {
                found.add(candidate);
            }
        }
        return found;
    }

    private static String graphicsEvidence(List<String> needed, Set<String> symbols) {
        boolean vulkan = containsIgnoreCase(needed, "vulkan") || startsWith(symbols, "vk");
        boolean gles = containsIgnoreCase(needed, "egl")
                || containsIgnoreCase(needed, "gles")
                || startsWith(symbols, "egl")
                || startsWith(symbols, "gl");

        if (vulkan && gles) return "Vulkan and OpenGL ES paths are both present";
        if (vulkan) return "Vulkan path present";
        if (gles) return "OpenGL ES / EGL path present";
        return "no known graphics imports found";
    }

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    private static boolean startsWith(Set<String> values, String prefix) {
        for (String value : values) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Segment firstSegment(List<Segment> segments, int type) {
        for (Segment segment : segments) {
            if (segment.type == type) return segment;
        }
        return null;
    }

    private static Segment segmentForAddress(List<Segment> segments, long address) {
        for (Segment segment : segments) {
            if (segment.type == PT_LOAD
                    && address >= segment.virtualAddress
                    && address < segment.virtualAddress + segment.memorySize) {
                return segment;
            }
        }
        return null;
    }

    private static String cString(byte[] bytes, int offset) {
        if (offset < 0 || offset >= bytes.length) return "<invalid-string-offset>";
        int end = offset;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static ByteBuffer littleEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int unsignedShort(short value) {
        return value & 0xffff;
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    private static String hex(byte[] bytes, int offset, int length) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < length; index++) {
            result.append(String.format(Locale.ROOT, "%02x", bytes[offset + index]));
        }
        return result.toString();
    }

    private static String formatBytes(long bytes) {
        double mib = bytes / 1024.0 / 1024.0;
        return String.format(Locale.ROOT, "%.1f MiB", mib);
    }

    private static final class Segment {
        private final int type;
        private final long offset;
        private final long virtualAddress;
        private final long fileSize;
        private final long memorySize;

        private Segment(int type, long offset, long virtualAddress, long fileSize, long memorySize) {
            this.type = type;
            this.offset = offset;
            this.virtualAddress = virtualAddress;
            this.fileSize = fileSize;
            this.memorySize = memorySize;
        }
    }

    private static final class DynamicInfo {
        private final long stringAddress;
        private final long stringSize;
        private final List<Long> neededOffsets;

        private DynamicInfo(long stringAddress, long stringSize, List<Long> neededOffsets) {
            this.stringAddress = stringAddress;
            this.stringSize = stringSize;
            this.neededOffsets = neededOffsets;
        }
    }
}
