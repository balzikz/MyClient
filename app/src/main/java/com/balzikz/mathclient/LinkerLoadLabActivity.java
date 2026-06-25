package com.balzikz.mathclient;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public final class LinkerLoadLabActivity extends Activity {

    private static final String[] TEST_LIBRARIES = {
            "libfmod.so",
            "libHttpClient.Android.so"
    };

    private TextView reportView;
    private Button runButton;
    private Button copyButton;
    private String report = "Stage 3.3 has not started.";
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 11, 10));
        getWindow().setNavigationBarColor(Color.rgb(9, 11, 10));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 11, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);

        root.addView(text("MATH LINKER LOAD LAB", 21, Color.WHITE, true));
        root.addView(text(
                "Stage 3.3 / Isolated dependency loading",
                12,
                Color.rgb(98, 216, 139),
                true));

        TextView note = text(
                "Лаборатория работает в отдельном процессе MATH. Она разрешает dlopen только для libfmod.so и libHttpClient.Android.so, не вызывает их функции и сразу выполняет dlclose. libminecraftpe.so жёстко запрещена на этом этапе.",
                11,
                Color.LTGRAY,
                false);
        note.setPadding(0, dp(8), 0, dp(8));
        root.addView(note);

        reportView = text(report, 11, Color.LTGRAY, false);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);

        runButton = new Button(this);
        runButton.setText("ЗАПУСТИТЬ LINKER TEST");
        runButton.setOnClickListener(view -> runLinkerTest());
        root.addView(runButton);

        copyButton = new Button(this);
        copyButton.setText("СКОПИРОВАТЬ ОТЧЁТ");
        copyButton.setOnClickListener(view -> copyReport());
        root.addView(copyButton);

        setContentView(scroll);
        runPreflight();
    }

    private void runPreflight() {
        File directory = runtimeDirectory();
        StringBuilder value = new StringBuilder();
        value.append("MATH BEDROCK LINKER PREFLIGHT\n");
        value.append("Stage: 3.3\n");
        value.append("Process: ").append(processName()).append('\n');
        value.append("Runtime directory: ").append(directory.getAbsolutePath()).append("\n\n");

        boolean ready = directory.isDirectory();
        value.append("Directory gate: ").append(ready ? "PASS" : "BLOCK").append('\n');

        File manifest = new File(directory, "runtime-manifest.txt");
        boolean manifestReady = manifest.isFile() && manifest.canRead();
        value.append("Manifest: ").append(manifestReady ? "READY" : "MISSING").append('\n');
        ready &= manifestReady;

        for (String name : TEST_LIBRARIES) {
            File file = new File(directory, name);
            boolean fileReady = file.isFile() && file.canRead() && file.length() > 0;
            value.append(fileReady ? "[READY] " : "[MISSING] ")
                    .append(name);
            if (fileReady) {
                value.append(" | ").append(file.length()).append(" bytes");
            }
            value.append('\n');
            ready &= fileReady;
        }

        File minecraft = new File(directory, "libminecraftpe.so");
        value.append("libminecraftpe.so present: ")
                .append(minecraft.isFile() ? "YES" : "NO")
                .append('\n');
        value.append("libminecraftpe.so load policy: DENY\n");
        value.append("Native core: ")
                .append(NativeBridge.isLoaded() ? "READY" : "FAILED")
                .append('\n');
        ready &= NativeBridge.isLoaded();

        value.append("Preflight verdict: ")
                .append(ready ? "READY TO RUN" : "BLOCKED");

        report = value.toString();
        reportView.setText(report);
        runButton.setEnabled(ready);
    }

    private void runLinkerTest() {
        if (busy) return;
        busy = true;
        runButton.setEnabled(false);
        copyButton.setEnabled(false);
        reportView.setText(
                "Running Stage 3.3 in secondary process...\n\n"
                        + "Разрешены только две малые библиотеки. Minecraft library remains blocked.");

        String path = runtimeDirectory().getAbsolutePath();
        new Thread(() -> {
            String result = NativeBridge.runLinkerLoadTest(path);
            runOnUiThread(() -> {
                report = result;
                reportView.setText(result);
                busy = false;
                runButton.setEnabled(true);
                copyButton.setEnabled(true);
                Toast.makeText(this, "Linker test завершён.", Toast.LENGTH_LONG).show();
            });
        }, "MATH-Linker-Load-Lab").start();
    }

    private File runtimeDirectory() {
        return new File(
                new File(getNoBackupFilesDir(), "bedrock-runtime"),
                BedrockProfile.ID);
    }

    private String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName();
        }
        return getPackageName() + ":linker_lab (pid=" + android.os.Process.myPid() + ")";
    }

    private void copyReport() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MATH Linker Load Lab",
                report));
        Toast.makeText(this, "Отчёт скопирован.", Toast.LENGTH_SHORT).show();
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
