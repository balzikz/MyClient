package com.balzikz.mathclient;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {

    private static final String MINECRAFT_PACKAGE = "com.mojang.minecraftpe";

    private static final int BACKGROUND = Color.rgb(9, 11, 10);
    private static final int PANEL = Color.rgb(20, 24, 22);
    private static final int PANEL_BORDER = Color.rgb(48, 58, 52);
    private static final int TEXT_PRIMARY = Color.rgb(238, 244, 240);
    private static final int TEXT_SECONDARY = Color.rgb(145, 158, 150);
    private static final int ACCENT = Color.rgb(98, 216, 139);
    private static final int ACCENT_DARK = Color.rgb(13, 48, 27);
    private static final int WARNING = Color.rgb(238, 181, 91);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        MinecraftInstall minecraft = readMinecraftInstall();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BACKGROUND);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(32));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView symbol = text("∑", 20, ACCENT, Typeface.BOLD);
        symbol.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        content.addView(symbol);

        TextView title = text("MATH", 42, TEXT_PRIMARY, Typeface.BOLD);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        content.addView(title, marginTop(4));

        TextView subtitle = text("CLIENT / BEDROCK", 13, TEXT_SECONDARY, Typeface.BOLD);
        subtitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        subtitle.setLetterSpacing(0.16f);
        content.addView(subtitle, marginTop(2));

        LinearLayout hero = panel();
        content.addView(hero, marginTop(28));

        TextView version = text("0.2.1-alpha", 12, ACCENT, Typeface.BOLD);
        version.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hero.addView(version);

        TextView heroTitle = text("Bedrock Link", 25, TEXT_PRIMARY, Typeface.BOLD);
        hero.addView(heroTitle, marginTop(10));

        TextView heroBody = text(
                "MATH Client определяет установленный Minecraft Bedrock, читает номер его версии и запускает оригинальное приложение в экспериментальном общем стеке.",
                15,
                TEXT_SECONDARY,
                Typeface.NORMAL
        );
        heroBody.setLineSpacing(0f, 1.25f);
        hero.addView(heroBody, marginTop(10));

        Button launchButton = primaryButton(
                minecraft.installed ? "ЗАПУСТИТЬ MINECRAFT" : "MINECRAFT НЕ НАЙДЕН"
        );
        launchButton.setOnClickListener(view -> launchMinecraft());
        content.addView(launchButton, marginTop(22));

        TextView connectionLabel = text("ПОДКЛЮЧЕНИЕ", 12, TEXT_SECONDARY, Typeface.BOLD);
        connectionLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        connectionLabel.setLetterSpacing(0.18f);
        content.addView(connectionLabel, marginTop(34));

        LinearLayout connectionPanel = panel();
        content.addView(connectionPanel, marginTop(12));

        int connectionColor = minecraft.installed ? ACCENT : WARNING;
        String connectionTitle = minecraft.installed
                ? "●  MINECRAFT CONNECTED"
                : "○  MINECRAFT NOT FOUND";

        TextView statusTitle = text(connectionTitle, 13, connectionColor, Typeface.BOLD);
        statusTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        connectionPanel.addView(statusTitle);

        TextView statusText = text(
                minecraft.statusText(),
                14,
                TEXT_SECONDARY,
                Typeface.NORMAL
        );
        statusText.setLineSpacing(0f, 1.25f);
        connectionPanel.addView(statusText, marginTop(10));

        TextView compatibilityText = text(
                "Совместимость: STABLE\nЗапуск: SAME TASK / EXPERIMENTAL",
                12,
                TEXT_SECONDARY,
                Typeface.NORMAL
        );
        compatibilityText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        compatibilityText.setLineSpacing(0f, 1.25f);
        connectionPanel.addView(compatibilityText, marginTop(12));

        TextView modulesLabel = text("МОДУЛИ", 12, TEXT_SECONDARY, Typeface.BOLD);
        modulesLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        modulesLabel.setLetterSpacing(0.18f);
        content.addView(modulesLabel, marginTop(34));

        Button profilesButton = secondaryButton("ПРОФИЛИ");
        profilesButton.setOnClickListener(view -> notReady("Профили"));
        content.addView(profilesButton, marginTop(12));

        Button packsButton = secondaryButton("РЕСУРС-ПАКИ");
        packsButton.setOnClickListener(view -> notReady("Ресурс-паки"));
        content.addView(packsButton, marginTop(10));

        LinearLayout systemPanel = panel();
        content.addView(systemPanel, marginTop(28));

        TextView systemTitle = text("●  SYSTEM READY", 13, ACCENT, Typeface.BOLD);
        systemTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        systemPanel.addView(systemTitle);

        TextView systemText = text(
                "Bedrock запускается поверх MATH Client без FLAG_ACTIVITY_NEW_TASK",
                13,
                TEXT_SECONDARY,
                Typeface.NORMAL
        );
        systemPanel.addView(systemText, marginTop(8));

        TextView footer = text("MATH Client  /  built by balzikz", 11, TEXT_SECONDARY, Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        footer.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        content.addView(footer, marginTop(30));

        setContentView(scrollView);
    }

    private MinecraftInstall readMinecraftInstall() {
        try {
            PackageInfo packageInfo;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageInfo = getPackageManager().getPackageInfo(
                        MINECRAFT_PACKAGE,
                        PackageManager.PackageInfoFlags.of(0)
                );
            } else {
                packageInfo = getPackageManager().getPackageInfo(MINECRAFT_PACKAGE, 0);
            }

            String versionName = packageInfo.versionName == null
                    ? "не указана"
                    : packageInfo.versionName;

            long versionCode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = packageInfo.getLongVersionCode();
            } else {
                versionCode = packageInfo.versionCode;
            }

            return new MinecraftInstall(true, versionName, versionCode);
        } catch (PackageManager.NameNotFoundException exception) {
            return new MinecraftInstall(false, "—", 0);
        }
    }

    private void launchMinecraft() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(MINECRAFT_PACKAGE);

        if (launchIntent == null) {
            Toast.makeText(
                    this,
                    "Minecraft Bedrock не найден. Установи официальное приложение и перезапусти MATH Client.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        // PackageManager usually returns a launcher intent with NEW_TASK already set.
        // Clearing all launch flags asks Android to place Minecraft above this Activity
        // in the current task. Minecraft or the device firmware can still override it.
        launchIntent.setFlags(0);
        startActivity(launchIntent);
    }

    private void notReady(String moduleName) {
        Toast.makeText(
                this,
                moduleName + ": модуль появится в следующей версии.",
                Toast.LENGTH_SHORT
        ).show();
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(20));
        panel.setBackground(roundedBackground(PANEL, PANEL_BORDER, 1, 18));
        return panel;
    }

    private Button primaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(Color.rgb(5, 22, 12));
        button.setBackground(roundedBackground(ACCENT, ACCENT, 0, 14));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(TEXT_PRIMARY);
        button.setBackground(roundedBackground(ACCENT_DARK, PANEL_BORDER, 1, 14));
        return button;
    }

    private Button baseButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setLetterSpacing(0.08f);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(58));
        button.setAllCaps(false);
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        return button;
    }

    private TextView text(String value, float sizeSp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(Typeface.SANS_SERIF, style));
        return view;
    }

    private GradientDrawable roundedBackground(
            int fillColor,
            int strokeColor,
            int strokeWidthDp,
            int radiusDp
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0) {
            drawable.setStroke(dp(strokeWidthDp), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams marginTop(int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(marginDp);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class MinecraftInstall {
        private final boolean installed;
        private final String versionName;
        private final long versionCode;

        private MinecraftInstall(boolean installed, String versionName, long versionCode) {
            this.installed = installed;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        private String statusText() {
            if (!installed) {
                return "Minecraft Bedrock не установлен. После установки MATH Client обнаружит его автоматически.";
            }

            return "Minecraft Bedrock обнаружен\n"
                    + "Версия: " + versionName + "\n"
                    + "Код сборки: " + versionCode;
        }
    }
}
