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

        Button diagnostics = new Button(this);
        diagnostics.setText("ОТКРЫТЬ DIAGNOSTICS");
        diagnostics.setOnClickListener(view ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        root.addView(diagnostics);

        Button host = new Button(this);
        host.setText("ОТКРЫТЬ STAGE 3.9 GAME HOST");
        host.setOnClickListener(view ->
                startActivity(new Intent(this, MinecraftGameHostLabActivity.class)));
        root.addView(host);

        setContentView(root);
    }
}
