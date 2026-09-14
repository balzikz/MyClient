package com.balzikz.mathclient.foundation;

import android.app.Activity;
import android.graphics.Color;
import android.view.*;
import android.widget.*;

final class Ui {
    static int dp(Activity a, int value) { return (int) (a.getResources().getDisplayMetrics().density * value); }
    static LinearLayout column(Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 21, 29));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int p = dp(a, 18);
            v.setPadding(p + insets.getSystemWindowInsetLeft(), p + insets.getSystemWindowInsetTop(),
                    p + insets.getSystemWindowInsetRight(), p + insets.getSystemWindowInsetBottom());
            return insets;
        });
        return root;
    }
    static TextView text(Activity a, LinearLayout root, String value, int size) {
        TextView text = new TextView(a); text.setText(value); text.setTextSize(size);
        text.setTextColor(Color.rgb(228, 235, 245));
        text.setPadding(0, dp(a, 6), 0, dp(a, 10));
        root.addView(text); return text;
    }
    static Button button(Activity a, LinearLayout root, String label, Runnable action) {
        Button button = new Button(a); button.setText(label); button.setAllCaps(false);
        button.setMinHeight(dp(a, 52));
        root.addView(button, new LinearLayout.LayoutParams(-1, -2));
        button.setOnClickListener(v -> action.run()); return button;
    }
}
