package com.balzikz.mathclient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

public final class MinecraftGameHostLabActivity extends Activity {
    private static final String[] LIBS = {
            "libc++_shared.so", "libfmod.so", "libHttpClient.Android.so", "libmaesdk.so",
            "libPlayFabMultiplayer.so", "libMediaDecoders_Android.so", "libconscrypt_jni.so",
            "libmcfix.so", "libminecraftpe.so"
    };

    private TextView report;
    private Button launch;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 11, 10));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        scroll.addView(root);

        report = new TextView(this);
        report.setTextColor(Color.LTGRAY);
        report.setTextSize(11);
        report.setTextIsSelectable(true);
        root.addView(report);

        launch = new Button(this);
        launch.setText("ЗАПУСТИТЬ STAGE 3.9 GAME HOST");
        launch.setOnClickListener(view -> {
            HostJournal.write(this, "LAUNCH_REQUESTED", "Starting GameHostActivity");
            startActivity(new Intent(this, GameHostActivity.class));
        });
        root.addView(launch);

        Button refresh = new Button(this);
        refresh.setText("ОБНОВИТЬ JOURNAL");
        refresh.setOnClickListener(view -> refresh());
        root.addView(refresh);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        File runtime = HostJournal.runtime(this);
        boolean ready = runtime.isDirectory()
                && new File(runtime, "runtime-manifest.txt").isFile();
        StringBuilder text = new StringBuilder("MATH GAMEACTIVITY HOST\nStage: 3.9\n\n");
        for (String name : LIBS) {
            File file = new File(runtime, name);
            boolean ok = file.isFile() && file.canRead() && file.length() > 0;
            text.append(ok ? "[READY] " : "[MISSING] ").append(name).append('\n');
            ready &= ok;
        }
        text.append("\n=== LAST JOURNAL ===\n").append(HostJournal.read(this));
        text.append("\nPreflight: ").append(ready ? "READY" : "BLOCKED");
        report.setText(text.toString());
        launch.setEnabled(ready);
    }
}
