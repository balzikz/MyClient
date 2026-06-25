package com.balzikz.mathclient;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
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

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;

public final class MinecraftJavaContractActivity extends Activity {

    private static final String TARGET_PACKAGE = "com.mojang.minecraftpe";
    private static final String TARGET_PREFIX = "com.mojang.minecraftpe";
    private static final String TARGET_MAIN_ACTIVITY = "com.mojang.minecraftpe.MainActivity";
    private static final int REPORT_LIMIT = 768 * 1024;

    private TextView reportView;
    private Button runButton;
    private Button copyButton;
    private String report = "Stage 3.7 has not started.";
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

        root.addView(text("MATH MINECRAFT JAVA CONTRACT", 21, Color.WHITE, true));
        root.addView(text(
                "Stage 3.7 / Installed APK class and native-method inventory",
                12,
                Color.rgb(98, 216, 139),
                true));

        TextView note = text(
                "Этот этап читает метаданные и DEX установленного Minecraft локально на телефоне. Классы загружаются с initialize=false только для reflection. Объекты не создаются, методы не вызываются, System.load и JNI_OnLoad не выполняются, байткод и APK никуда не копируются.",
                11,
                Color.LTGRAY,
                false);
        note.setPadding(0, dp(8), 0, dp(8));
        root.addView(note);

