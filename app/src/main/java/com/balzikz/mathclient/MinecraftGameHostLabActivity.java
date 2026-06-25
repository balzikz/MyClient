package com.balzikz.mathclient;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public final class MinecraftGameHostLabActivity extends Activity {
    private static final int CREATE_TOMBSTONE_FILE = 397;
    private static final int CREATE_DIAGNOSTICS_FILE = 1397;
    private static final long MAX_TOMBSTONE_BYTES = 32L * 1024L * 1024L;
    private static final String STAGE = "3.9.7";
    private static final String[] LIBS = {
            "libc++_shared.so", "libfmod.so", "libHttpClient.Android.so", "libmaesdk.so",
            "libPlayFabMultiplayer.so", "libMediaDecoders_Android.so", "libconscrypt_jni.so",
            "libmcfix.so", "libminecraftpe.so"
    };

    private TextView report;
    private Button launch;
    private Button exportTombstone;
    private File cachedTombstone;
    private long tombstoneTimestamp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 11, 10));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        scroll.addView(root);

        report = new TextView(this);
        report.setTextColor(Color.LTGRAY);
        report.setTextSize(11);
        report.setTextIsSelectable(true);
        root.addView(report);

        launch = button("ЗАПУСТИТЬ STAGE " + STAGE + " GAME HOST");
        launch.setOnClickListener(view -> {
            HostJournal.reset(this);
            HostJournal.write(this, "LAUNCH_REQUESTED", "Starting GameHostActivity");
            startActivity(new Intent(this, GameHostActivity.class));
        });
        root.addView(launch);

        Button refresh = button("ОБНОВИТЬ TIMELINE И SIGNAL TRACE");
        refresh.setOnClickListener(view -> refresh());
        root.addView(refresh);

        Button exportDiagnostics = button("СОХРАНИТЬ DIAGNOSTICS (.TXT)");
        exportDiagnostics.setOnClickListener(view -> chooseDiagnosticsDestination());
        root.addView(exportDiagnostics);

        exportTombstone = button("СОХРАНИТЬ SYSTEM TOMBSTONE (.PB)");
        exportTombstone.setEnabled(false);
        exportTombstone.setOnClickListener(view -> chooseTombstoneDestination());
        root.addView(exportTombstone);
        setContentView(scroll);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        if (requestCode == CREATE_DIAGNOSTICS_FILE) {
            writeTextDocument(destination, buildReport());
            return;
        }
        if (requestCode != CREATE_TOMBSTONE_FILE
                || cachedTombstone == null
                || !cachedTombstone.isFile()) return;
        try (InputStream input = new FileInputStream(cachedTombstone);
             OutputStream output = getContentResolver().openOutputStream(destination)) {
            if (output == null) throw new IllegalStateException("Destination stream is null");
            copy(input, output, Long.MAX_VALUE, null);
            output.flush();
            HostJournal.write(this, "TOMBSTONE_EXPORTED", destination.toString());
        } catch (Throwable error) {
            HostJournal.write(this, "TOMBSTONE_EXPORT_FAIL",
                    error.getClass().getName() + ": " + error.getMessage());
        }
        refresh();
    }

    private void refresh() {
        report.setText(buildReport());
    }

    private String buildReport() {
        File runtime = HostJournal.runtime(this);
        boolean ready = runtime.isDirectory() && new File(runtime, "runtime-manifest.txt").isFile();
        StringBuilder text = new StringBuilder("MATH GAMEACTIVITY HOST\nStage: " + STAGE + "\n\n");
        for (String name : LIBS) {
            File file = new File(runtime, name);
            boolean ok = file.isFile() && file.canRead() && file.length() > 0;
            text.append(ok ? "[READY] " : "[MISSING] ").append(name).append('\n');
            ready &= ok;
        }
        text.append("\n=== HOST TIMELINE TAIL ===\n").append(HostJournal.read(this));
        text.append("\n=== NATIVE SIGNAL TRACE TAIL ===\n").append(HostJournal.readSignalTrace(this));
        text.append("\n=== LAST PROCESS EXIT ===\n").append(latestExitInfoAndCaptureTrace());
        text.append("\nPreflight: ").append(ready ? "READY" : "BLOCKED");
        launch.setEnabled(ready);
        exportTombstone.setEnabled(cachedTombstone != null
                && cachedTombstone.isFile()
                && cachedTombstone.length() > 0);
        return text.toString();
    }

    private String latestExitInfoAndCaptureTrace() {
        cachedTombstone = null;
        tombstoneTimestamp = 0L;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "ApplicationExitInfo requires Android 11+.";
        }
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return "ActivityManager unavailable.";
        try {
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    getPackageName(), 0, 16);
            for (ApplicationExitInfo exit : exits) {
                String process = exit.getProcessName();
                if (process != null && process.endsWith(":game_host")) {
                    String description = exit.getDescription();
                    return "process=" + process
                            + "\nreason=" + reasonName(exit.getReason()) + " (" + exit.getReason() + ")"
                            + "\nstatus=" + exit.getStatus()
                            + "\nimportance=" + exit.getImportance()
                            + "\ntimestamp=" + exit.getTimestamp()
                            + "\ndescription=" + (description == null ? "NONE" : description)
                            + "\n" + captureTrace(exit);
                }
            }
            return "No historical :game_host exit found.";
        } catch (Throwable error) {
            return error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private String captureTrace(ApplicationExitInfo exit) {
        if (exit.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) {
            return "tombstone=NOT A NATIVE CRASH; use NATIVE SIGNAL TRACE above";
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return "tombstone=Native traces require Android 12+.";
        }
        try (InputStream input = exit.getTraceInputStream()) {
            if (input == null) return "tombstone=UNAVAILABLE OR OVERWRITTEN";
            File target = new File(getCacheDir(), "stage-3.9.7-native-tombstone.pb");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count;
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                count = copy(input, output, MAX_TOMBSTONE_BYTES, digest);
                output.flush();
                output.getFD().sync();
            }
            if (count <= 0) {
                target.delete();
                return "tombstone=EMPTY";
            }
            cachedTombstone = target;
            tombstoneTimestamp = exit.getTimestamp();
            return "tombstone=CAPTURED"
                    + "\nbytes=" + count
                    + "\nsha256=" + hex(digest.digest())
                    + (count >= MAX_TOMBSTONE_BYTES ? "\nwarning=TRACE MAY BE TRUNCATED" : "");
        } catch (Throwable error) {
            return "tombstone=CAPTURE FAILED: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage();
        }
    }

    private void chooseDiagnosticsDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE,
                "math-stage397-diagnostics-" + System.currentTimeMillis() + ".txt");
        startActivityForResult(intent, CREATE_DIAGNOSTICS_FILE);
    }

    private void chooseTombstoneDestination() {
        if (cachedTombstone == null || !cachedTombstone.isFile()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,
                "math-stage397-tombstone-" + tombstoneTimestamp + ".pb");
        startActivityForResult(intent, CREATE_TOMBSTONE_FILE);
    }

    private void writeTextDocument(Uri destination, String text) {
        try (OutputStream output = getContentResolver().openOutputStream(destination)) {
            if (output == null) throw new IllegalStateException("Destination stream is null");
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            HostJournal.write(this, "DIAGNOSTICS_EXPORTED", destination.toString());
        } catch (Throwable error) {
            HostJournal.write(this, "DIAGNOSTICS_EXPORT_FAIL",
                    error.getClass().getName() + ": " + error.getMessage());
        }
    }

    private static long copy(InputStream input, OutputStream output, long limit,
                             MessageDigest digest) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        while (total < limit) {
            int wanted = (int) Math.min(buffer.length, limit - total);
            int count = input.read(buffer, 0, wanted);
            if (count < 0) break;
            if (count == 0) continue;
            output.write(buffer, 0, count);
            if (digest != null) digest.update(buffer, 0, count);
            total += count;
        }
        return total;
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte value : data) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN";
        }
    }
}
