package com.balzikz.mathclient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class RenderStateLabActivity extends Activity {

    private RenderStateLabView glView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 11, 10));
        getWindow().setNavigationBarColor(Color.rgb(9, 11, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 11, 10));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(text("MATH RENDER STATE LAB", 21, Color.WHITE, true));
        root.addView(text(
                "Host scene → MATH overlay → state restore → sentinel",
                12,
                Color.rgb(98, 216, 139),
                true));

        TextView status = text("Creating native render pipelines...", 11, Color.LTGRAY, false);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status);

        glView = new RenderStateLabView(this, status::setText);
        LinearLayout.LayoutParams glParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f);
        root.addView(glView, glParams);

        TextView explanation = text(
                "Тёмно-зелёная область изображает чужую сцену. Полупрозрачная MATH-панель рисуется поверх неё с другим shader/VAO/blend/viewport. После панели исходное GL-состояние возвращается. Белый треугольник справа снизу рисуется уже после возврата и служит визуальным контрольным маркером. Коснись сцены, чтобы двигать панель.",
                11,
                Color.LTGRAY,
                false);
        explanation.setPadding(0, dp(10), 0, dp(8));
        root.addView(explanation);

        Button fastLab = new Button(this);
        fastLab.setText("ОТКРЫТЬ FAST GUARD LAB");
        fastLab.setAllCaps(false);
        fastLab.setOnClickListener(view ->
                startActivity(new Intent(this, FastGuardLabActivity.class)));
        root.addView(fastLab);

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