        reportView = text(report, 10, Color.LTGRAY, false);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);

        runButton = new Button(this);
        runButton.setText("СКАНИРОВАТЬ JAVA/JNI КОНТРАКТ");
        runButton.setOnClickListener(view -> runInventory());
        root.addView(runButton);

        copyButton = new Button(this);
        copyButton.setText("СКОПИРОВАТЬ ОТЧЁТ");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(view -> copyReport());
        root.addView(copyButton);

        setContentView(scroll);
        runPreflight();
    }

    private void runPreflight() {
        StringBuilder value = new StringBuilder();
        value.append("MATH BEDROCK JAVA CONTRACT PREFLIGHT\n");
        value.append("Stage: 3.7\n");
        value.append("Process: ").append(processName()).append('\n');
        value.append("Target package: ").append(TARGET_PACKAGE).append('\n');
        value.append("Class initialization: DISABLED\n");
        value.append("Method invocation: NONE\n");
        value.append("Native library loading: NONE\n\n");

        boolean installed;
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(
                    TARGET_PACKAGE,
                    PackageManager.GET_META_DATA);
            installed = info != null && info.sourceDir != null;
            value.append("Package gate: ").append(installed ? "PASS" : "BLOCK").append('\n');
            if (installed) {
                value.append("Base APK readable: ")
                        .append(new File(info.sourceDir).canRead() ? "YES" : "NO")
                        .append('\n');
            }
        } catch (Throwable error) {
            installed = false;
            value.append("Package gate: BLOCK\n");
            value.append("Error: ").append(errorText(error)).append('\n');
        }

        value.append("Preflight verdict: ")
                .append(installed ? "READY TO INVENTORY" : "BLOCKED");
        report = value.toString();
        reportView.setText(report);
        runButton.setEnabled(installed);
    }

    private void runInventory() {
        if (busy) return;
        busy = true;
        runButton.setEnabled(false);
        copyButton.setEnabled(false);
        reportView.setText(
                "Reading installed Minecraft package metadata and DEX tables...\n\n"
                        + "No classes will be initialized and no methods will be invoked.");

        new Thread(() -> {
            String result = buildInventory();
            runOnUiThread(() -> {
                report = result;
                reportView.setText(result);
                busy = false;
                runButton.setEnabled(true);
                copyButton.setEnabled(true);
                Toast.makeText(this, "Java/JNI contract inventory завершён.", Toast.LENGTH_LONG).show();
            });
        }, "MATH-Minecraft-Java-Contract").start();
    }

    @SuppressWarnings("deprecation")
    private String buildInventory() {
        BoundedReport value = new BoundedReport(REPORT_LIMIT);
        value.append("MATH BEDROCK JAVA CONTRACT INVENTORY\n");
        value.append("Stage: 3.7\n");
        value.append("Target package: ").append(TARGET_PACKAGE).append('\n');
        value.append("Class initialization: DISABLED (Class.forName initialize=false)\n");
        value.append("Instances created: NO\n");
        value.append("Methods invoked: NO\n");
        value.append("System.load invoked: NO\n");
        value.append("JNI_OnLoad invoked: NO\n");
        value.append("APK/DEX bytes exported: NO\n\n");

        PackageManager manager = getPackageManager();
        ApplicationInfo applicationInfo;
        PackageInfo packageInfo;
        try {
            applicationInfo = manager.getApplicationInfo(
                    TARGET_PACKAGE,
                    PackageManager.GET_META_DATA);
            int flags = PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS
                    | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_META_DATA
                    | PackageManager.MATCH_DISABLED_COMPONENTS;
            packageInfo = manager.getPackageInfo(TARGET_PACKAGE, flags);
        } catch (Throwable error) {
            value.append("Package lookup: FAIL\n");
            value.append("Error: ").append(errorText(error)).append('\n');
            value.append("Inventory verdict: PACKAGE METADATA UNAVAILABLE");
            return value.toString();
        }

        List<String> apkPaths = collectApkPaths(applicationInfo);
        Intent launchIntent = manager.getLaunchIntentForPackage(TARGET_PACKAGE);
        ComponentName launchComponent = launchIntent == null ? null : launchIntent.getComponent();

        value.append("=== PACKAGE METADATA ===\n");
        value.append("Package: ").append(packageInfo.packageName).append('\n');
        value.append("Version name: ").append(safe(packageInfo.versionName)).append('\n');
        value.append("Version code: ").append(versionCode(packageInfo)).append('\n');
        value.append("Application class: ").append(safe(applicationInfo.className)).append('\n');
        value.append("Launch activity: ")
                .append(launchComponent == null ? "NONE" : launchComponent.flattenToShortString())
                .append('\n');
        value.append("Native library dir: ").append(safe(applicationInfo.nativeLibraryDir)).append('\n');
        value.append("Data dir: ").append(safe(applicationInfo.dataDir)).append('\n');
        value.append("android.app.lib_name: ")
                .append(applicationInfo.metaData == null
                        ? "NONE"
                        : safe(applicationInfo.metaData.getString("android.app.lib_name")))
                .append('\n');
        value.append("APK count: ").append(apkPaths.size()).append('\n');
        for (int index = 0; index < apkPaths.size(); ++index) {
            File apk = new File(apkPaths.get(index));
            value.append("  [").append(index + 1).append("] ")
                    .append(apk.getAbsolutePath())
                    .append(" | readable=").append(apk.canRead() ? "YES" : "NO")
                    .append(" | bytes=").append(apk.isFile() ? apk.length() : -1)
                    .append('\n');
        }

        appendComponents(value, "ACTIVITIES", packageInfo.activities);
        appendComponents(value, "SERVICES", packageInfo.services);
        appendProviders(value, packageInfo.providers);

        value.append("\n=== DEX CLASS ENUMERATION ===\n");
        Set<String> minecraftClasses = new TreeSet<>();
        int dexOpenPass = 0;
        int dexOpenFail = 0;
        for (String path : apkPaths) {
            DexFile dexFile = null;
            try {
                dexFile = new DexFile(path);
                ++dexOpenPass;
                Enumeration<String> entries = dexFile.entries();
                while (entries.hasMoreElements()) {
                    String className = entries.nextElement();
                    if (className.equals(TARGET_PREFIX)
                            || className.startsWith(TARGET_PREFIX + ".")) {
                        minecraftClasses.add(className);
                    }
                }
                value.append("[DEX PASS] ").append(path).append('\n');
            } catch (Throwable error) {
                ++dexOpenFail;
                value.append("[DEX FAIL] ").append(path)
                        .append(" | ").append(errorText(error)).append('\n');
            } finally {
                if (dexFile != null) {
                    try {
                        dexFile.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        value.append("DEX opened: ").append(dexOpenPass).append('\n');
        value.append("DEX failed: ").append(dexOpenFail).append('\n');
        value.append("Minecraft namespace classes: ").append(minecraftClasses.size()).append('\n');
        for (String className : minecraftClasses) {
            value.append("  ").append(className).append('\n');
        }

        value.append("\n=== REFLECTION CONTRACT ===\n");
        if (apkPaths.isEmpty()) {
            value.append("Reflection loader: BLOCKED (no APK paths)\n");
            value.append("Inventory verdict: DEX PATH UNAVAILABLE");
            return value.toString();
        }

        String dexPath = joinPaths(apkPaths);
        File optimizedDirectory = new File(getCodeCacheDir(), "minecraft-contract-dex");
        if (!optimizedDirectory.isDirectory() && !optimizedDirectory.mkdirs()) {
            value.append("Optimized directory: CREATE FAILED\n");
        }

        DexClassLoader loader;
        try {
            loader = new DexClassLoader(
                    dexPath,
                    optimizedDirectory.getAbsolutePath(),
                    null,
                    getClassLoader());
            value.append("DexClassLoader: READY\n");
            value.append("Native library search path: DISABLED\n");
        } catch (Throwable error) {
            value.append("DexClassLoader: FAIL\n");
            value.append("Error: ").append(errorText(error)).append('\n');
            value.append("Inventory verdict: REFLECTION LOADER FAILED");
            return value.toString();
        }

        int classLoadPass = 0;
        int classLoadFail = 0;
        int nativeClassCount = 0;
        int nativeMethodCount = 0;
        boolean mainActivityLoaded = false;
        List<String> failedClasses = new ArrayList<>();

        for (String className : minecraftClasses) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                ++classLoadPass;
                if (TARGET_MAIN_ACTIVITY.equals(className)) {
                    mainActivityLoaded = true;
                    appendMainActivityShape(value, type);
                }

                List<Method> nativeMethods = nativeMethods(type);
                if (!nativeMethods.isEmpty()) {
                    ++nativeClassCount;
                    nativeMethodCount += nativeMethods.size();
                    value.append("\n[NATIVE CLASS] ").append(className).append('\n');
                    for (Method method : nativeMethods) {
                        value.append("  ").append(methodSignature(method)).append('\n');
                    }
                }
            } catch (Throwable error) {
                ++classLoadFail;
                if (failedClasses.size() < 80) {
                    failedClasses.add(className + " | " + errorText(error));
                }
            }
        }

        if (!failedClasses.isEmpty()) {
            value.append("\n=== REFLECTION FAILURES (CAPPED) ===\n");
            for (String failure : failedClasses) {
                value.append("[FAIL] ").append(failure).append('\n');
            }
        }

        value.append("\n=== INVENTORY VERDICT ===\n");
        value.append("Minecraft classes enumerated: ").append(minecraftClasses.size()).append('\n');
        value.append("Classes reflected: ").append(classLoadPass).append('\n');
        value.append("Classes reflection-failed: ").append(classLoadFail).append('\n');
        value.append("Classes declaring native methods: ").append(nativeClassCount).append('\n');
        value.append("Native methods discovered: ").append(nativeMethodCount).append('\n');
        value.append("MainActivity reflected: ").append(mainActivityLoaded ? "YES" : "NO").append('\n');
        value.append("Code initialized: NO\n");
        value.append("Methods invoked: NO\n");
        value.append("Report truncated: ").append(value.wasTruncated() ? "YES" : "NO").append('\n');

        if (mainActivityLoaded && nativeMethodCount > 0) {
            value.append("Inventory verdict: READY FOR STAGE 3.8 JNI SHELL DESIGN");
        } else if (!minecraftClasses.isEmpty()) {
            value.append("Inventory verdict: PARTIAL CONTRACT; DEX PARSER FALLBACK REQUIRED");
        } else {
            value.append("Inventory verdict: MINECRAFT JAVA NAMESPACE NOT ENUMERATED");
        }
        return value.toString();
    }

    private List<String> collectApkPaths(ApplicationInfo info) {
        Set<String> paths = new LinkedHashSet<>();
        if (info.sourceDir != null && !info.sourceDir.isEmpty()) {
            paths.add(info.sourceDir);
        }
        if (info.splitSourceDirs != null) {
            paths.addAll(Arrays.asList(info.splitSourceDirs));
        }
        return new ArrayList<>(paths);
    }

    private void appendComponents(BoundedReport value, String title, ActivityInfo[] items) {
        value.append("\n=== ").append(title).append(" ===\n");
        if (items == null || items.length == 0) {
            value.append("NONE\n");
            return;
        }
        List<ActivityInfo> sorted = new ArrayList<>(Arrays.asList(items));
        Collections.sort(sorted, Comparator.comparing(item -> safe(item.name)));
        for (ActivityInfo item : sorted) {
            value.append(safe(item.name))
                    .append(" | process=").append(safe(item.processName))
                    .append(" | exported=").append(item.exported ? "YES" : "NO")
                    .append(" | enabled=").append(item.enabled ? "YES" : "NO")
                    .append('\n');
        }
    }

    private void appendComponents(BoundedReport value, String title, ServiceInfo[] items) {
        value.append("\n=== ").append(title).append(" ===\n");
        if (items == null || items.length == 0) {
            value.append("NONE\n");
            return;
        }
        List<ServiceInfo> sorted = new ArrayList<>(Arrays.asList(items));
        Collections.sort(sorted, Comparator.comparing(item -> safe(item.name)));
        for (ServiceInfo item : sorted) {
            value.append(safe(item.name))
                    .append(" | process=").append(safe(item.processName))
                    .append(" | exported=").append(item.exported ? "YES" : "NO")
                    .append(" | enabled=").append(item.enabled ? "YES" : "NO")
                    .append('\n');
        }
    }

    private void appendProviders(BoundedReport value, ProviderInfo[] items) {
        value.append("\n=== PROVIDERS ===\n");
        if (items == null || items.length == 0) {
            value.append("NONE\n");
            return;
        }
        List<ProviderInfo> sorted = new ArrayList<>(Arrays.asList(items));
        Collections.sort(sorted, Comparator.comparing(item -> safe(item.name)));
        for (ProviderInfo item : sorted) {
            value.append(safe(item.name))
                    .append(" | authorities=").append(safe(item.authority))
                    .append(" | process=").append(safe(item.processName))
                    .append(" | exported=").append(item.exported ? "YES" : "NO")
                    .append('\n');
        }
    }

    private void appendMainActivityShape(BoundedReport value, Class<?> type) {
        value.append("\n=== MAINACTIVITY SHAPE ===\n");
        value.append("Class: ").append(type.getName()).append('\n');
        Class<?> superclass = type.getSuperclass();
        value.append("Superclass: ")
                .append(superclass == null ? "NONE" : superclass.getName())
                .append('\n');
        Class<?>[] interfaces = type.getInterfaces();
        value.append("Interfaces: ").append(interfaces.length).append('\n');
        for (Class<?> item : interfaces) {
            value.append("  ").append(item.getName()).append('\n');
        }
        value.append("Declared methods: ").append(type.getDeclaredMethods().length).append('\n');
        value.append("Declared native methods: ").append(nativeMethods(type).size()).append('\n');
    }

    private List<Method> nativeMethods(Class<?> type) {
        Method[] methods = type.getDeclaredMethods();
        List<Method> result = new ArrayList<>();
        for (Method method : methods) {
            if (Modifier.isNative(method.getModifiers())) {
                result.add(method);
            }
        }
        result.sort(Comparator.comparing(this::methodSignature));
        return result;
    }

    private String methodSignature(Method method) {
        StringBuilder value = new StringBuilder();
        String modifiers = Modifier.toString(method.getModifiers());
        if (!modifiers.isEmpty()) {
            value.append(modifiers).append(' ');
        }
        value.append(typeName(method.getReturnType()))
                .append(' ')
                .append(method.getName())
                .append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int index = 0; index < parameters.length; ++index) {
            if (index > 0) value.append(", ");
            value.append(typeName(parameters[index]));
        }
        value.append(')');
        return value.toString();
    }

    private String typeName(Class<?> type) {
        if (!type.isArray()) {
            return type.getName();
        }
        int dimensions = 0;
        Class<?> component = type;
        while (component.isArray()) {
            ++dimensions;
            component = component.getComponentType();
        }
        StringBuilder value = new StringBuilder(component.getName());
        for (int index = 0; index < dimensions; ++index) value.append("[]");
        return value.toString();
    }

    private long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private String joinPaths(List<String> paths) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < paths.size(); ++index) {
            if (index > 0) value.append(File.pathSeparator);
            value.append(paths.get(index));
        }
        return value.toString();
    }

    private String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName();
        }
        return getPackageName() + ":java_contract (pid=" + android.os.Process.myPid() + ")";
    }

    private String errorText(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private String safe(String value) {
        return value == null || value.isEmpty() ? "NONE" : value;
    }

    private void copyReport() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MATH Minecraft Java Contract",
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

    private static final class BoundedReport {
        private final StringBuilder value = new StringBuilder();
        private final int limit;
        private boolean truncated;

        BoundedReport(int limit) {
            this.limit = limit;
        }

        BoundedReport append(Object item) {
            if (truncated) return this;
            String text = String.valueOf(item);
            int remaining = limit - value.length();
            if (remaining <= 0) {
                truncated = true;
                return this;
            }
            if (text.length() <= remaining) {
                value.append(text);
            } else {
                value.append(text, 0, remaining);
                truncated = true;
            }
            return this;
        }

        BoundedReport append(char item) {
            if (!truncated && value.length() < limit) {
                value.append(item);
            } else {
                truncated = true;
            }
            return this;
        }

        boolean wasTruncated() {
            return truncated;
        }

        @Override
        public String toString() {
            if (truncated) {
                return value + "\n\n[REPORT TRUNCATED AT " + limit + " CHARACTERS]";
            }
            return value.toString();
        }
    }
}
