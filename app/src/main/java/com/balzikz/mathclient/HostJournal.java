package com.balzikz.mathclient;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class HostJournal {
    private static final String NAME = "stage-3.9-host-journal.txt";

    private HostJournal() {
    }

    public static File runtime(Context context) {
        return new File(new File(context.getNoBackupFilesDir(), "bedrock-runtime"), BedrockProfile.ID);
    }

    public static File game(Context context) {
        return new File(runtime(context), "libminecraftpe.so");
    }

    public static synchronized void write(Context context, String status, String detail) {
        File file = new File(context.getNoBackupFilesDir(), NAME);
        String text = "stage=3.9\nstatus=" + clean(status)
                + "\ndetail=" + clean(detail)
                + "\npid=" + android.os.Process.myPid()
                + "\ntime=" + System.currentTimeMillis() + "\n";
        try (FileOutputStream output = new FileOutputStream(file, false)) {
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
            byte[] data = new byte[(int) Math.min(file.length(), 32768)];
            int count = input.read(data);
            return count <= 0 ? "Journal is empty." : new String(data, 0, count, StandardCharsets.UTF_8);
        } catch (Throwable error) {
            return "Journal read failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static String clean(String value) {
        return value == null ? "NONE" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
