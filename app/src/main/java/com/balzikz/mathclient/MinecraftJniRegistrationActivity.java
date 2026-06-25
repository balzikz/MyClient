package com.balzikz.mathclient;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class MinecraftJniRegistrationActivity extends Activity {

    private static final String JOURNAL_NAME = "stage-3.8-journal.txt";
    private static final int EXPECTED_NATIVE_METHODS = 66;

    private static final String[] REQUIRED_LIBRARIES = {
            "libc++_shared.so",
            "libfmod.so",
            "libHttpClient.Android.so",
            "libmaesdk.so",
            "libPlayFabMultiplayer.so",
            "libMediaDecoders_Android.so",
            "libconscrypt_jni.so",
            "libmcfix.so",
            "libminecraftpe.so"
    };

    private static final String[] SHELL_CLASSES = {
            "com.mojang.minecraftpe.AppExitInfoHelper",
            "com.mojang.minecraftpe.BatteryMonitor",
            "com.mojang.minecraftpe.CrashManager",
            "com.mojang.minecraftpe.FilePickerManager",
            "com.mojang.minecraftpe.MainActivity",
            "com.mojang.minecraftpe.NetworkMonitor",
            "com.mojang.minecraftpe.NotificationListenerService",
            "com.mojang.minecraftpe.ThermalMonitor",
            "com.mojang.minecraftpe.WorldRecovery",
            "com.mojang.minecraftpe.Webview.MinecraftWebview",
            "com.mojang.minecraftpe.input.JellyBeanDeviceManager",
            "com.mojang.minecraftpe.store.NativeStoreListener",
            "com.mojang.minecraftpe.store.Product",
            "com.mojang.minecraftpe.store.Purchase"
    };

    private TextView reportView;
    private Button runButton;
    private Button copyButton;
    private Button killButton;
    private String report = "Stage 3.8 preflight has not started.";
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

        root.addView(text("MATH MINECRAFT JNI REGISTRATION", 21, Color.WHITE, true));
        root.addView(text(
                "Stage 3.8 / First controlled JNI_OnLoad",
                12,
                Color.rgb(98, 216, 139),
                true));

        TextView note = text(
                "Этот этап впервые вызывает JNI_OnLoad из libminecraftpe.so. GameActivity не создаётся, lifecycle не запускается, зарегистрированные native-методы не вызываются. После попытки библиотеки удерживаются до смерти отдельного процесса.",
                11,
                Color.LTGRAY,
                false);
        note.setPadding(0, dp(8), 0, dp(8));
        root.addView(note);

        reportView = text(report, 10, Color.LTGRAY, false);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);

        runButton = new Button(this);
        runButton.setText("ВЫЗВАТЬ JNI_OnLoad");
        runButton.setEnabled(false);
        runButton.setOnClickListener(view -> runRegistration());
        root.addView(runButton);

        copyButton = new Button(this);
        copyButton.setText("СКОПИРОВАТЬ ОТЧЁТ");
        copyButton.setOnClickListener(view -> copyReport());
        root.addView(copyButton);

        killButton = new Button(this);
        killButton.setText("ЗАВЕРШИТЬ :jni_registration PROCESS");
        killButton.setEnabled(false);
        killButton.setOnClickListener(view ->
                android.os.Process.killProcess(android.os.Process.myPid()));
        root.addView(killButton);

        setContentView(scroll);
        runPreflight();
    }

    private void runPreflight() {
        if (busy) return;
        busy = true;
        runButton.setEnabled(false);
        copyButton.setEnabled(false);
        reportView.setText("Checking Stage 3.8 shell and runtime...\n");

        new Thread(() -> {
            PreflightResult result = buildPreflight();
            runOnUiThread(() -> {
                report = result.report;
                reportView.setText(result.report);
                busy = false;
                runButton.setEnabled(result.ready);
                copyButton.setEnabled(true);
            });
        }, "MATH-JNI-Registration-Preflight").start();
    }

    private PreflightResult buildPreflight() {
        StringBuilder value = new StringBuilder();
        value.append("MATH BEDROCK JNI REGISTRATION PREFLIGHT\n");
        value.append("Stage: 3.8\n");
        value.append("Process: ").append(processName()).append('\n');
        value.append("Expected shell classes: ").append(SHELL_CLASSES.length).append('\n');
        value.append("Expected native methods: ").append(EXPECTED_NATIVE_METHODS).append("\n\n");

        boolean ready = true;
        File directory = runtimeDirectory();
        value.append("=== RUNTIME GATE ===\n");
        boolean directoryReady = directory.isDirectory();
        value.append("Directory: ").append(directory.getAbsolutePath()).append('\n');
        value.append("Directory gate: ").append(directoryReady ? "PASS" : "BLOCK").append('\n');
        ready &= directoryReady;

        File manifest = new File(directory, "runtime-manifest.txt");
        boolean manifestReady = manifest.isFile() && manifest.canRead();
        value.append("Manifest gate: ").append(manifestReady ? "PASS" : "BLOCK").append('\n');
        ready &= manifestReady;

        for (String name : REQUIRED_LIBRARIES) {
            File file = new File(directory, name);
            boolean fileReady = file.isFile() && file.canRead() && file.length() > 0;
            value.append(fileReady ? "[READY] " : "[MISSING] ")
                    .append(name);
            if (fileReady) value.append(" | ").append(file.length()).append(" bytes");
            value.append('\n');
            ready &= fileReady;
        }

        value.append('\n').append(LinkerBridge.loadStatus()).append('\n');
        ready &= LinkerBridge.isLoaded();

        value.append("\n=== JAVA SHELL GATE ===\n");
        int nativeMethodCount = 0;
        int classPassCount = 0;
        for (String name : SHELL_CLASSES) {
            try {
                Class<?> type = Class.forName(name, false, getClassLoader());
                ++classPassCount;
                int classNativeCount = countNativeMethods(type);
                nativeMethodCount += classNativeCount;
                value.append("[PASS] ").append(name)
                        .append(" | native methods=")
                        .append(classNativeCount)
                        .append('\n');
            } catch (Throwable error) {
                ready = false;
                value.append("[FAIL] ").append(name)
                        .append(" | ")
                        .append(error.getClass().getName())
                        .append(": ")
                        .append(error.getMessage())
                        .append('\n');
            }
        }

        try {
            Class<?> mainActivity = Class.forName(
                    "com.mojang.minecraftpe.MainActivity",
                    false,
                    getClassLoader());
            Class<?> superclass = mainActivity.getSuperclass();
            String superclassName = superclass == null ? "NONE" : superclass.getName();
            boolean superclassReady =
                    "com.google.androidgamesdk.GameActivity".equals(superclassName);
            value.append("MainActivity superclass: ").append(superclassName).append('\n');
            value.append("Superclass gate: ")
                    .append(superclassReady ? "PASS" : "BLOCK")
                    .append('\n');
            ready &= superclassReady;
        } catch (Throwable error) {
            ready = false;
            value.append("Superclass gate: BLOCK | ")
                    .append(error.getClass().getName())
                    .append(": ")
                    .append(error.getMessage())
                    .append('\n');
        }

        boolean methodCountReady = nativeMethodCount == EXPECTED_NATIVE_METHODS;
        value.append("Shell classes resolved: ")
                .append(classPassCount)
                .append('/')
                .append(SHELL_CLASSES.length)
                .append('\n');
        value.append("Native methods declared: ").append(nativeMethodCount).append('\n');
        value.append("Native method count gate: ")
                .append(methodCountReady ? "PASS" : "BLOCK")
                .append('\n');
        ready &= methodCountReady;

        value.append("\n=== PREVIOUS STAGE 3.8 JOURNAL ===\n");
        File journal = new File(directory, JOURNAL_NAME);
        if (journal.isFile() && journal.canRead()) {
            try {
                value.append(readText(journal));
            } catch (Throwable error) {
                value.append("Journal read failed: ")
                        .append(error.getClass().getName())
                        .append(": ")
                        .append(error.getMessage())
                        .append('\n');
            }
        } else {
            value.append("No previous journal.\n");
        }

        value.append("\nPreflight verdict: ")
                .append(ready ? "READY TO INVOKE JNI_OnLoad" : "BLOCKED");
        return new PreflightResult(value.toString(), ready);
    }

    private int countNativeMethods(Class<?> type) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())) {
                ++count;
            }
        }
        return count;
    }

    private void runRegistration() {
        if (busy) return;
        busy = true;
        runButton.setEnabled(false);
        copyButton.setEnabled(false);
        killButton.setEnabled(false);
        reportView.setText(
                "Running manual JNI_OnLoad in :jni_registration...\n\n"
                        + "If this screen disappears, reopen Stage 3.8 and copy the journal.");

        String path = runtimeDirectory().getAbsolutePath();
        new Thread(() -> {
            String result = LinkerBridge.runMinecraftJniRegistration(path);
            runOnUiThread(() -> {
                report = result;
                reportView.setText(result);
                busy = false;
                copyButton.setEnabled(true);
                killButton.setEnabled(true);
                Toast.makeText(this, "JNI registration attempt завершён.", Toast.LENGTH_LONG).show();
            });
        }, "MATH-Minecraft-JNI-OnLoad").start();
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
        return getPackageName() + ":jni_registration (pid=" + android.os.Process.myPid() + ")";
    }

    private String readText(File file) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1 && total < 64 * 1024) {
                int accepted = Math.min(count, 64 * 1024 - total);
                output.write(buffer, 0, accepted);
                total += accepted;
            }
            return output.toString("UTF-8");
        }
    }

    private void copyReport() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MATH Minecraft JNI Registration",
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

    private static final class PreflightResult {
        final String report;
        final boolean ready;

        PreflightResult(String report, boolean ready) {
            this.report = report;
            this.ready = ready;
        }
    }
}
