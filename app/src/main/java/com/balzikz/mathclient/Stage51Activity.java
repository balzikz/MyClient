package com.balzikz.mathclient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class Stage51Activity extends Activity {
    private TextView report;
    private Button prepare;
    private Button launch;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 11, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("MATH CLIENT STAGE 5.1");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Minecraft 1.26.30.x • JVM load • NativeActivity handoff");
        subtitle.setTextColor(Color.rgb(98, 216, 139));
        subtitle.setTextSize(12);
        root.addView(subtitle);

        report = new TextView(this);
        report.setTextColor(Color.LTGRAY);
        report.setTextSize(11);
        report.setTextIsSelectable(true);
        report.setPadding(0, 20, 0, 20);
        root.addView(report);

        prepare = new Button(this);
        prepare.setText("1. ПОДГОТОВИТЬ RUNTIME ИЗ MINECRAFT");
        prepare.setOnClickListener(view -> prepareRuntime());
        root.addView(prepare);

        launch = new Button(this);
        launch.setText("2. ЗАПУСТИТЬ STAGE 5.1 HOST");
        launch.setOnClickListener(view -> launchHost());
        root.addView(launch);

        Button refresh = new Button(this);
        refresh.setText("ОБНОВИТЬ СОСТОЯНИЕ");
        refresh.setOnClickListener(view -> refresh());
        root.addView(refresh);

        Button diagnostics = new Button(this);
        diagnostics.setText("ОТКРЫТЬ ДИАГНОСТИКУ STAGE 5.1");
        diagnostics.setOnClickListener(view ->
                startActivity(new Intent(this, MathShimLabActivity.class)));
        root.addView(diagnostics);

        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy) refresh();
    }

    private void prepareRuntime() {
        if (busy) return;
        busy = true;
        prepare.setEnabled(false);
        launch.setEnabled(false);
        report.setText("Подготовка runtime...\nФайлы читаются только из установленного Minecraft.");

        new Thread(() -> {
            String result = InstalledRuntimePreparer.prepare(getApplicationContext());
            runOnUiThread(() -> {
                busy = false;
                prepare.setEnabled(true);
                report.setText(result);
                launch.setEnabled(result.contains("Runtime preparation: READY FOR STAGE 5.1"));
                Toast.makeText(this, "Подготовка завершена", Toast.LENGTH_LONG).show();
            });
        }, "MATH-Stage51-Prepare").start();
    }

    private void launchHost() {
        if (!InstalledRuntimePreparer.isReady(getApplicationContext())) {
            Toast.makeText(this, "Сначала подготовь новый runtime", Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        HostJournal.reset(this);
        HostJournal.write(this, "LAUNCH_REQUESTED",
                "Starting RuntimeHostActivity stage=5.1");
        startActivity(new Intent(this, RuntimeHostActivity.class));
    }

    private void refresh() {
        String state = InstalledRuntimePreparer.inspect(getApplicationContext());
        report.setText(state);
        launch.setEnabled(!busy && state.contains("Prepared runtime: COMPLETE"));
    }
}
