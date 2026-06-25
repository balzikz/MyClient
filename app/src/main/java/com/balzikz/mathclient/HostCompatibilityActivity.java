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

public final class HostCompatibilityActivity extends Activity {

    private TextView reportView;
    private Button copyButton;
    private String report = "Running read-only host discovery...";

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

        root.addView(text("MATH BEDROCK HOST LAB", 21, Color.WHITE, true));
        root.addView(text(
                "Stage 3.1 / PackageContext + library discovery",
                12,
                Color.rgb(98, 216, 139),
                true));

        TextView note = text(
                "Этот экран только читает метаданные установленного Minecraft, открывает официальный AssetManager и проверяет наличие ARM64-библиотек. libminecraftpe.so здесь не загружается и не исполняется.",
                11,
                Color.LTGRAY,
                false);
        note.setPadding(0, dp(8), 0, dp(8));
        root.addView(note);

        reportView = text(report, 11, Color.LTGRAY, false);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);

        Button refreshButton = new Button(this);
        refreshButton.setText("ПОВТОРИТЬ ПРОВЕРКУ");
        refreshButton.setOnClickListener(view -> runDiscovery());
        root.addView(refreshButton);

        copyButton = new Button(this);
        copyButton.setText("ПРОВЕРКА ВЫПОЛНЯЕТСЯ...");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(view -> copyReport());
        root.addView(copyButton);

        setContentView(scroll);
        runDiscovery();
    }

    private void runDiscovery() {
        copyButton.setEnabled(false);
        copyButton.setText("ПРОВЕРКА ВЫПОЛНЯЕТСЯ...");
        reportView.setText("Running read-only host discovery...");

        Context appContext = getApplicationContext();
        new Thread(() -> {
            String result = HostCompatibilityReport.create(appContext);
            runOnUiThread(() -> {
                report = result;
                reportView.setText(result);
                copyButton.setEnabled(true);
                copyButton.setText("СКОПИРОВАТЬ ОТЧЁТ");
                Toast.makeText(this, "Host discovery завершён.", Toast.LENGTH_SHORT).show();
            });
        }, "MATH-Host-Discovery").start();
    }

    private void copyReport() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MATH Bedrock Host Lab",
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
