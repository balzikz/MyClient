package com.balzikz.mathclient.foundation;

import android.app.*;
import android.content.Context;
import android.os.Build;
import java.io.*;

final class ExitCollector {
    static void collect(Context context, SessionLog log) throws IOException {
        if (Build.VERSION.SDK_INT < 30) { log.event("EXIT_INFO_UNAVAILABLE", "Requires Android 11+"); return; }
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) throw new IOException("ActivityManager unavailable");
        StringBuilder text = new StringBuilder("Historical app exits; may belong to earlier sessions.\n");
        for (ApplicationExitInfo exit : am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 16)) {
            text.append("time=").append(exit.getTimestamp()).append(" pid=").append(exit.getPid())
                    .append(" process=").append(exit.getProcessName()).append(" reason=").append(exit.getReason())
                    .append(" status=").append(exit.getStatus()).append(" pssKb=").append(exit.getPss())
                    .append(" rssKb=").append(exit.getRss()).append(" description=").append(exit.getDescription()).append('\n');
            if (Build.VERSION.SDK_INT >= 31 && exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                String name = "native-exit-" + exit.getTimestamp() + "-" + exit.getPid() + ".pb";
                File file = new File(log.directory, name);
                if (file.isFile()) continue;
                try (InputStream in = exit.getTraceInputStream()) {
                    if (in == null) { text.append("native trace: not retained by OS\n"); continue; }
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192]; int n; long size = 0;
                        while ((n = in.read(buffer)) != -1) {
                            size += n;
                            if (size > 32L * 1024 * 1024) throw new IOException("Native trace exceeds 32 MiB cap (partial file)");
                            out.write(buffer, 0, n);
                        }
                        out.getFD().sync();
                    }
                    text.append("native trace: ").append(name).append('\n');
                } catch (IOException | SecurityException error) { text.append("trace error: ").append(error).append('\n'); }
            }
        }
        log.writeText("exits-" + System.currentTimeMillis() + "-" + System.nanoTime() + ".txt", text.toString());
        log.event("EXIT_INFO_COLLECTED", "Historical exits are evidence, not automatically attributed to this session");
    }
}
