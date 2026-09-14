package com.balzikz.mathclient.foundation;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.balzikz.mathclient.foundation.core.HostState;
import java.io.*;
import java.util.concurrent.Future;

/** Isolated runtime/resource/surface preparation. Exact game Java/JNI adapter is intentionally absent. */
public final class GameHostActivity extends Activity implements SurfaceHolder.Callback {
    private final HostState state = new HostState();
    private SessionLog log;
    private TextView status;
    private HostEnvironment environment;
    private Future<?> preparation;
    private String lastPhase = "";
    private boolean readyReported;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = Ui.column(this); setContentView(root);
        Ui.text(this, root, "MATH · игровой хост", 22);
        status = Ui.text(this, root, "Подготовка файлов и окна…", 15);
        Ui.button(this, root, "Вернуться к журналу", this::finish);
        Ui.text(this, root, "После подготовки появится результат. Подключение самой игры ещё в разработке.", 14);
        try { log = SessionLog.bind(this, getIntent().getStringExtra("session")); }
        catch (IOException error) { state.fail(); status.setText("Не удалось открыть сессию: " + error.getMessage()); return; }
        log.event("HOST_ACTIVITY_CREATE", "minecraftLoaded=false; adapter=NOT_IMPLEMENTED");
        SurfaceView view = new SurfaceView(this);
        root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        view.getHolder().addCallback(this);
        boolean selfTest = BuildConfig.DEBUG && getIntent().getBooleanExtra("debug_host_self", false);
        preparation = ((FoundationApplication)getApplication()).io.submit(() -> {
            try {
                HostEnvironment result = HostEnvironment.prepare(getApplicationContext(), log, selfTest);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    environment = result; state.prepared(result.assetSamples > 0); update();
                });
            } catch (Exception error) {
                log.exception(Thread.currentThread().isInterrupted() ? "HOST_PREPARE_CANCELLED" : "HOST_PREPARE_ERROR", error);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    state.fail(); update(); status.append("\n" + error.getMessage());
                });
            }
        });
        update();
    }
    private void update() {
        String phase = state.phase();
        if (log != null && !phase.equals(lastPhase)) {
            log.event("HOST_PHASE", phase); lastPhase = phase;
        }
        if (state.environmentReady()) {
            status.setText("Файлы, assets и окно хоста готовы.\nMinecraft ожидает подключения игрового адаптера."
                    + "\nABI: " + environment.runtime.abi + "; библиотек: " + environment.runtime.libraries.size());
            if (!readyReported && log != null) {
                log.event("HOST_ENVIRONMENT_READY", "surface=true; assetSamples=" + environment.assetSamples
                        + "; minecraftLoaded=false; gameFrame=false");
            }
            readyReported = true;
        } else {
            readyReported = false;
            if (phase.equals("FAILED")) status.setText("Подготовка хоста прервана. Ошибка сохранена в сессии.");
            else if (phase.equals("PREPARING_RUNTIME")) status.setText("Проверка APK и подготовка библиотек…");
            else if (phase.equals("WAITING_FOR_SURFACE")) status.setText("Файлы готовы. Ожидается окно хоста…");
            else if (phase.equals("WAITING_FOR_RESUME")) status.setText("Хост приостановлен.");
        }
    }
    @Override public void surfaceCreated(SurfaceHolder holder) {
        state.surface(false, 0, 0);
        if (log != null) log.event("HOST_SURFACE_CREATED", "valid=" + holder.getSurface().isValid());
        update();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        boolean valid = holder.getSurface().isValid(); state.surface(valid, width, height);
        if (log != null) log.event("HOST_SURFACE_CHANGED", width + "x" + height + "; valid=" + valid + "; format=" + format);
        update();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        state.surface(false, 0, 0);
        if (log != null) log.event("HOST_SURFACE_DESTROYED", "No native renderer is attached");
        update();
    }
    @Override protected void onResume() {
        super.onResume(); state.resumed(true);
        if (log != null) log.event("HOST_RESUME", "activity resumed"); update();
    }
    @Override protected void onPause() {
        state.resumed(false);
        if (log != null) log.event("HOST_PAUSE", "activity paused"); update(); super.onPause();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (log != null) log.event("HOST_CONFIGURATION_CHANGED", "orientation=" + config.orientation);
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (log != null) log.event("HOST_WINDOW_FOCUS", Boolean.toString(focus));
    }
    @Override protected void onDestroy() {
        state.destroy();
        if (preparation != null) preparation.cancel(true);
        environment = null;
        if (log != null) log.event("HOST_DESTROY", "Preparation cancelled; session preserved");
        super.onDestroy();
    }
}
