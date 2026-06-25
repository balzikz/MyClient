package com.balzikz.mathclient;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class RuntimePreparationActivity extends Activity {

    private TextView reportView;
    private Button prepareButton;
    private Button copyButton;
    private String report = "Runtime preparation has not started.";
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

        root.addView(text("MATH RUNTIME PREPARER", 21, Color.WHITE, true));
        root.addView(text(
                "Stage 3.2 / Local runtime preparation",
                12,
                Color.rgb(98, 216, 139),
                true));

        TextView note = text(
                "Подготовщик использует только локально установленную копию Minecraft 1.26.31.1. Он проверяет профиль, создаёт приватный runtime MATH и считает SHA-256. На этом этапе библиотека Minecraft не запускается.",
                11,
                Color.LTGRAY,
                false);
        note.setPadding(0, dp(8), 0, dp(8));
        root.addView(note);

        reportView = text(report, 11, Color.LTGRAY, false);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);

        prepareButton = new Button(this);
        prepareButton.setText("ПОДГОТОВИТЬ RUNTIME");
        prepareButton.setOnClickListener(view -> prepareRuntime());
        root.addView(prepareButton);

        Button inspectButton = new Button(this);
        inspectButton.setText("ПРОВЕРИТЬ ГОТОВЫЙ RUNTIME");
        inspectButton.setOnClickListener(view -> inspectRuntime());
        root.addView(inspectButton);

        copyButton = new Button(this);
        copyButton.setText("СКОПИРОВАТЬ ОТЧЁТ");
        copyButton.setOnClickListener(view -> copyReport());
        root.addView(copyButton);

        setContentView(scroll);
        inspectRuntime();
    }

    private void prepareRuntime() {
        if (busy) return;
        busy = true;
        prepareButton.setEnabled(false);
        copyButton.setEnabled(false);
        reportView.setText("Preparing local Bedrock runtime...\n\nНе закрывай приложение до появления итогового отчёта.");

        Context appContext = getApplicationContext();
        new Thread(() -> {
            String result = BedrockRuntimePreparer.prepare(appContext);
            runOnUiThread(() -> {
                report = result;
                reportView.setText(result);
                busy = false;
                prepareButton.setEnabled(true);
                copyButton.setEnabled(true);
                Toast.makeText(this, "Runtime preparation завершён.", Toast.LENGTH_LONG).show();
            });
        }, "MATH-Runtime-Preparer").start();
    }

    private void inspectRuntime() {
        if (busy) return;
        report = BedrockRuntimePreparer.inspectPrepared(getApplicationContext());
        reportView.setText(report);
    }

    private void copyReport() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MATH Runtime Preparer",
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
