package com.balzikz.mathclient.foundation;

import android.app.Activity;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProbeActivity extends Activity implements SurfaceHolder.Callback2 {
    private SessionLog log;
    private TextView status;
    private HandlerThread thread;
    private Handler worker;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile int width, height;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this); setContentView(root);
        Ui.text(this, root, "MATH · проверка собственного окна", 22);
        status = Ui.text(this, root, "Это не Minecraft. Ожидается двухцветное тестовое поле.", 15);
        Ui.button(this, root, "Вернуться к журналу", this::finish);
        try { log = SessionLog.bind(this, getIntent().getStringExtra("session")); }
        catch (IOException error) { status.setText("Нет диагностической сессии: " + error.getMessage()); return; }
        log.event("PROBE_ACTIVITY_CREATE", "Own EGL probe; game not loaded");
        thread = new HandlerThread("math-egl-probe"); thread.start(); worker = new Handler(thread.getLooper());
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        surface.getHolder().addCallback(this);
    }
    @Override public void surfaceCreated(SurfaceHolder holder) {
        generation.incrementAndGet(); log.event("SURFACE_CREATED", "valid=" + holder.getSurface().isValid());
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        width = w; height = h; log.event("SURFACE_CHANGED", w + "x" + h + " format=" + format);
        draw(holder, null);
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        generation.incrementAndGet(); width = 0; height = 0;
        log.event("SURFACE_DESTROYED", "Outstanding frames are invalidated");
    }
    @Override public void surfaceRedrawNeeded(SurfaceHolder holder) { draw(holder, null); }
    @Override public void surfaceRedrawNeededAsync(SurfaceHolder holder, Runnable done) { draw(holder, done); }
    private void draw(SurfaceHolder holder, Runnable done) {
        int token = generation.get(), w = width, h = height;
        Surface surface = holder.getSurface();
        Runnable action = () -> {
            try {
                if (token != generation.get() || !surface.isValid() || w <= 0 || h <= 0) return;
                NativeProbe.load(log);
                String result = NativeProbe.draw(surface, w, h);
                boolean success = result.startsWith("PROBE_SWAP_OK");
                log.event(success ? "PROBE_SWAP_OK" : "PROBE_FRAME_ERROR", result);
                runOnUiThread(() -> {
                    if (!isDestroyed()) status.setText((success ? "Собственный EGL-кадр отправлен.\n" : "Ошибка проверки окна.\n")
                            + "Minecraft не загружен.\n" + result);
                });
            } catch (Exception | LinkageError error) {
                log.exception("PROBE_ERROR", error);
                runOnUiThread(() -> { if (!isDestroyed()) status.setText("Ошибка JNI/EGL: " + error); });
            } finally { if (done != null) done.run(); }
        };
        if (worker == null || !worker.post(action)) { if (done != null) done.run(); }
    }
    @Override protected void onDestroy() {
        generation.incrementAndGet();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
