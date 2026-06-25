package com.balzikz.mathclient;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

public final class MinecraftGameHostLabActivity extends Activity {
    private static final String[] LIBS = {
            "libc++_shared.so", "libfmod.so", "libHttpClient.Android.so", "libmaesdk.so",
            "libPlayFabMultiplayer.so", "libMediaDecoders_Android.so", "libconscrypt_jni.so",
            "libmcfix.so", "libminecraftpe.so"
    };

    private TextView report;
    private Button launch;

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

        launch = new Button(this);
        launch.setText("ЗАПУСТИТЬ STAGE 3.9.2 GAME HOST");
        launch.setOnClickListener(view -> {
            HostJournal.reset(this);
            HostJournal.write(this, "LAUNCH_REQUESTED", "Starting GameHostActivity");
            startActivity(new Intent(this, GameHostActivity.class));
        });
        root.addView(launch);

        Button refresh = new Button(this);
        refresh.setText("ОБНОВИТЬ TIMELINE");
        refresh.setOnClickListener(view -> refresh());
        root.addView(refresh);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        File runtime = HostJournal.runtime(this);
        boolean ready = runtime.isDirectory()
                && new File(runtime, "runtime-manifest.txt").isFile();
        StringBuilder text = new StringBuilder("MATH GAMEACTIVITY HOST\nStage: 3.9.2\n\n");
        for (String name : LIBS) {
            File file = new File(runtime, name);
            boolean ok = file.isFile() && file.canRead() && file.length() > 0;
            text.append(ok ? "[READY] " : "[MISSING] ").append(name).append('\n');
            ready &= ok;
        }
        text.append("\n=== HOST TIMELINE ===\n").append(HostJournal.read(this));
        text.append("\n=== LAST PROCESS EXIT ===\n").append(latestExitInfo());
        text.append("\nPreflight: ").append(ready ? "READY" : "BLOCKED");
        report.setText(text.toString());
        launch.setEnabled(ready);
    }

    private String latestExitInfo() {
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
                            + "\ndescription=" + (description == null ? "NONE" : description);
                }
            }
            return "No historical :game_host exit found.";
        } catch (Throwable error) {
            return error.getClass().getSimpleName() + ": " + error.getMessage();
        }
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
