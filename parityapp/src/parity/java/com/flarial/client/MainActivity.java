package com.flarial.client;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.balzikz.mathclient.HostJournal;
import com.flarial.client.Launcher.MinecraftActivity;

public final class MainActivity extends Activity {
    private TextView report;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("MATH CLIENT\nFLARIAL PARITY BOOTSTRAP");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("Один маршрут. Никаких Stage-экранов, меню клиента и модулей. "
                + "Кнопка повторяет загрузочный порядок Flarial до super.onCreate().");
        note.setTextSize(15);
        note.setPadding(0, dp(16), 0, dp(16));
        root.addView(note);

        Button launch = new Button(this);
        launch.setText("ПОДГОТОВИТЬ И ЗАПУСТИТЬ MINECRAFT");
        launch.setOnClickListener(view -> {
            HostJournal.write(this, "LAUNCH_REQUESTED", "MinecraftActivity :client");
            startActivity(new Intent(this, MinecraftActivity.class));
        });
        root.addView(launch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button refresh = new Button(this);
        refresh.setText("ОБНОВИТЬ ЖУРНАЛ");
        refresh.setOnClickListener(view -> refresh());
        root.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(11);
        report.setTextIsSelectable(true);
        report.setMovementMethod(new ScrollingMovementMethod());
        report.setPadding(0, dp(14), 0, dp(14));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f));

        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (report != null) report.setText(HostJournal.read(this));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
