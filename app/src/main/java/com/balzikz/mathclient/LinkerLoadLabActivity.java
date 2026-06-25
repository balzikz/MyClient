package com.balzikz.mathclient;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class LinkerLoadLabActivity extends Activity {

    private static final String[] TEST_LIBRARIES = {
            "libc++_shared.so",
            "libfmod.so",
            "libHttpClient.Android.so",
            "libmaesdk.so",
            "libPlayFabMultiplayer.so",
            "libMediaDecoders_Android.so",
            "libconscrypt_jni.so",
            "libmcfix.so"
    };

    private TextView reportView;
    private Button runButton;
    private Button stage35Button;
    private Button copyButton;
    private String report = "Stage 3.4 has not started.";
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 11, 10));
        getWindow().setNavigationBarColor(Color.rgb(9, 11, 10));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 11, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);

        root.addView(text("MATH SMALL RUNTIME CHAIN LAB", 21, Color.WHITE, true));
        root.addView(text(
                "Stage 3.4 / Eight-library ordered chain",
                12,
                Color.rgb(98, 216, 139),
                true));

        TextView note = text(
                "Лаборатория работает в отдельном C-процессе. Она проверяет SHA-256 восьми малых библиотек, загружает их одновременно и закрывает в обратном порядке. На Stage 3.4 libminecraftpe.so всё ещё не загружается.",
                11,
                Color.LTGRAY,
                false);
        note.setPadding(0, dp(8), 0, dp(8));
        root.addView(note);

        reportView = text(report, 11, Color.LTGRAY, false);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);

        runButton = new Button(this);
        runButton.setText("ЗАПУСТИТЬ 8-LIBRARY CHAIN");
        runButton.setOnClickListener(view -> runLinkerTest());
        root.addView(runButton);

        stage35Button = new Button(this);
        stage35Button.setText("ПЕРЕЙТИ К STAGE 3.5");
        stage35Button.setEnabled(false);
        stage35Button.setOnClickListener(view ->
                startActivity(new Intent(this, MinecraftLoadLabActivity.class)));
        root.addView(stage35Button);

        copyButton = new Button(this);
        copyButton.setText("СКОПИРОВАТЬ ОТЧЁТ");
        copyButton.setOnClickListener(view -> copyReport());
        root.addView(copyButton);

        setContentView(scroll);
        runPreflight();
    }

    private void runPreflight() {
        File directory = runtimeDirectory();
        StringBuilder value = new StringBuilder();
        value.append("MATH BEDROCK SMALL RUNTIME PREFLIGHT\n");
        value.append("Stage: 3.4\n");
        value.append("Process: ").append(processName()).append('\n');
        value.append("Runtime directory: ").append(directory.getAbsolutePath()).append("\n\n");

        boolean ready = directory.isDirectory();
        value.append("Directory gate: ").append(ready ? "PASS" : "BLOCK").append('\n');

        File manifest = new File(directory, "runtime-manifest.txt");
        boolean manifestReady = manifest.isFile() && manifest.canRead();
        value.append("Manifest: ").append(manifestReady ? "READY" : "MISSING").append('\n');
        ready &= manifestReady;

        for (String name : TEST_LIBRARIES) {
            File file = new File(directory, name);
            boolean fileReady = file.isFile() && file.canRead() && file.length() > 0;
            value.append(fileReady ? "[READY] " : "[MISSING] ")
                    .append(name);
            if (fileReady) {
                value.append(" | ").append(file.length()).append(" bytes");
            }
            value.append('\n');

            String expected = expectedHash(name);
            String actual = "";
            if (fileReady) {
                try {
                    actual = sha256(file);
                } catch (Exception error) {
                    value.append("  hash error=")
                            .append(error.getClass().getSimpleName())
                            .append(": ")
                            .append(error.getMessage())
                            .append('\n');
                }
            }

            boolean hashReady = fileReady
                    && !expected.isEmpty()
                    && expected.equalsIgnoreCase(actual);
            value.append("  expected sha256=").append(expected).append('\n');
            value.append("  actual sha256=")
                    .append(actual.isEmpty() ? "UNAVAILABLE" : actual)
                    .append('\n');
            value.append("  hash gate=").append(hashReady ? "PASS" : "BLOCK").append('\n');
            ready &= hashReady;
        }

        File minecraft = new File(directory, "libminecraftpe.so");
        value.append("libminecraftpe.so present: ")
                .append(minecraft.isFile() ? "YES" : "NO")
                .append('\n');
        value.append("libminecraftpe.so load policy at Stage 3.4: DENY\n");
        value.append(LinkerBridge.loadStatus()).append('\n');
        ready &= LinkerBridge.isLoaded();

        if (!ready) {
            value.append("Action: return to Stage 3.2 and update the runtime.\n");
        }
        value.append("Preflight verdict: ")
                .append(ready ? "READY TO RUN" : "BLOCKED");

        report = value.toString();
        reportView.setText(report);
        runButton.setEnabled(ready);
        stage35Button.setEnabled(false);
    }

    private void runLinkerTest() {
        if (busy) return;
        busy = true;
        runButton.setEnabled(false);
        stage35Button.setEnabled(false);
        copyButton.setEnabled(false);
        reportView.setText(
                "Running Stage 3.4 in secondary process...\n\n"
                        + "Eight small libraries will be loaded. Minecraft library remains blocked.");

        String path = runtimeDirectory().getAbsolutePath();
        new Thread(() -> {
            String result = LinkerBridge.runLinkerLoadTest(path);
            runOnUiThread(() -> {
                report = result;
                reportView.setText(result);
                busy = false;
                runButton.setEnabled(true);
                copyButton.setEnabled(true);
                stage35Button.setEnabled(
                        result.contains("Runtime verdict: READY FOR STAGE 3.5"));
                Toast.makeText(this, "Small runtime chain завершён.", Toast.LENGTH_LONG).show();
            });
        }, "MATH-Small-Runtime-Chain").start();
    }

    private File runtimeDirectory() {
        return new File(
                new File(getNoBackupFilesDir(), "bedrock-runtime"),
                BedrockProfile.ID);
    }

    private String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName();
        }
        return getPackageName() + ":linker_lab (pid=" + android.os.Process.myPid() + ")";
    }

    private String expectedHash(String name) {
        switch (name) {
            case "libc++_shared.so":
                return BedrockProfile.CXX_SHARED_SHA256;
            case "libfmod.so":
                return BedrockProfile.FMOD_SHA256;
            case "libHttpClient.Android.so":
                return BedrockProfile.HTTP_CLIENT_SHA256;
            case "libmaesdk.so":
                return BedrockProfile.MAE_SDK_SHA256;
            case "libPlayFabMultiplayer.so":
                return BedrockProfile.PLAYFAB_SHA256;
            case "libMediaDecoders_Android.so":
                return BedrockProfile.MEDIA_DECODERS_SHA256;
            case "libconscrypt_jni.so":
                return BedrockProfile.CONSCRYPT_SHA256;
            case "libmcfix.so":
                return BedrockProfile.SUPPORT_LIBRARY_SHA256;
            default:
                return "";
        }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }

        StringBuilder hex = new StringBuilder(64);
        for (byte item : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", item));
        }
        return hex.toString();
    }

    private void copyReport() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MATH Small Runtime Chain",
                report));
        Toast.makeText(this, "Отчёт скопирован.", Toast.LENGTH_SHORT).show();
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
