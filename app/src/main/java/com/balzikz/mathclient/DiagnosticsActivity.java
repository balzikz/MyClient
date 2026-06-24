package com.balzikz.mathclient;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class DiagnosticsActivity extends Activity {

    private static final int PREVIEW_MODULE_LINES = 61;

    private TextView body;
    private Button expandButton;
    private Button copyButton;
    private String reportPrefix;
    private String fullModules;
    private String elfReport = "Analyzing libminecraftpe.so...";
    private boolean expanded;
    private boolean elfReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 11, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        scroll.addView(root);

        root.addView(block(
                "MATH DIAGNOSTICS\nStage 2.9 / ELF Inspector",
                22,
                Color.WHITE));

        fullModules = NativeBridge.getLoadedModules();
        reportPrefix = "\n=== NATIVE CORE ===\n"
                + NativeBridge.getStatusText()
                + "\n\n=== BEDROCK PACKAGE ===\n"
                + PackageReport.create(this)
                + "\n\n=== DEVICE / GRAPHICS ===\n"
                + GraphicsReport.create(this)
                + "\n\n=== APK NATIVE INVENTORY ===\n"
                + ApkNativeInventory.create(this);

        body = block(buildVisibleReport(), 12, Color.LTGRAY);
        body.setTextIsSelectable(true);
        root.addView(body);

        expandButton = new Button(this);
        expandButton.setText("РАЗВЕРНУТЬ ВЕСЬ СПИСОК МОДУЛЕЙ");
        expandButton.setOnClickListener(view -> toggleModules());
        root.addView(expandButton);

        copyButton = new Button(this);
        copyButton.setText("ELF-АНАЛИЗ ВЫПОЛНЯЕТСЯ...");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(view -> copyFullReport());
        root.addView(copyButton);

        Button next = new Button(this);
        next.setText("ОТКРЫТЬ MATH CLIENT");
        next.setOnClickListener(view -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        root.addView(next);

        setContentView(scroll);
        startElfInspection();
    }

    private void startElfInspection() {
        Context appContext = getApplicationContext();
        new Thread(() -> {
            String result = ElfInspector.create(appContext);
            runOnUiThread(() -> {
                elfReport = result;
                elfReady = true;
                body.setText(buildVisibleReport());
                copyButton.setEnabled(true);
                copyButton.setText("СКОПИРОВАТЬ ПОЛНЫЙ ОТЧЁТ");
                Toast.makeText(this, "ELF-анализ завершён.", Toast.LENGTH_SHORT).show();
            });
        }, "MATH-ELF-Inspector").start();
    }

    private void toggleModules() {
        expanded = !expanded;
        body.setText(buildVisibleReport());
        expandButton.setText(expanded
                ? "СВЕРНУТЬ СПИСОК МОДУЛЕЙ"
                : "РАЗВЕРНУТЬ ВЕСЬ СПИСОК МОДУЛЕЙ");
    }

    private String buildVisibleReport() {
        return reportPrefix
                + "\n\n=== ELF INSPECTOR / libminecraftpe.so ===\n"
                + elfReport
                + "\n\n=== CURRENT PROCESS MODULES ===\n"
                + (expanded ? fullModules : modulePreview(fullModules));
    }

    private String modulePreview(String value) {
        String[] lines = value.split("\\n");
        if (lines.length <= PREVIEW_MODULE_LINES) {
            return value;
        }

        StringBuilder preview = new StringBuilder();
        for (int index = 0; index < PREVIEW_MODULE_LINES; index++) {
            if (index > 0) preview.append('\n');
            preview.append(lines[index]);
        }
        preview.append("\n... ")
                .append(lines.length - PREVIEW_MODULE_LINES)
                .append(" more modules. Use the expand button below.");
        return preview.toString();
    }

    private void copyFullReport() {
        if (!elfReady) {
            Toast.makeText(this, "ELF-анализ ещё выполняется.", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show();
            return;
        }

        String fullReport = reportPrefix
                + "\n\n=== ELF INSPECTOR / libminecraftpe.so ===\n"
                + elfReport
                + "\n\n=== CURRENT PROCESS MODULES ===\n"
                + fullModules;
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MATH Client diagnostics",
                fullReport));
        Toast.makeText(this, "Полный отчёт скопирован.", Toast.LENGTH_SHORT).show();
    }

    private TextView block(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        view.setPadding(0, 16, 0, 16);
        return view;
    }
}
