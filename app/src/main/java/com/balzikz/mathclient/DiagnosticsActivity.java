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
    private String baseReport;
    private String fullModules;
    private boolean expanded;

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
                "MATH DIAGNOSTICS\nStage 2.5 / APK native inventory",
                22,
                Color.WHITE));

        fullModules = NativeBridge.getLoadedModules();
        baseReport = "\n=== NATIVE CORE ===\n"
                + NativeBridge.getStatusText()
                + "\n\n=== BEDROCK PACKAGE ===\n"
                + PackageReport.create(this)
                + "\n\n=== DEVICE / GRAPHICS ===\n"
                + GraphicsReport.create(this)
                + "\n\n=== APK NATIVE INVENTORY ===\n"
                + ApkNativeInventory.create(this)
                + "\n\n=== CURRENT PROCESS MODULES ===\n";

        body = block(buildVisibleReport(), 12, Color.LTGRAY);
        body.setTextIsSelectable(true);
        root.addView(body);

        expandButton = new Button(this);
        expandButton.setText("РАЗВЕРНУТЬ ВЕСЬ СПИСОК МОДУЛЕЙ");
        expandButton.setOnClickListener(view -> toggleModules());
        root.addView(expandButton);

        Button copy = new Button(this);
        copy.setText("СКОПИРОВАТЬ ПОЛНЫЙ ОТЧЁТ");
        copy.setOnClickListener(view -> copyFullReport());
        root.addView(copy);

        Button next = new Button(this);
        next.setText("ОТКРЫТЬ MATH CLIENT");
        next.setOnClickListener(view -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        root.addView(next);

        setContentView(scroll);
    }

    private void toggleModules() {
        expanded = !expanded;
        body.setText(buildVisibleReport());
        expandButton.setText(expanded
                ? "СВЕРНУТЬ СПИСОК МОДУЛЕЙ"
                : "РАЗВЕРНУТЬ ВЕСЬ СПИСОК МОДУЛЕЙ");
    }

    private String buildVisibleReport() {
        return baseReport + (expanded ? fullModules : modulePreview(fullModules));
    }

    private String modulePreview(String value) {
        String[] lines = value.split("\\n");
        if (lines.length <= PREVIEW_MODULE_LINES) {
            return value;
        }

        StringBuilder preview = new StringBuilder();
        for (int index = 0; index < PREVIEW_MODULE_LINES; index++) {
            if (index > 0) {
                preview.append('\n');
            }
            preview.append(lines[index]);
        }
        preview.append("\n... ")
                .append(lines.length - PREVIEW_MODULE_LINES)
                .append(" more modules. Use the expand button below.");
        return preview.toString();
    }

    private void copyFullReport() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MATH Client diagnostics",
                baseReport + fullModules));
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
