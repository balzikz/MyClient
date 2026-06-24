package com.balzikz.mathclient;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public final class BootstrapActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String message = NativeBridge.isLoaded()
                ? "MATH Native Core: ONLINE"
                : "MATH Native Core: FAILED";

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();

        Intent diagnosticsIntent = new Intent(this, DiagnosticsActivity.class);
        startActivity(diagnosticsIntent);
        finish();
    }
}
