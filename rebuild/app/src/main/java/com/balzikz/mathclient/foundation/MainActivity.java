package com.balzikz.mathclient.foundation;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.balzikz.mathclient.foundation.core.SessionFiles;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    private SessionLog session;
    private FoundationApplication app;
    private TextView status, report, contractSummary;
    private final List<Button> controls = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean pendingProbe;
    private boolean pendingContractSelfTest;
    private boolean pendingHostSelfTest;
    private final Runnable awaitIo = new Runnable() {
        @Override public void run() {
            if (isDestroyed()) return;
            if (app.busy.get()) { setBusy(true); main.postDelayed(this, 300); }
            else { setBusy(false); work("Чтение журнала…", log -> {}); }
        }
    };
    private interface Work { void run(SessionLog log) throws Exception; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (FoundationApplication) getApplication();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this); scroll.addView(root); setContentView(scroll);
        Ui.text(this, root, "MATH / Foundation", 28);
        Ui.text(this, root, "0.3 · подготовка игрового хоста", 16);
        Ui.text(this, root, "Подготовка проверит файлы Minecraft и откроет окно хоста. Подключение самой игры ещё в разработке.", 15);
        status = Ui.text(this, root, "", 14);
        controls.add(Ui.button(this, root, "Подготовить игровой хост", () -> startHost(false)));
        controls.add(Ui.button(this, root, "Прочитать интерфейс Minecraft", () -> readContract(false)));
        contractSummary = Ui.text(this, root, "", 14);
        controls.add(Ui.button(this, root, "Поделиться контрактом TXT", this::shareContractText));
        controls.add(Ui.button(this, root, "Поделиться сессией ZIP", this::share));
        controls.add(Ui.button(this, root, "Состав APK и библиотек", () -> work("Чтение APK и SHA-256…", log -> RuntimeInventory.collect(this, log))));
        controls.add(Ui.button(this, root, "Проверить графическое окно", this::startProbe));
        controls.add(Ui.button(this, root, "Собрать причины завершения", () -> work("История завершений…", log -> ExitCollector.collect(this, log))));
        controls.add(Ui.button(this, root, "Обновить журнал", () -> work("Чтение журнала…", log -> {})));
        controls.add(Ui.button(this, root, "Копировать полный текст", this::copyText));
        controls.add(Ui.button(this, root, "Новая сессия", () -> select(SessionLog.newId())));
        controls.add(Ui.button(this, root, "Сохранённые сессии", this::chooseSession));
        Ui.text(this, root, "Отчёт содержит технические пути, версии и сведения об устройстве. Перед отправкой его можно просмотреть. APK, игровые файлы и токены в ZIP не включаются.", 13);
        report = Ui.text(this, root, "", 12); report.setTypeface(Typeface.MONOSPACE); report.setTextIsSelectable(true);
        String id = state != null ? state.getString("session") : getPreferences(0).getString("session", null);
        if (app.busy.get() && SessionLog.active() != null) {
            session = SessionLog.active();
            status.setText("Операция продолжается…\nСессия: " + session.id); setBusy(true);
        } else select(id == null ? SessionLog.newId() : id);
        // Debug-build-only, explicit CI smoke-test hook. No arbitrary file or library input.
        pendingProbe = BuildConfig.DEBUG && getIntent().getBooleanExtra("debug_probe", false) && state == null;
        pendingContractSelfTest = BuildConfig.DEBUG && getIntent().getBooleanExtra("debug_contract_self", false) && state == null;
        pendingHostSelfTest = BuildConfig.DEBUG && getIntent().getBooleanExtra("debug_host_self", false) && state == null;
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (session != null) out.putString("session", session.id);
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() {
        super.onResume();
        if (pendingProbe) { pendingProbe = false; startProbe(); }
        else if (pendingContractSelfTest) { pendingContractSelfTest = false; readContract(true); }
        else if (pendingHostSelfTest) { pendingHostSelfTest = false; startHost(true); }
        else if (session != null) main.post(awaitIo);
    }
    @Override protected void onPause() { main.removeCallbacks(awaitIo); super.onPause(); }
    private void select(String id) {
        if (app.busy.get()) { toast("Дождись завершения текущей операции"); return; }
        try {
            session = SessionLog.bind(this, id);
            getPreferences(0).edit().putString("session", id).apply();
            status.setText("Сессия: " + id);
            contractSummary.setText("Интерфейс запуска Minecraft ещё не прочитан.");
            report.setText("");
        } catch (IOException e) { status.setText("Не удалось открыть журнал: " + e.getMessage()); }
    }
    private void setBusy(boolean busy) { for (Button b : controls) b.setEnabled(!busy); }
    private void work(String label, Work action) {
        SessionLog log = session;
        if (log == null || !app.busy.compareAndSet(false, true)) return;
        setBusy(true); status.setText(label + "\nСессия: " + log.id);
        app.io.execute(() -> {
            String failure = null;
            try { action.run(log); }
            catch (Exception e) { log.exception("OPERATION_ERROR", e); failure = e.getMessage(); }
            String contents;
            try { contents = SessionFiles.readText(log.directory, 512 * 1024); }
            catch (IOException e) { contents = "Просмотр ограничен: " + e.getMessage() + "\nПолная сессия доступна в ZIP."; }
            final String display = contents, error = failure, summary = RuntimeContract.latestSummary(log);
            app.busy.set(false);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setBusy(false); report.setText(display);
                contractSummary.setText(summary);
                status.setText("Сессия: " + log.id
                        + (error == null ? "" : "\nОшибка: " + error)
                        + (log.writeError() == null ? "" : "\nОшибка сохранения: " + log.writeError()));
            });
        });
    }
    private void readContract(boolean selfTest) {
        work("Чтение интерфейса запуска…", log -> RuntimeContract.collect(this, log, message -> runOnUiThread(() -> {
            if (!isDestroyed()) status.setText(message + "\nСессия: " + log.id);
        }), selfTest));
    }
    private void startHost(boolean selfTest) {
        if (session == null || app.busy.get()) { toast("Сначала дождись завершения операции"); return; }
        session.event("HOST_REQUESTED", "Prepare installed runtime and surface; selfTest=" + selfTest);
        startActivity(new Intent(this, GameHostActivity.class).putExtra("session", session.id)
                .putExtra("debug_host_self", selfTest && BuildConfig.DEBUG));
    }
    private void startProbe() {
        if (session == null || app.busy.get()) { toast("Сначала дождись завершения операции"); return; }
        session.event("PROBE_REQUESTED", "Own native surface, not a Minecraft launch");
        startActivity(new Intent(this, ProbeActivity.class).putExtra("session", session.id));
    }
    private void copyText() {
        work("Подготовка текста…", log -> {
            // Never silently truncate text for Clipboard. Larger reports use ZIP.
            String text = SessionFiles.readText(log.directory, 220 * 1024);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                ClipboardManager manager = getSystemService(ClipboardManager.class);
                if (manager != null) { manager.setPrimaryClip(ClipData.newPlainText("MATH " + log.id, text)); toast("Полный текст скопирован"); }
            });
        });
    }
    private void share() {
        work("Подготовка ZIP…", log -> {
            log.event("EXPORT_REQUESTED", "Diagnostic text and OS native traces only");
            try { ExitCollector.collect(this, log); }
            catch (Exception error) { log.exception("EXIT_INFO_ERROR", error); }
            File exports = new File(getCacheDir(), "exports");
            if (!exports.isDirectory() && !exports.mkdirs()) throw new IOException("Cannot create export folder");
            File zip = new File(exports, "MATH-" + log.id + "-" + System.nanoTime() + ".zip");
            SessionFiles.zip(log.directory, zip);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", zip);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                Intent intent = new Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("MATH diagnostic session", uri));
                try { startActivity(Intent.createChooser(intent, "Отправить диагностическую сессию")); }
                catch (ActivityNotFoundException error) { toast("Нет приложения для отправки ZIP"); }
            });
        });
    }
    private void shareContractText() {
        work("Подготовка текста контракта…", log -> {
            File[] candidates = log.directory.listFiles(f -> f.isFile()
                    && f.getName().matches("contract-[0-9]+-[0-9]+[.]jsonl"));
            if (candidates == null || candidates.length == 0)
                throw new IOException("В этой сессии нет контракта. Выбери сохранённую сессию 0.2 или прочитай интерфейс Minecraft.");
            Arrays.sort(candidates, Comparator.comparing(File::getName).reversed());
            File source = candidates[0];
            if (!source.getCanonicalFile().getParentFile().equals(log.directory.getCanonicalFile()))
                throw new IOException("Contract path is outside the session");
            File exports = new File(getCacheDir(), "exports");
            if (!exports.isDirectory() && !exports.mkdirs()) throw new IOException("Cannot create export folder");
            File text = new File(exports, "MATH-contract-" + log.id + "-" + System.nanoTime() + ".txt");
            if (!text.createNewFile()) throw new IOException("Contract export already exists");
            boolean complete = false;
            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                 FileOutputStream out = new FileOutputStream(text)) {
                // Exact complete JSONL bytes, with a text MIME/name for clients that cannot unpack ZIP.
                byte[] buffer = new byte[65536]; long total = 0; int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > 32L * 1024 * 1024) throw new IOException("Контракт больше 32 MiB; используй ZIP.");
                    out.write(buffer, 0, n);
                }
                out.getFD().sync(); complete = true;
            } finally {
                if (!complete && !text.delete()) log.event("CONTRACT_EXPORT_CLEANUP_ERROR", text.getName());
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", text);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("MATH contract", uri));
                try { startActivity(Intent.createChooser(intent, "Отправить контракт Minecraft")); }
                catch (ActivityNotFoundException error) { toast("Нет приложения для отправки TXT"); }
            });
        });
    }
    private void chooseSession() {
        File[] dirs = SessionLog.root(this).listFiles(f -> f.isDirectory() && f.getName().matches("[0-9]{13}-[a-f0-9]{12}"));
        if (dirs == null || dirs.length == 0) { toast("Сессий пока нет"); return; }
        Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
        String[] names = new String[dirs.length];
        for (int i = 0; i < dirs.length; i++) names[i] = dirs[i].getName();
        new AlertDialog.Builder(this).setTitle("Сохранённые сессии").setItems(names, (dialog, index) -> {
            select(names[index]); work("Чтение журнала…", log -> {});
        }).show();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
