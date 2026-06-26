package com.balzikz.mathclient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MathShimLabActivity extends Activity {
    private static final int CREATE_DIAGNOSTICS_FILE = 4200;
    private TextView report;

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
        report.setTextSize(12);
        report.setTextIsSelectable(true);
        root.addView(report);

        Button launch = button("ЗАПУСТИТЬ STAGE 5.0.1 NATIVE HANDOFF");
        launch.setOnClickListener(view -> {
            HostJournal.reset(this);
            HostJournal.write(this, "LAUNCH_REQUESTED", "Starting RuntimeHostActivity stage=5.0.1");
            startActivity(new Intent(this, RuntimeHostActivity.class));
        });
        root.addView(launch);

        Button refresh = button("ОБНОВИТЬ ЖУРНАЛ");
        refresh.setOnClickListener(view -> refresh());
        root.addView(refresh);

        Button export = button("СОХРАНИТЬ DIAGNOSTICS (.TXT)");
        export.setOnClickListener(view -> chooseDiagnosticsDestination());
        root.addView(export);

        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_DIAGNOSTICS_FILE
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) return;
        writeDiagnostics(data.getData());
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    private void refresh() {
        report.setText(buildReport());
    }

    private String buildReport() {
        File runtime = HostJournal.runtime(this);
        File minecraft = HostJournal.game(this);
        File shim = new File(getApplicationInfo().nativeLibraryDir, "libmathshim.so");

        return "MATH CLIENT STAGE 5.0.1\n"
                + "Architecture: JVM System.load -> RTLD_NOLOAD -> single native entrypoint owner\n\n"
                + "Shim packaged: " + describe(shim) + "\n"
                + "Runtime directory: " + describe(runtime) + "\n"
                + "Minecraft library: " + describe(minecraft) + "\n"
                + "Connection state: " + safeConnectionStatus() + "\n\n"
                + "=== STAGE 5.0.1 HOST JOURNAL ===\n"
                + HostJournal.read(this)
                + "\n=== SIGNAL TRACE ===\n"
                + HostJournal.readSignalTrace(this);
    }

    private String safeConnectionStatus() {
        try {
            return MathShimBridge.combinedStatus();
        } catch (Throwable error) {
            return "UNAVAILABLE: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static String describe(File file) {
        if (!file.exists()) return "MISSING | " + file.getAbsolutePath();
        if (file.isDirectory()) return "READY DIRECTORY | " + file.getAbsolutePath();
        return "READY FILE | bytes=" + file.length() + " | " + file.getAbsolutePath();
    }

    private void chooseDiagnosticsDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE,
                "math-stage501-diagnostics-" + System.currentTimeMillis() + ".txt");
        startActivityForResult(intent, CREATE_DIAGNOSTICS_FILE);
    }

    private void writeDiagnostics(Uri destination) {
        try (OutputStream output = getContentResolver().openOutputStream(destination)) {
            if (output == null) throw new IllegalStateException("Destination stream is null");
            output.write(buildReport().getBytes(StandardCharsets.UTF_8));
            output.flush();
            HostJournal.write(this, "DIAGNOSTICS_EXPORTED", destination.toString());
        } catch (Throwable error) {
            HostJournal.write(this, "DIAGNOSTICS_EXPORT_FAIL",
                    error.getClass().getName() + ": " + error.getMessage());
        }
        refresh();
    }
}
