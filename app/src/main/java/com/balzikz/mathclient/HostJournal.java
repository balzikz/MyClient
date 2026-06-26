package com.balzikz.mathclient;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

public final class HostJournal {
    private static final String STAGE = "4.2.0";
    private static final String NAME = "stage-4.2-host-journal.txt";
    private static final String SIGNAL_NAME = "stage-4.2-signal-trace.txt";
    private static final int MAX_READ_BYTES = 96 * 1024;

    private HostJournal() {
    }

    public static File runtime(Context context) {
        return new File(new File(context.getNoBackupFilesDir(), "bedrock-runtime"), BedrockProfile.ID);
    }

    public static File game(Context context) {
        return new File(runtime(context), "libminecraftpe.so");
    }

    public static File journal(Context context) {
        return new File(context.getNoBackupFilesDir(), NAME);
    }

    public static File signalTrace(Context context) {
        return new File(context.getNoBackupFilesDir(), SIGNAL_NAME);
    }

    public static synchronized void reset(Context context) {
        long started = System.currentTimeMillis();
        writeFresh(journal(context),
                "MATH GAMEACTIVITY HOST TIMELINE\nstage=" + STAGE + "\nstarted=" + started + "\n");
        writeFresh(signalTrace(context),
                "MATH NATIVE SIGNAL TRACE\nstage=" + STAGE + "\nstarted=" + started + "\n");
    }

    public static synchronized void write(Context context, String status, String detail) {
        String text = "\n---\n"
                + "status=" + clean(status)
                + "\ndetail=" + clean(detail)
                + "\npid=" + android.os.Process.myPid()
                + "\nthread=" + clean(Thread.currentThread().getName())
                + "\ntime=" + System.currentTimeMillis() + "\n";
        try (FileOutputStream output = new FileOutputStream(journal(context), true)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized String read(Context context) {
        return readTail(journal(context), "No Stage 4.2 journal yet.");
    }

    public static synchronized String readSignalTrace(Context context) {
        return readTail(signalTrace(context), "No Stage 4.2 native signal trace yet.");
    }

    private static void writeFresh(File file, String text) {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    private static String readTail(File file, String missing) {
        if (!file.isFile()) return missing;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            int count = (int) Math.min(length, MAX_READ_BYTES);
            long start = Math.max(0L, length - count);
            input.seek(start);
            byte[] data = new byte[count];
            input.readFully(data);
            String body = new String(data, StandardCharsets.UTF_8);
            return start == 0L ? body : "[SHOWING LAST " + count + " OF " + length + " BYTES]\n" + body;
        } catch (Throwable error) {
            return "Journal read failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static String clean(String value) {
        if (value == null) return "NONE";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= 4096 ? cleaned : cleaned.substring(0, 4096) + "...";
    }
}
