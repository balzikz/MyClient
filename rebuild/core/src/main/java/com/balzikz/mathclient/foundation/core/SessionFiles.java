package com.balzikz.mathclient.foundation.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Session-scoped files only. Never deletes sessions and never exports game binaries. */
public final class SessionFiles {
    private SessionFiles() {}
    public static File directory(File root, String id) throws IOException {
        if (id == null || !id.matches("[0-9]{13}-[a-f0-9]{12}"))
            throw new IOException("Invalid diagnostic session ID");
        File dir = new File(root, id).getCanonicalFile();
        if (!dir.getParentFile().equals(root.getCanonicalFile())) throw new IOException("Outside session root");
        return dir;
    }
    public static List<File> files(File dir) throws IOException {
        File[] files = dir.listFiles(File::isFile);
        if (files == null) throw new IOException("Cannot list session " + dir.getName());
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<File> safe = new ArrayList<>();
        for (File file : files) {
            if (!file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()))
                throw new IOException("Symlink outside session");
            String name = file.getName();
            if (name.endsWith(".jsonl") || name.endsWith(".txt") || name.endsWith(".pb")) safe.add(file);
        }
        return safe;
    }
    /** Snapshot each file's initial length; a running writer cannot make export unbounded. */
    public static void zip(File dir, File output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            byte[] buffer = new byte[64 * 1024];
            for (File file : files(dir)) {
                long remaining = file.length();
                zip.putNextEntry(new ZipEntry(file.getName()));
                try (InputStream in = new FileInputStream(file)) {
                    while (remaining > 0) {
                        int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (n < 0) throw new EOFException("Session file shrank: " + file.getName());
                        zip.write(buffer, 0, n); remaining -= n;
                    }
                }
                zip.closeEntry();
            }
        }
    }
    public static String readText(File dir, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (File file : files(dir)) {
            if (file.getName().endsWith(".pb")) continue;
            byte[] title = ("\n--- " + file.getName() + " ---\n").getBytes(StandardCharsets.UTF_8);
            if (out.size() + title.length > maxBytes) throw new IOException("Report exceeds text limit; share ZIP instead");
            out.write(title);
            try (InputStream in = new FileInputStream(file)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > maxBytes) throw new IOException("Report exceeds text limit; share ZIP instead");
                    out.write(buffer, 0, n);
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
