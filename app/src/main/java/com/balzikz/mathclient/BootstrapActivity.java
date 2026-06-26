package com.balzikz.mathclient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class BootstrapActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.rgb(9, 11, 10));

        TextView status = new TextView(this);
        status.setText(NativeBridge.isLoaded()
                ? "MATH Native Core: ONLINE"
                : "MATH Native Core: FAILED");
        status.setTextColor(Color.WHITE);
        status.setTextSize(20);
        root.addView(status);

        Button stage51 = new Button(this);
        stage51.setText("ОТКРЫТЬ STAGE 5.1 • MINECRAFT 1.26.30.X");
        stage51.setOnClickListener(view ->
                startActivity(new Intent(this, Stage51Activity.class)));
        root.addView(stage51);

        Button diagnostics = new Button(this);
        diagnostics.setText("ОТКРЫТЬ DIAGNOSTICS");
        diagnostics.setOnClickListener(view ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        root.addView(diagnostics);

        Button legacy = new Button(this);
        legacy.setText("ОТКРЫТЬ LEGACY HOST LAB");
        legacy.setOnClickListener(view ->
                startActivity(new Intent(this, MinecraftGameHostLabActivity.class)));
        root.addView(legacy);

        setContentView(root);
    }
}
