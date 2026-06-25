package com.balzikz.mathclient;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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

public final class MinecraftSymbolProbeActivity extends Activity {

    private static final String[] REQUIRED_LIBRARIES = {
            "libc++_shared.so",
            "libfmod.so",
            "libHttpClient.Android.so",
            "libmaesdk.so",
            "libPlayFabMultiplayer.so",
            "libMediaDecoders_Android.so",
            "libconscrypt_jni.so",
            "libmcfix.so",
            "libminecraftpe.so"
    };

    private TextView reportView;
    private Button runButton;
    private Button stage37Button;
    private Button copyButton;
    private String report = "Stage 3.6 has not started.";
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

        root.addView(text("MATH MINECRAFT ENTRYPOINT PROBE", 21, Color.WHITE, true));
        root.addView(text(
                "Stage 3.6 / dlsym table without invocation",
                12,
                Color.rgb(98, 216, 139),
                true));

        TextView note = text(
                "Отдельный процесс загружает проверенный runtime, затем через dlsym ищет JNI_OnLoad, ANativeActivity_onCreate, android_main и несколько Mojang JNI-методов. Найденные адреса только описываются через dladdr. Ни одна точка входа не вызывается.",
                11,
                Color.LTGRAY,
                false);
        note.setPadding(0, dp(8), 0, dp(8));
        root.addView(note);

        reportView = text(report, 11, Color.LTGRAY, false);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);

        runButton = new Button(this);
        runButton.setText("ЗАПУСТИТЬ ENTRYPOINT PROBE");
        runButton.setOnClickListener(view -> runProbe());
        root.addView(runButton);

        stage37Button = new Button(this);
        stage37Button.setText("ПЕРЕЙТИ К STAGE 3.7");
        stage37Button.setEnabled(false);
        stage37Button.setOnClickListener(view ->
                startActivity(new Intent(this, MinecraftJavaContractActivity.class)));
        root.addView(stage37Button);

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
        value.append("MATH BEDROCK ENTRYPOINT PROBE PREFLIGHT\n");
        value.append("Stage: 3.6\n");
        value.append("Process: ").append(processName()).append('\n');
        value.append("Runtime directory: ").append(directory.getAbsolutePath()).append("\n\n");

        boolean ready = directory.isDirectory();
        value.append("Directory gate: ").append(ready ? "PASS" : "BLOCK").append('\n');

        File manifest = new File(directory, "runtime-manifest.txt");
        boolean manifestReady = manifest.isFile() && manifest.canRead();
        value.append("Manifest: ").append(manifestReady ? "READY" : "MISSING").append('\n');
        ready &= manifestReady;

        for (String name : REQUIRED_LIBRARIES) {
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

        value.append(LinkerBridge.loadStatus()).append('\n');
        ready &= LinkerBridge.isLoaded();
        value.append("Invocation policy: DENY ALL EXPORT CALLS\n");
        value.append("Preflight verdict: ")
                .append(ready ? "READY TO PROBE" : "BLOCKED");

        report = value.toString();
        reportView.setText(report);
        runButton.setEnabled(ready);
        stage37Button.setEnabled(false);
    }

    private void runProbe() {
        if (busy) return;
        busy = true;
        runButton.setEnabled(false);
        stage37Button.setEnabled(false);
        copyButton.setEnabled(false);
        reportView.setText(
                "Running Stage 3.6 in :minecraft_probe...\n\n"
                        + "The runtime will be loaded, symbols inspected, and every handle closed without invoking exports.");

        String path = runtimeDirectory().getAbsolutePath();
        new Thread(() -> {
            String result = LinkerBridge.runMinecraftSymbolProbe(path);
            runOnUiThread(() -> {
                report = result;
                reportView.setText(result);
                busy = false;
                runButton.setEnabled(true);
                copyButton.setEnabled(true);
                stage37Button.setEnabled(
                        result.contains("JNI_OnLoad owned by Minecraft: YES"));
                Toast.makeText(this, "Entrypoint probe завершён.", Toast.LENGTH_LONG).show();
            });
        }, "MATH-Minecraft-Entrypoint-Probe").start();
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
        return getPackageName() + ":minecraft_probe (pid=" + android.os.Process.myPid() + ")";
    }

    private void copyReport() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MATH Minecraft Entrypoint Probe",
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
