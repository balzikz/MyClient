package com.balzikz.mathclient;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class EglLabActivity extends Activity {

    private EglLabView glView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 11, 10));
        getWindow().setNavigationBarColor(Color.rgb(9, 11, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 11, 10));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = text("MATH EGL LAB", 22, Color.WHITE, true);
        root.addView(title);

        TextView subtitle = text(
                "Native C++ / OpenGL ES 3.0 / JNI touch bridge",
                12,
                Color.rgb(98, 216, 139),
                true);
        root.addView(subtitle);

        TextView status = text("Creating EGL context...", 11, Color.LTGRAY, false);
        status.setPadding(0, dp(10), 0, dp(12));
        root.addView(status);

        glView = new EglLabView(this, status::setText);
        LinearLayout.LayoutParams glParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f);
        root.addView(glView, glParams);

        TextView hint = text(
                "Коснись области: треугольник переместится и изменит состояние. Рендер выполняется внутри libmathclient.so.",
                12,
                Color.LTGRAY,
                false);
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint);

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
