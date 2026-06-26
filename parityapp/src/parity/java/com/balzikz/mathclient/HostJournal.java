package com.balzikz.mathclient;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class HostJournal {
    private static final String NAME = "flarial-parity-bootstrap.txt";
    private static final Object LOCK = new Object();

    private HostJournal() {
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), NAME);
    }

    public static void reset(Context context) {
        synchronized (LOCK) {
            writeRaw(file(context), "MATH FLARIAL PARITY BOOTSTRAP\nstarted="
                    + System.currentTimeMillis() + "\n");
        }
    }

    public static void write(Context context, String status, String detail) {
        synchronized (LOCK) {
            String block = "\n---\nstatus=" + safe(status)
                    + "\ndetail=" + safe(detail)
                    + "\npid=" + android.os.Process.myPid()
                    + "\nthread=" + Thread.currentThread().getName()
                    + "\ntime=" + System.currentTimeMillis() + "\n";
            try (FileOutputStream output = new FileOutputStream(file(context), true)) {
                output.write(block.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String read(Context context) {
        synchronized (LOCK) {
            File source = file(context);
            if (!source.isFile()) return "No parity journal yet.";
            try (FileInputStream input = new FileInputStream(source);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            } catch (Throwable error) {
                return "Journal read failed: " + error;
            }
        }
    }

    private static void writeRaw(File file, String text) {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String safe(String value) {
        if (value == null) return "null";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
