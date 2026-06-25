package com.balzikz.mathclient;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class HostJournal {
    private static final String NAME = "stage-3.9-host-journal.txt";
    private static final int MAX_READ_BYTES = 64 * 1024;

    private HostJournal() {
    }

    public static File runtime(Context context) {
        return new File(new File(context.getNoBackupFilesDir(), "bedrock-runtime"), BedrockProfile.ID);
    }

    public static File game(Context context) {
        return new File(runtime(context), "libminecraftpe.so");
    }

    public static synchronized void reset(Context context) {
        File file = new File(context.getNoBackupFilesDir(), NAME);
        String header = "MATH GAMEACTIVITY HOST TIMELINE\n"
                + "stage=3.9.5\n"
                + "started=" + System.currentTimeMillis() + "\n";
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void write(Context context, String status, String detail) {
        File file = new File(context.getNoBackupFilesDir(), NAME);
        String text = "\n---\n"
                + "status=" + clean(status)
                + "\ndetail=" + clean(detail)
                + "\npid=" + android.os.Process.myPid()
                + "\nthread=" + clean(Thread.currentThread().getName())
                + "\ntime=" + System.currentTimeMillis() + "\n";
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized String read(Context context) {
        File file = new File(context.getNoBackupFilesDir(), NAME);
        if (!file.isFile()) return "No Stage 3.9 journal yet.";
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), MAX_READ_BYTES)];
            int count = input.read(data);
            return count <= 0 ? "Journal is empty." : new String(data, 0, count, StandardCharsets.UTF_8);
        } catch (Throwable error) {
            return "Journal read failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static String clean(String value) {
        if (value == null) return "NONE";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= 4096 ? cleaned : cleaned.substring(0, 4096) + "…";
    }
}
