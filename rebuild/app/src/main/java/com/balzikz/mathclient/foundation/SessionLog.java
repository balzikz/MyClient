package com.balzikz.mathclient.foundation;

import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import com.balzikz.mathclient.foundation.core.Inventory;
import com.balzikz.mathclient.foundation.core.SessionFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SessionLog {
    private static volatile SessionLog active;
    final String id;
    final File directory;
    private final File journal;
    private volatile String writeError;

    static File root(Context context) { return new File(context.getFilesDir(), "sessions"); }
    static String newId() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
    static synchronized SessionLog bind(Context context, String id) throws IOException {
        SessionLog log = new SessionLog(context, id);
        active = log;
        log.event("PROCESS_ATTACHED", "version=" + BuildConfig.VERSION_NAME
                + " appStartElapsed=" + FoundationApplication.startedElapsed
                + " loader=" + SessionLog.class.getClassLoader());
        return log;
    }
    static SessionLog active() { return active; }
    private SessionLog(Context context, String id) throws IOException {
        this.id = id;
        directory = SessionFiles.directory(root(context), id);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create diagnostic session");
        journal = new File(directory, "events-" + Process.myPid() + ".jsonl");
    }
    synchronized void event(String kind, String detail) {
        Map<String, Object> fields = Inventory.fields("schema", 1, "session", id,
                "timeMs", System.currentTimeMillis(), "elapsedMs", SystemClock.elapsedRealtime(),
                "pid", Process.myPid(), "tid", Process.myTid(), "process", Application.getProcessName(),
                "thread", Thread.currentThread().getName(), "event", kind, "detail", detail);
        String line = Inventory.json(fields);
        Log.i("MathFoundation", line);
        try (FileOutputStream out = new FileOutputStream(journal, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            writeError = e.toString();
            Log.e("MathFoundation", "Persistent log failed", e);
        }
    }
    String writeError() { return writeError; }
    void exception(String event, Throwable error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        event(event, text.toString());
    }
    synchronized void writeText(String name, String text) throws IOException {
        if (!name.matches("[a-zA-Z0-9_.-]+\\.(txt|jsonl)")) throw new IOException("Invalid report filename");
        // A unique report name is used by callers; never overwrite a previous report.
        File file = new File(directory, name);
        if (!file.createNewFile()) throw new IOException("Report already exists: " + name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
    }
}
