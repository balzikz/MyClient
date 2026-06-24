package com.balzikz.mathclient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ZipEntryAccess {

    private final String zipPath;
    private final String entryName;
    private final long entrySize;

    ZipEntryAccess(String zipPath, String entryName) throws Exception {
        this.zipPath = zipPath;
        this.entryName = entryName;

        try (ZipFile zip = new ZipFile(zipPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("ZIP entry not found: " + entryName);
            }
            this.entrySize = entry.getSize();
        }
    }

    long size() {
        return entrySize;
    }

    byte[] read(long offset, int length) throws Exception {
        if (offset < 0 || length < 0 || offset + length > entrySize) {
            throw new IllegalArgumentException("Read outside ELF entry");
        }

        try (ZipFile zip = new ZipFile(zipPath);
             InputStream input = zip.getInputStream(zip.getEntry(entryName))) {
            skipFully(input, offset);

            ByteArrayOutputStream output = new ByteArrayOutputStream(length);
            byte[] buffer = new byte[Math.min(8192, Math.max(1, length))];
            int remaining = length;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IllegalStateException("Unexpected end of ZIP entry");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            return output.toByteArray();
        }
    }

    String sha256() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (ZipFile zip = new ZipFile(zipPath);
             InputStream input = zip.getInputStream(zip.getEntry(entryName))) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }

        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }

    private static void skipFully(InputStream input, long amount) throws Exception {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }

            if (input.read() < 0) {
                throw new IllegalStateException("Unexpected end while seeking ZIP entry");
            }
            remaining--;
        }
    }
}
