package com.balzikz.mathclient;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class FastGuardLabActivity extends Activity {

    private FastGuardLabView glView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 11, 10));
        getWindow().setNavigationBarColor(Color.rgb(9, 11, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 11, 10));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(text("MATH FAST GUARD LAB", 21, Color.WHITE, true));
        root.addView(text(
                "SAFE warmup → FAST guard → periodic full audit",
                12,
                Color.rgb(98, 216, 139),
                true));

        TextView status = text("Creating benchmark pipelines...", 11, Color.LTGRAY, false);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status);

        glView = new FastGuardLabView(this, status::setText);
        LinearLayout.LayoutParams glParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f);
        root.addView(glView, glParams);

        TextView explanation = text(
                "Первые 180 кадров используют полный SAFE-снимок. Затем включается FAST-режим: собственный VAO, без настройки VBO каждый кадр и без изменения framebuffer/viewport/texture state. Раз в 120 кадров выполняется полный аудит. При несовпадении лаборатория автоматически возвращается в SAFE. Белый треугольник подтверждает, что сцена продолжила рисовать после возврата состояния.",
                11,
                Color.LTGRAY,
                false);
        explanation.setPadding(0, dp(10), 0, 0);
        root.addView(explanation);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
    }

    @Override
    protected void onPause() {
        glView.onPause();
        super.onPause();
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
