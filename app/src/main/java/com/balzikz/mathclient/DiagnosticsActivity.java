package com.balzikz.mathclient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class DiagnosticsActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 11, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        scroll.addView(root);

        TextView title = block("MATH DIAGNOSTICS\nStage 2.5 / APK native inventory", 22, Color.WHITE);
        root.addView(title);

        String report = "\n=== NATIVE CORE ===\n"
                + NativeBridge.getStatusText()
                + "\n\n=== BEDROCK PACKAGE ===\n"
                + PackageReport.create(this)
                + "\n\n=== DEVICE / GRAPHICS ===\n"
                + GraphicsReport.create(this)
                + "\n\n=== APK NATIVE INVENTORY ===\n"
                + ApkNativeInventory.create(this)
                + "\n\n=== CURRENT PROCESS MODULES ===\n"
                + NativeBridge.getLoadedModules();

        TextView body = block(report, 12, Color.LTGRAY);
        body.setTextIsSelectable(true);
        root.addView(body);

        Button next = new Button(this);
        next.setText("ОТКРЫТЬ MATH CLIENT");
        next.setOnClickListener(view -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        root.addView(next);

        setContentView(scroll);
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
